package com.google.ai.edge.gallery.systeminfo

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.gallery.data.Model
import java.io.File

/** Stages of the active NPU initialization probe, in execution order. */
enum class NpuProbeStage {
  PRECHECK,
  LITERT_CORE_LIBRARY_LOAD,
  DISPATCH_LIBRARY_LOAD,
  DISPATCH_API_HANDSHAKE,
  DISPATCH_INITIALIZE,
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
  /** Absolute path of the dispatch library loaded by the diagnostic dlopen stage. */
  val dispatchLibraryPath: String = "",
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
    /**
     * Last executed stage for diagnostic runs that intentionally stop before the
     * full initialization path, or null when the run ran through.
     */
    val stoppedAfterStage: NpuProbeStage? = null,
    /** Dispatch API handshake diagnostics, when the handshake stage ran. */
    val dispatchHandshake: NpuDispatchHandshakeResult? = null,
    /** Dispatch initialize diagnostics, when the initialize stage ran. */
    val dispatchInitialize: NpuDispatchInitializeResult? = null,
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

  /** MediaTek dispatch library loaded by the diagnostic dlopen stage. */
  const val DISPATCH_LIBRARY_NAME = "libLiteRtDispatch_MediaTek.so"

  /** LiteRT core C API runtime required by the dispatch library (DT_NEEDED). */
  const val CORE_LIBRARY_NAME = "libLiteRt.so"

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

    // --- Stage 2: LITERT_CORE_LIBRARY_LOAD (pure dlopen diagnostic). ---
    val corePath = File(nativeLibraryDir, CORE_LIBRARY_NAME).absolutePath
    val coreStart = SystemClock.elapsedRealtime()
    val coreError: Throwable? =
      try {
        System.load(corePath)
        null
      } catch (t: Throwable) {
        t
      }
    stageResults.add(
      stageResult(NpuProbeStage.LITERT_CORE_LIBRARY_LOAD, coreStart, coreError)
    )
    if (coreError != null) {
      return NpuProbeResult(
        precheck = precheck.copy(dispatchLibraryPath = corePath),
        stageResults = stageResults,
        failedStage = NpuProbeStage.LITERT_CORE_LIBRARY_LOAD,
        stoppedAfterStage = NpuProbeStage.LITERT_CORE_LIBRARY_LOAD,
        totalDurationMs = elapsedSince(startTotal),
      )
    }

    // --- Stage 3: DISPATCH_LIBRARY_LOAD (pure dlopen diagnostic, no LiteRT calls). ---
    val dispatchPath = File(nativeLibraryDir, DISPATCH_LIBRARY_NAME).absolutePath
    val loadStart = SystemClock.elapsedRealtime()
    val loadError: Throwable? =
      try {
        System.load(dispatchPath)
        null
      } catch (t: Throwable) {
        t
      }
    stageResults.add(stageResult(NpuProbeStage.DISPATCH_LIBRARY_LOAD, loadStart, loadError))
    if (loadError != null) {
      return NpuProbeResult(
        precheck = precheck.copy(dispatchLibraryPath = dispatchPath),
        stageResults = stageResults,
        failedStage = NpuProbeStage.DISPATCH_LIBRARY_LOAD,
        stoppedAfterStage = NpuProbeStage.DISPATCH_LIBRARY_LOAD,
        totalDurationMs = elapsedSince(startTotal),
      )
    }

    // --- Stage 4: DISPATCH_API_HANDSHAKE (dlsym + LiteRtDispatchGetApi only). ---
    // The native bridge dlopens the already-loaded dispatch library, resolves
    // LiteRtDispatchGetApi and returns the API version plus null-ness of the
    // interface pointers. It never calls initialize and never touches Neuron.
    val handshakeStart = SystemClock.elapsedRealtime()
    var handshakeError: Throwable? = null
    var handshake: NpuDispatchHandshakeResult? = null
    try {
      val json = NpuDispatchHandshakeBridge.handshake(dispatchPath)
      handshake =
        parseNpuDispatchHandshakeJson(json)
          ?: throw IllegalStateException("Dispatch handshake: unparsable result: $json")
      if (handshake.status != "OK") {
        throw IllegalStateException(
          "Dispatch handshake failed: ${handshake.error.ifEmpty { "status=${handshake.status}" }}"
        )
      }
    } catch (t: Throwable) {
      handshakeError = t
    }
    stageResults.add(
      stageResult(NpuProbeStage.DISPATCH_API_HANDSHAKE, handshakeStart, handshakeError)
    )
    if (handshakeError != null) {
      return NpuProbeResult(
        precheck = precheck.copy(dispatchLibraryPath = dispatchPath),
        stageResults = stageResults,
        failedStage = NpuProbeStage.DISPATCH_API_HANDSHAKE,
        stoppedAfterStage = NpuProbeStage.DISPATCH_API_HANDSHAKE,
        dispatchHandshake = handshake,
        totalDurationMs = elapsedSince(startTotal),
      )
    }

    // --- Stage 5: DISPATCH_INITIALIZE (LiteRtDispatchInitialize only). ---
    // Creates a fresh LiteRtEnvironment with a single DispatchLibraryDir string
    // option pointing at the installer-managed nativeLibraryDir, an empty
    // LiteRtOptions, and calls the dispatch's initialize entry point. It never
    // creates Engine/model/device contexts and never loads the model.
    // Upstream source contract (revision 0b1b17f): the MediaTek dispatch keeps
    // references to both the environment and options beyond initialize, so the
    // bridge deliberately does not destroy them.
    val initializeStart = SystemClock.elapsedRealtime()
    var initializeError: Throwable? = null
    var initializeResult: NpuDispatchInitializeResult? = null
    try {
      val json =
        NpuDispatchHandshakeBridge.initializeDispatch(
          coreLibraryPath = corePath,
          dispatchLibraryPath = dispatchPath,
          nativeLibraryDir = nativeLibraryDir,
        )
      initializeResult =
        parseNpuDispatchInitializeJson(json)
          ?: throw IllegalStateException("Dispatch initialize: unparsable result: $json")
      if (initializeResult.status != "OK") {
        throw IllegalStateException(
          "Dispatch initialize failed: ${initializeResult.error.ifEmpty {
            "status=${initializeResult.initStatus} (${initializeResult.statusString})"
          }}"
        )
      }
    } catch (t: Throwable) {
      initializeError = t
    }
    stageResults.add(
      stageResult(NpuProbeStage.DISPATCH_INITIALIZE, initializeStart, initializeError)
    )

    // Diagnostic build: the probe always stops here, engine initialization
    // (Backend.NPU / Engine / model) is a separate later increment.
    return NpuProbeResult(
      precheck = precheck.copy(dispatchLibraryPath = dispatchPath),
      stageResults = stageResults,
      failedStage = initializeError?.let { NpuProbeStage.DISPATCH_INITIALIZE },
      stoppedAfterStage = NpuProbeStage.DISPATCH_INITIALIZE,
      dispatchHandshake = handshake,
      dispatchInitialize = initializeResult,
      totalDurationMs = elapsedSince(startTotal),
    )
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
}
