package com.google.ai.edge.gallery.data

/**
 * Artifact-level compatibility helpers for the BoxProbe model catalog.
 *
 * Compatibility is declared per downloadable artifact in the version-controlled catalog
 * (backend accelerators + optional target SoC), never inferred from file names.
 */

/** Whether the artifact can run on [deviceSoc]: generic artifacts can, SoC-specific ones must match. */
fun artifactMatchesDeviceSoc(model: Model, deviceSoc: String): Boolean =
  model.targetSoc == null || model.targetSoc.equals(deviceSoc, ignoreCase = true)

/** Compact compatibility summary shown under the model name, e.g. "GPU / CPU · Generic". */
fun modelCompatibilitySummary(model: Model, deviceSoc: String): String {
  val backend = model.accelerators.joinToString(" / ") { it.label }
  val scope =
    if (model.targetSoc == null) {
      "Generic"
    } else {
      "Target: ${model.targetSoc!!.uppercase()}"
    }
  val base = "$backend · $scope"
  return if (model.targetSoc != null && artifactMatchesDeviceSoc(model, deviceSoc)) {
    "$base · Compatible with this device"
  } else {
    base
  }
}
