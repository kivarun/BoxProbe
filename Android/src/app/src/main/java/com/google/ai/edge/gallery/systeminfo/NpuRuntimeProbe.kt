package com.google.ai.edge.gallery.systeminfo

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_MAX_TOKEN
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import java.io.File

/** Stages of the active NPU initialization probe, in execution order. */
enum class NpuProbeStage {
  PRECHECK,
  BACKEND_CREATED,
  ENGINE_CREATED,
  ENGINE_INITIALIZED,
  CONVERSATION_CREATED,
  SUCCESS,
}

/** Outcome of a single probe stage. */
data class NpuProbeStageResult(
  val stage: NpuProbeStage,
  val durationMs: Long,
  val passed: Boolean,
  val exceptionClass: String?,
  val exceptionMessage: String?,
)

/**
 * Diagnostics captured before any LiteRT call: the exact upstream
 * `applicationInfo.nativeLibraryDir` value and what the filesystem directly exposes
 * there, plus the vendor-isolated dispatch directory prepared for the device vendor.
 * Deliberately not derived from the package APK inventory.
 */
data class NpuProbePrecheck(
  val model: String,
  val modelPath: String,
  val nativeLibraryDir: String,
  val directoryExists: Boolean,
  val directoryReadable: Boolean,
  val visibleSoCount: Int,
  val visibleSoNames: List<String>,
  val vendorLabel: String = "",
  val vendorDispatchDirPath: String = "",
  val vendorDispatchDirExists: Boolean = false,
  val vendorDispatchVisibleSoCount: Int = 0,
  val vendorDispatchVisibleSoNames: List<String> = emptyList(),
)

enum class NpuProbeStatus {
  NOT_PROBED,
  RUNNING,
  INITIALIZATION_PASSED,
  INITIALIZATION_FAILED,
  NO_MODEL,
}

/** Full result of one active NPU probe run. */
data class NpuProbeResult(
  val precheck: NpuProbePrecheck?,
  val stageResults: List<NpuProbeStageResult>,
  /** Stage where the probe stopped, or null when every stage passed (SUCCESS). */
  val failedStage: NpuProbeStage?,
  val totalDurationMs: Long,
)

/**
 * Active diagnostic probe of the LiteRT-LM NPU backend.
 *
 * It drives the exact upstream runtime path used by `LlmChatModelHelper`
 * (`Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)`) through
 * engine initialization and conversation creation, recording per-stage durations and
 * raw exceptions. No inference is performed and no fallback backend is attempted.
 */
object NpuRuntimeProbe {

  /** At most this many .so names are recorded in the precheck diagnostics. */
  const val MAX_LISTED_SO_NAMES = 20

