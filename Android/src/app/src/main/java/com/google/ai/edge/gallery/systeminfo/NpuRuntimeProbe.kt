package com.google.ai.edge.gallery.systeminfo

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.npu.VendorDispatchPreparation
import com.google.ai.edge.gallery.runtime.npu.prepareVendorDispatchRuntimeForDevice
import com.google.ai.edge.gallery.runtime.npu.resolveNpuNativeLibraryDir
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
  INFERENCE_SMOKE,
  DELEGATE_VALIDATED,
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
  val socModel: String = "",
  val htpGeneration: String = "",
  val vendorDispatchMissingLibs: List<String> = emptyList(),
  val vendorDispatchErrors: List<String> = emptyList(),
)

enum class NpuProbeStatus {
  NOT_PROBED,
  RUNNING,
  /** Engine + conversation ready, inference smoke ran, and the delegate is proven NPU. */
  SUCCESS,
  /** Initialization failed before a conversation existed. */
  INITIALIZATION_FAILED,
  /** Conversation existed but the smoke inference threw. */
  INFERENCE_FAILED,
  /** Inference ran but the delegate verdict is not DispatchDelegate — CPU fallback or unknown. */
  NOT_VALIDATED,
  NO_MODEL,
}

/** Full result of one active NPU probe run. */
data class NpuProbeResult(
  val precheck: NpuProbePrecheck?,
  val stageResults: List<NpuProbeStageResult>,
  /** Stage where the probe stopped, or null when every stage passed (SUCCESS). */
  val failedStage: NpuProbeStage?,
  val totalDurationMs: Long,
  /** Delegate verdict derived from the probe window's partitioning logs. */
  val delegateVerdict: NpuDelegateVerdict = NpuDelegateVerdict.UNKNOWN,
  /** Raw partitioning evidence behind the verdict (may be empty when capture failed). */
  val delegateEvidence: NpuDelegateEvidence? = null,
  /** Whether self-logcat capture produced any lines at all. */
  val logcatCaptureAvailable: Boolean = false,
)

/**
 * Active probe of the production LiteRT-LM NPU path.
 *
 * It drives the exact runtime path used by `LlmChatModelHelper` — vendor dispatch
 * isolation, `Backend.NPU(nativeLibraryDir = …)`, engine initialization, conversation
 * creation, a minimal smoke inference — recording per-stage durations and raw
 * exceptions. The probe result counts as validated only when the smoke inference
 * ran AND the partitioning logs prove the transformer graphs were delegated to the
 * vendor dispatch delegate (HTP). No fallback backend is attempted.
 */
object NpuRuntimeProbe {

  /** At most this many .so names are recorded in the precheck diagnostics. */
  const val MAX_LISTED_SO_NAMES = 20

  /** Fixed smoke prompt: a few tokens, only to trigger actual graph execution. */
  const val SMOKE_PROMPT = "Hi"

