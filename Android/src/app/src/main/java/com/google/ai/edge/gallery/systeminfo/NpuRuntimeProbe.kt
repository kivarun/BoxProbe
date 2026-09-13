package com.google.ai.edge.gallery.systeminfo

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
 * Active probe of the production LiteRT-LM NPU path.
 *
 * It drives the exact runtime path used by `LlmChatModelHelper` — vendor dispatch
 * isolation, `Backend.NPU(nativeLibraryDir = …)`, engine initialization and
 * conversation creation — recording per-stage durations and raw exceptions.
 * No inference is performed and no fallback backend is attempted.
 */
object NpuRuntimeProbe {

  /** At most this many .so names are recorded in the precheck diagnostics. */
  const val MAX_LISTED_SO_NAMES = 20

  /**
   * Runs the probe for [model] on the caller thread. Blocking native calls are
   * expected; callers must invoke this off the main thread.
   */
  fun run(context: Context, model: Model): NpuProbeResult {
    val startTotal = SystemClock.elapsedRealtime()
    val stageResults = mutableListOf<NpuProbeStageResult>()

    // --- Stage 1: PRECHECK (no LiteRT calls). ---
    val precheckStart = SystemClock.elapsedRealtime()
    val modelPath = model.getPath(context = context)
    val nativeLibraryDirRaw: String? = context.applicationInfo.nativeLibraryDir
    val precheck =
      collectPrecheck(
        context,
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
    stageResults.add(stageResult(NpuProbeStage.PRECHECK, precheckStart, precheckError))
    if (precheckError != null) {
      return NpuProbeResult(
        precheck = precheck,
        stageResults = stageResults,
        failedStage = NpuProbeStage.PRECHECK,
        totalDurationMs = elapsedSince(startTotal),
      )
    }

    // --- Stages 2..5: the production initialization path. ---
    val npuNativeLibraryDir = npuNativeLibraryDirForDevice(context)
    var engine: Engine? = null
    try {
      val backendStart = SystemClock.elapsedRealtime()
      val backend = Backend.NPU(nativeLibraryDir = npuNativeLibraryDir)
      stageResults.add(stageResult(NpuProbeStage.BACKEND_CREATED, backendStart, null))

      val engineStart = SystemClock.elapsedRealtime()
      var created: Engine? = null
      var createError: Throwable? = null
      try {
        created =
          Engine(
            EngineConfig(
              modelPath = modelPath,
              backend = backend,
              visionBackend = null,
              audioBackend = null,
              maxNumTokens = null,
              cacheDir = null,
            ),
          )
      } catch (t: Throwable) {
        createError = t
      }
      engine = created
      stageResults.add(stageResult(NpuProbeStage.ENGINE_CREATED, engineStart, createError))
      if (createError != null) {
        return failedResult(precheck, stageResults, NpuProbeStage.ENGINE_CREATED, startTotal)
      }
      val initialized = engine!!

      val initializeStart = SystemClock.elapsedRealtime()
      var initializeError: Throwable? = null
      try {
        initialized.initialize()
      } catch (t: Throwable) {
        initializeError = t
      }
      stageResults.add(stageResult(NpuProbeStage.ENGINE_INITIALIZED, initializeStart, initializeError))
      if (initializeError != null) {
        return failedResult(precheck, stageResults, NpuProbeStage.ENGINE_INITIALIZED, startTotal)
      }

      val conversationStart = SystemClock.elapsedRealtime()
      var conversationError: Throwable? = null
      var conversationCreated = false
      try {
        val conversation =
          initialized.createConversation(ConversationConfig(samplerConfig = null))
        conversation.close()
        conversationCreated = true
      } catch (t: Throwable) {
        conversationError = t
      }
      stageResults.add(stageResult(NpuProbeStage.CONVERSATION_CREATED, conversationStart, conversationError))
      if (conversationError != null || !conversationCreated) {
        return failedResult(precheck, stageResults, NpuProbeStage.CONVERSATION_CREATED, startTotal)
      }

      stageResults.add(stageResult(NpuProbeStage.SUCCESS, SystemClock.elapsedRealtime(), null))
      return NpuProbeResult(
        precheck = precheck,
        stageResults = stageResults,
        failedStage = null,
        totalDurationMs = elapsedSince(startTotal),
      )
    } finally {
      try {
        engine?.close()
      } catch (t: Throwable) {
        // Closing a partially initialized engine must not mask the probe outcome.
      }
    }
  }

  private fun failedResult(
    precheck: NpuProbePrecheck?,
    stageResults: List<NpuProbeStageResult>,
    failedStage: NpuProbeStage,
    startTotalMs: Long,
  ): NpuProbeResult =
    NpuProbeResult(
      precheck = precheck,
      stageResults = stageResults,
      failedStage = failedStage,
      totalDurationMs = elapsedSince(startTotalMs),
    )

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
    context: Context,
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
    var vendorLabel = ""
    var vendorDispatchDirPath = ""
    var vendorDispatchDirExists = false
    var vendorDispatchSoCount = 0
    var vendorDispatchSoNames: List<String> = emptyList()
    if (exists) {
      val vendor =
        npuDispatchVendorForDevice(
          SocVendorDetector.detect(
            socManufacturer = android.os.Build.SOC_MANUFACTURER ?: "",
            socModel = android.os.Build.SOC_MODEL ?: "",
          ),
        )
      if (vendor != null) {
        vendorLabel = vendor.label
        val preparation = prepareVendorDispatchRuntime(context, vendor)
        vendorDispatchDirPath = preparation.vendorDispatchDir.absolutePath
        vendorDispatchDirExists = preparation.vendorDispatchDir.isDirectory
        vendorDispatchSoCount = preparation.visibleSoNames.size
        vendorDispatchSoNames = preparation.visibleSoNames
      }
    }
    return NpuProbePrecheck(
      model = modelName,
      modelPath = modelPath,
      nativeLibraryDir = nativeLibraryDir ?: "",
      directoryExists = exists,
      directoryReadable = readable,
      visibleSoCount = soNames.size,
      visibleSoNames = soNames.take(MAX_LISTED_SO_NAMES),
      vendorLabel = vendorLabel,
      vendorDispatchDirPath = vendorDispatchDirPath,
      vendorDispatchDirExists = vendorDispatchDirExists,
      vendorDispatchVisibleSoCount = vendorDispatchSoCount,
      vendorDispatchVisibleSoNames = vendorDispatchSoNames.take(MAX_LISTED_SO_NAMES),
    )
  }
}
