package com.google.ai.edge.gallery.systeminfo

/** Label shown in the "Runtime validated" row for the device-matched vendor. */
fun npuProbeRuntimeValidatedLabel(status: NpuProbeStatus, result: NpuProbeResult?): String =
  when {
    status == NpuProbeStatus.INITIALIZATION_FAILED ->
      result?.failedStage?.let { "Failed at ${it.name}" } ?: "Initialization failed"
    status == NpuProbeStatus.INFERENCE_FAILED -> "Inference smoke failed"
    status == NpuProbeStatus.NOT_VALIDATED ->
      when (result?.delegateVerdict) {
        NpuDelegateVerdict.CPU_FALLBACK -> "CPU fallback — NPU not validated"
        else -> "Delegate unknown — NPU not validated"
      }
    status == NpuProbeStatus.SUCCESS -> "NPU execution validated"
    else -> npuProbeStatusLabel(status)
  }

fun npuProbeStatusLabel(status: NpuProbeStatus): String =
  when (status) {
    NpuProbeStatus.NOT_PROBED -> "Not probed"
    NpuProbeStatus.RUNNING -> "Running…"
    NpuProbeStatus.SUCCESS -> "Validated"
    NpuProbeStatus.INITIALIZATION_FAILED -> "Initialization failed"
    NpuProbeStatus.INFERENCE_FAILED -> "Inference failed"
    NpuProbeStatus.NOT_VALIDATED -> "Not validated"
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