  /**
   * Runs the probe for [model] on the caller thread. Blocking native calls are
   * expected; callers must invoke this off the main thread.
   */
  fun run(context: Context, model: Model): NpuProbeResult {
    val startTotal = SystemClock.elapsedRealtime()
    val probeStartWallClock = System.currentTimeMillis()
    val stageResults = mutableListOf<NpuProbeStageResult>()

    // --- Stage 1: PRECHECK (no LiteRT calls). ---
    val precheckStart = SystemClock.elapsedRealtime()
    val modelPath = model.getPath(context = context)
    val nativeLibraryDirRaw: String? = context.applicationInfo.nativeLibraryDir
    // The vendor dispatch runtime is prepared exactly once per probe run: the same
    // preparation feeds the precheck diagnostics and the Backend.NPU creation below.
    // The vendor directory is only touched when the installer nativeLibraryDir exists.
    val vendorPreparation: VendorDispatchPreparation? =
      nativeLibraryDirRaw
        ?.takeIf { it.isNotEmpty() && File(it).isDirectory }
        ?.let { prepareVendorDispatchRuntimeForDevice(context) }
    val precheck =
      collectPrecheck(
        modelName = model.name,
        modelPath = modelPath,
        nativeLibraryDir = nativeLibraryDirRaw,
        vendorPreparation = vendorPreparation,
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

    // --- Stages 2..7: the production initialization + execution validation path. ---
    var engine: Engine? = null
    var smokeThrowable: Throwable? = null
    try {
      val backendStart = SystemClock.elapsedRealtime()
      val backend =
        Backend.NPU(
          nativeLibraryDir = resolveNpuNativeLibraryDir(nativeLibraryDirRaw, vendorPreparation),
        )
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
      var conversation: com.google.ai.edge.litertlm.Conversation? = null
      try {
        conversation = initialized.createConversation(ConversationConfig(samplerConfig = null))
      } catch (t: Throwable) {
        conversationError = t
      }
      stageResults.add(stageResult(NpuProbeStage.CONVERSATION_CREATED, conversationStart, conversationError))
      if (conversationError != null || conversation == null) {
        return failedResult(precheck, stageResults, NpuProbeStage.CONVERSATION_CREATED, startTotal)
      }

      // --- INFERENCE_SMOKE: the minimum inference that forces graph execution. ---
      val smokeStart = SystemClock.elapsedRealtime()
      try {
        conversation.sendMessage(SMOKE_PROMPT)
      } catch (t: Throwable) {
        smokeThrowable = t
      }
      stageResults.add(stageResult(NpuProbeStage.INFERENCE_SMOKE, smokeStart, smokeThrowable))
      runCatching { conversation.close() }
      if (smokeThrowable != null) {
        return failedResult(precheck, stageResults, NpuProbeStage.INFERENCE_SMOKE, startTotal)
      }

      // --- DELEGATE_VALIDATED: partitioning evidence from this process. ---
      val delegateStart = SystemClock.elapsedRealtime()
      val captured = captureSelfLogcatLines(sinceMs = probeStartWallClock)
      val evidence = classifyDelegateLines(captured)
      val verdict = evidence.verdict
      val validated = verdict == NpuDelegateVerdict.DISPATCH_DELEGATED
      stageResults.add(
        NpuProbeStageResult(
          stage = NpuProbeStage.DELEGATE_VALIDATED,
          durationMs = SystemClock.elapsedRealtime() - delegateStart,
          passed = validated,
          exceptionClass = if (validated) null else NpuDelegateVerdict::class.java.name,
          exceptionMessage =
            if (validated) null else npuDelegateVerdictLabel(verdict) + delegateOpsSuffix(evidence),
        ),
      )
      if (!validated) {
        return failedResult(
          precheck = precheck,
          stageResults = stageResults,
          failedStage = NpuProbeStage.DELEGATE_VALIDATED,
          startTotalMs = startTotal,
          delegateVerdict = verdict,
          delegateEvidence = evidence,
          logcatCaptureAvailable = captured.isNotEmpty(),
        )
      }

      stageResults.add(stageResult(NpuProbeStage.SUCCESS, SystemClock.elapsedRealtime(), null))
      return NpuProbeResult(
        precheck = precheck,
        stageResults = stageResults,
        failedStage = null,
        totalDurationMs = elapsedSince(startTotal),
        delegateVerdict = verdict,
        delegateEvidence = evidence,
        logcatCaptureAvailable = captured.isNotEmpty(),
      )
    } catch (t: Throwable) {
      // Any unexpected crash inside the probe itself must not kill the caller.
      stageResults.add(
        NpuProbeStageResult(
          stage = NpuProbeStage.DELEGATE_VALIDATED,
          durationMs = 0,
          passed = false,
          exceptionClass = t.javaClass.name,
          exceptionMessage = t.message,
        ),
      )
      return failedResult(
        precheck = precheck,
        stageResults = stageResults,
        failedStage = NpuProbeStage.DELEGATE_VALIDATED,
        startTotalMs = startTotal,
        delegateVerdict = NpuDelegateVerdict.UNKNOWN,
        delegateEvidence = null,
        logcatCaptureAvailable = false,
      )
    } finally {
      try {
        engine?.close()
      } catch (t: Throwable) {
        // Closing a partially initialized engine must not mask the probe outcome.
      }
    }
  }

  private fun delegateOpsSuffix(evidence: NpuDelegateEvidence): String {
    val ops = npuDelegateOpsLabel(evidence)
    return if (ops.isNullOrEmpty()) "" else " ($ops)"
  }

  private fun failedResult(
    precheck: NpuProbePrecheck?,
    stageResults: List<NpuProbeStageResult>,
    failedStage: NpuProbeStage,
    startTotalMs: Long,
    delegateVerdict: NpuDelegateVerdict = NpuDelegateVerdict.UNKNOWN,
    delegateEvidence: NpuDelegateEvidence? = null,
    logcatCaptureAvailable: Boolean = false,
  ): NpuProbeResult =
    NpuProbeResult(
      precheck = precheck,
      stageResults = stageResults,
      failedStage = failedStage,
      totalDurationMs = elapsedSince(startTotalMs),
      delegateVerdict = delegateVerdict,
      delegateEvidence = delegateEvidence,
      logcatCaptureAvailable = logcatCaptureAvailable,
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
    modelName: String,
    modelPath: String,
    nativeLibraryDir: String?,
    vendorPreparation: VendorDispatchPreparation?,
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
    var socModel = ""
    var htpGeneration = ""
    var vendorDispatchMissingLibs: List<String> = emptyList()
    var vendorDispatchErrors: List<String> = emptyList()
    if (vendorPreparation != null) {
      vendorLabel = vendorPreparation.vendor.label
      vendorDispatchDirPath = vendorPreparation.vendorDispatchDir.absolutePath
      vendorDispatchDirExists = vendorPreparation.vendorDispatchDir.isDirectory
      vendorDispatchSoCount = vendorPreparation.visibleSoNames.size
      vendorDispatchSoNames = vendorPreparation.visibleSoNames
      socModel = vendorPreparation.socModel
      htpGeneration = vendorPreparation.htpGeneration
      vendorDispatchMissingLibs = vendorPreparation.missingRequired
      vendorDispatchErrors = vendorPreparation.errors
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
      socModel = socModel,
      htpGeneration = htpGeneration,
      vendorDispatchMissingLibs = vendorDispatchMissingLibs,
      vendorDispatchErrors = vendorDispatchErrors,
    )
  }
}