  /**
   * Runs the probe for [model] on the caller thread. Blocking native calls are
   * expected; callers must invoke this off the main thread.
   */
  @OptIn(ExperimentalApi::class)
  fun run(context: Context, model: Model): NpuProbeResult {
    val startTotal = SystemClock.elapsedRealtime()
    val stageResults = mutableListOf<NpuProbeStageResult>()

    // --- Stage 1: PRECHECK (no LiteRT calls). ---
    val precheckStart = SystemClock.elapsedRealtime()
    val modelPath = model.getPath(context = context)
    val nativeLibraryDirRaw: String? = context.applicationInfo.nativeLibraryDir
    val precheck =
      collectPrecheck(
        modelName = model.name,
        modelPath = modelPath,
        nativeLibraryDir = nativeLibraryDirRaw,
      )
    val nativeLibraryDir: String = nativeLibraryDirRaw ?: ""
    val precheckError: Throwable? =
      when {
        nativeLibraryDir.isEmpty() ->
          IllegalStateException("applicationInfo.nativeLibraryDir is null or empty")
        !precheck.directoryExists ->
          IllegalStateException("nativeLibraryDir '$nativeLibraryDir' does not exist")
        else -> null
      }

    // Vendor-isolated dispatch runtime: on multi-vendor devices the plain
    // nativeLibraryDir exposes dispatch libraries of several vendors and LiteRT may
    // pick an unrelated one (observed: GoogleTensor chosen on MT6991). Prepare an
    // app-private directory with symlinks to only the device vendor's libraries and
    // point the NPU backend at it.
    var vendorDispatch: VendorDispatchPreparation? = null
    var vendorDispatchError: Throwable? = null
    if (precheckError == null) {
      val socVendor =
        SocVendorDetector.detect(
          socManufacturer = Build.SOC_MANUFACTURER ?: "",
          socModel = Build.SOC_MODEL ?: "",
        )
      val dispatchVendor = npuDispatchVendorForDevice(socVendor)
      if (dispatchVendor == null) {
        vendorDispatchError =
          IllegalStateException(
            "NPU dispatch isolation is not available for device SoC vendor: ${socVendor.name}"
          )
      } else {
        val prepared = prepareVendorDispatchRuntime(context, dispatchVendor)
        vendorDispatch = prepared
        vendorDispatchError =
          when {
            prepared.errors.isNotEmpty() ->
              IllegalStateException(
                "Vendor dispatch preparation failed: ${prepared.errors.joinToString("; ")}"
              )
            prepared.missingRequired.isNotEmpty() ->
              IllegalStateException(
                "Required ${dispatchVendor.label} dispatch libraries missing from " +
                  "nativeLibraryDir: ${prepared.missingRequired}"
              )
            else -> null
          }
      }
    }

    val enrichedPrecheck =
      precheck.copy(
        vendorLabel = vendorDispatch?.vendor?.label ?: "unknown",
        vendorDispatchDirPath = vendorDispatch?.vendorDispatchDir?.absolutePath ?: "",
        vendorDispatchDirExists = vendorDispatch?.dirExists ?: false,
        vendorDispatchVisibleSoCount = vendorDispatch?.visibleSoNames?.size ?: 0,
        vendorDispatchVisibleSoNames = vendorDispatch?.visibleSoNames ?: emptyList(),
      )

    val precheckFailure = precheckError ?: vendorDispatchError
    stageResults.add(stageResult(NpuProbeStage.PRECHECK, precheckStart, precheckFailure))
    if (precheckFailure != null) {
      return NpuProbeResult(
        precheck = enrichedPrecheck,
        stageResults = stageResults,
        failedStage = NpuProbeStage.PRECHECK,
        totalDurationMs = elapsedSince(startTotal),
      )
    }

    var engine: Engine? = null
    var conversation: Conversation? = null

    try {
      // --- Stage 2: BACKEND_CREATED. ---
      val backendHolder = arrayOfNulls<Backend>(1)
      recordStage(stageResults, NpuProbeStage.BACKEND_CREATED) {
        // Same expression as LlmChatModelHelper for NPU/TPU, but pointing at the
        // vendor-isolated dispatch directory prepared in PRECHECK.
        backendHolder[0] =
          Backend.NPU(
            nativeLibraryDir = vendorDispatch!!.vendorDispatchDir.absolutePath
          )
      }
        ?.let {
          return NpuProbeResult(
            enrichedPrecheck,
            stageResults,
            NpuProbeStage.BACKEND_CREATED,
            elapsedSince(startTotal),
          )
        }

      val maxTokens =
        model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = DEFAULT_MAX_TOKEN)
      val engineConfig =
        EngineConfig(
          modelPath = modelPath,
          backend = backendHolder[0]!!,
          maxNumTokens = maxTokens,
          cacheDir =
            if (modelPath.startsWith("/data/local/tmp")) {
              context.getExternalFilesDir(null)?.absolutePath
            } else {
              null
            },
        )

      // --- Stage 3: ENGINE_CREATED. ---
      recordStage(stageResults, NpuProbeStage.ENGINE_CREATED) { engine = Engine(engineConfig) }
        ?.let {
          return NpuProbeResult(
            enrichedPrecheck,
            stageResults,
            NpuProbeStage.ENGINE_CREATED,
            elapsedSince(startTotal),
          )
        }

      // --- Stage 4: ENGINE_INITIALIZED. ---
      recordStage(stageResults, NpuProbeStage.ENGINE_INITIALIZED) { engine!!.initialize() }
        ?.let {
          return NpuProbeResult(
            enrichedPrecheck,
            stageResults,
            NpuProbeStage.ENGINE_INITIALIZED,
            elapsedSince(startTotal),
          )
        }

      // --- Stage 5: CONVERSATION_CREATED. ---
      // No SamplerConfig, matching the production NPU path in LlmChatModelHelper.
      recordStage(stageResults, NpuProbeStage.CONVERSATION_CREATED) {
        conversation = engine!!.createConversation(ConversationConfig(samplerConfig = null))
      }
        ?.let {
          return NpuProbeResult(
            enrichedPrecheck,
            stageResults,
            NpuProbeStage.CONVERSATION_CREATED,
            elapsedSince(startTotal),
          )
        }
    } finally {
      // Cleanup runs both after success and after any partial initialization failure.
      closeQuietly { conversation?.close() }
      closeQuietly { engine?.close() }
    }

    return NpuProbeResult(
      precheck = enrichedPrecheck,
      stageResults = stageResults,
      failedStage = null,
      totalDurationMs = elapsedSince(startTotal),
    )
  }

  private inline fun recordStage(
    stageResults: MutableList<NpuProbeStageResult>,
    stage: NpuProbeStage,
    block: () -> Unit,
  ): Throwable? {
    val start = SystemClock.elapsedRealtime()
    val error: Throwable? =
      try {
        block()
        null
      } catch (t: Throwable) {
        t
      }
    stageResults.add(stageResult(stage, start, error))
    return error
  }

  private fun stageResult(
    stage: NpuProbeStage,
    startMs: Long,
    error: Throwable?,
  ): NpuProbeStageResult =
    NpuProbeStageResult(
      stage = stage,
      durationMs = SystemClock.elapsedRealtime() - startMs,
      passed = error == null,
      exceptionClass = error?.javaClass?.name,
      exceptionMessage = error?.message,
    )

  private fun elapsedSince(startTotalMs: Long): Long = SystemClock.elapsedRealtime() - startTotalMs

  private fun collectPrecheck(
    modelName: String,
    modelPath: String,
    nativeLibraryDir: String?,
  ): NpuProbePrecheck {
    val dir: File? = nativeLibraryDir?.takeIf { it.isNotEmpty() }?.let { File(it) }
    val exists = dir?.isDirectory == true
    val readable = if (exists) dir!!.canRead() else false
    val soNames: List<String> =
      if (readable) {
        (dir!!.listFiles() ?: emptyArray())
          .filter { it.isFile && it.name.endsWith(".so") }
          .map { it.name }
          .sorted()
      } else {
        emptyList()
      }
    return NpuProbePrecheck(
      model = modelName,
      modelPath = modelPath,
      nativeLibraryDir = nativeLibraryDir ?: "",
      directoryExists = exists,
      directoryReadable = readable,
      visibleSoCount = soNames.size,
      visibleSoNames = soNames.take(MAX_LISTED_SO_NAMES),
    )
  }

  private fun closeQuietly(block: () -> Unit) {
    try {
      block()
    } catch (_: Throwable) {
      // A cleanup failure must not mask the probe outcome.
    }
  }
}
