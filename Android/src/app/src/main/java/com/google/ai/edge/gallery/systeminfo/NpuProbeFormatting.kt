package com.google.ai.edge.gallery.systeminfo

/** Label shown in the "Runtime validated" row for the device-matched vendor. */
fun npuProbeRuntimeValidatedLabel(status: NpuProbeStatus, result: NpuProbeResult?): String =
  when {
    status == NpuProbeStatus.INITIALIZATION_FAILED ->
      result?.failedStage?.let { "Failed at ${it.name}" } ?: "Initialization failed"
    status == NpuProbeStatus.INITIALIZATION_PASSED &&
      result?.stoppedAfterStage == NpuProbeStage.DISPATCH_INITIALIZE ->
      "Core + dispatch + API handshake + initialize"
    status == NpuProbeStatus.INITIALIZATION_PASSED &&
      result?.stoppedAfterStage == NpuProbeStage.DISPATCH_API_HANDSHAKE ->
      "Core + dispatch + API handshake"
    status == NpuProbeStatus.INITIALIZATION_PASSED &&
      result?.stoppedAfterStage == NpuProbeStage.DISPATCH_LIBRARY_LOAD ->
      "Core + dispatch loaded"
    else -> npuProbeStatusLabel(status)
  }

fun npuProbeStatusLabel(status: NpuProbeStatus): String =
  when (status) {
    NpuProbeStatus.NOT_PROBED -> "Not probed"
    NpuProbeStatus.RUNNING -> "Running…"
    NpuProbeStatus.INITIALIZATION_PASSED -> "Initialization passed"
    NpuProbeStatus.INITIALIZATION_FAILED -> "Initialization failed"
    NpuProbeStatus.NO_MODEL -> "No downloaded NPU-compatible LiteRT model"
  }

fun npuProbeFailedStageLabel(result: NpuProbeResult): String? =
  result.failedStage?.let { "Failed at ${it.name}" }

/** Compact label for one stage row, e.g. "passed in 120 ms" or failure details. */
fun npuProbeStageLabel(result: NpuProbeStageResult): String =
  if (result.passed) {
    "passed in ${npuProbeFormatDuration(result.durationMs)}"
  } else {
    buildString {
      append("failed in ")
      append(npuProbeFormatDuration(result.durationMs))
      result.exceptionClass?.takeIf { it.isNotEmpty() }?.let { append(" — "); append(it) }
      result.exceptionMessage?.takeIf { it.isNotEmpty() }?.let { append(": "); append(it) }
    }
  }

fun npuProbeFormatDuration(millis: Long): String =
  if (millis >= 1000) "%.2f s".format(millis / 1000.0) else "$millis ms"

/**
 * Diagnostic rows for the dispatch API handshake (order matches the UI),
 * e.g. "API version" -> "0.1.0", "Async interface" -> "absent"/"present".
 */
fun npuProbeHandshakeRows(handshake: NpuDispatchHandshakeResult): List<Pair<String, String>> {
  val rows = mutableListOf<Pair<String, String>>()
  rows.add("Dispatch API status" to handshake.status)
  rows.add("API version" to "${handshake.major}.${handshake.minor}.${handshake.patch}")
  rows.add("Basic interface" to if (handshake.interfacePresent) "present" else "absent")
  rows.add("Async interface" to if (handshake.asyncPresent) "present" else "absent")
  rows.add("Graph interface" to if (handshake.graphPresent) "present" else "absent")
  if (handshake.error.isNotEmpty()) {
    rows.add("Handshake raw error" to handshake.error)
  }
  return rows
}

/** Diagnostic rows for the dispatch initialize stage, e.g. "OK"/"ERROR + status string". */
fun npuProbeInitializeRows(initialize: NpuDispatchInitializeResult): List<Pair<String, String>> {
  val rows = mutableListOf<Pair<String, String>>()
  val value =
    if (initialize.status == "OK") {
      "OK"
    } else {
      listOf(
          initialize.status,
          initialize.initStatus.toString(),
          initialize.statusString,
        )
        .filter { it.isNotEmpty() }
        .joinToString(" — ")
    }
  rows.add("Dispatch initialize" to value)
  if (initialize.adapterProbe.isNotEmpty()) {
    rows.add(
      "Adapter probe" to
        initialize.adapterProbe.joinToString("\n") { entry ->
          val status = if (entry.ok) "ok" else "fail"
          entry.name + " → " + status +
            (if (entry.error.isNotEmpty()) " (${"dlerror"}: ${entry.error})" else "")
        },
    )
  }
  if (initialize.error.isNotEmpty()) {
    rows.add("Initialize raw error" to initialize.error)
  }
  return rows
}
