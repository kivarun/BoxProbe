/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.systeminfo

import com.google.ai.edge.gallery.systeminfo.DeviceSnapshot
import com.google.ai.edge.gallery.systeminfo.NpuProbeResult
import com.google.ai.edge.gallery.systeminfo.NpuProbeStageResult
import com.google.ai.edge.gallery.systeminfo.NpuProbeStatus
import com.google.ai.edge.gallery.systeminfo.npuProbeFormatDuration
import com.google.ai.edge.gallery.systeminfo.npuProbeStatusLabel
import com.google.ai.edge.gallery.systeminfo.npuProbeStageLabel

/**
 * Composes the remote-support diagnostics text for the System Info screen: build and
 * device identity plus the full NPU probe record. Pure data-in/text-out so the format
 * stays unit-testable; the caller only puts the result on the clipboard.
 */
fun composeNpuDiagnostics(
  build: BuildInfoSnapshot,
  device: DeviceSnapshot?,
  probeStatus: NpuProbeStatus,
  result: NpuProbeResult?,
): String = buildString {
  appendLine("BoxProbe diagnostics")
  appendLine("build: ${build.versionName} (${build.versionCode}) ${build.buildType}")
  appendLine("build SHA: ${build.gitCommit}")
  appendLine("applicationId: ${build.applicationId}")
  if (device != null) {
    appendLine("device: ${device.manufacturer} ${device.model} (${device.device})")
    appendLine("brand: ${device.brand}  hardware: ${device.hardware}")
    appendLine("SoC: ${device.socManufacturer.ifEmpty { "—" }} / ${device.socModel.ifEmpty { "—" }}")
    appendLine("probable vendor: ${device.probableVendor.name}")
    appendLine("android: ${device.androidRelease} (API ${device.apiLevel})")
  }
  appendLine("probe status: ${npuProbeStatusLabel(probeStatus)}")
  val precheck = result?.precheck
  if (precheck != null) {
    appendLine("model: ${precheck.model}")
    appendLine("vendor: ${precheck.vendorLabel.ifEmpty { "—" }}")
    appendLine("SoC model (precheck): ${precheck.socModel.ifEmpty { "—" }}")
    appendLine("HTP generation: ${precheck.htpGeneration.ifEmpty { "—" }}")
    appendLine("nativeLibraryDir: ${precheck.nativeLibraryDir}")
    appendLine("vendor runtime dir: ${precheck.vendorDispatchDirPath.ifEmpty { "—" }}")
    appendLine(
      "vendor dir exists: " +
        if (precheck.vendorDispatchDirExists) "yes" else "no",
    )
    appendLine("vendor dir .so names: ${precheck.vendorDispatchVisibleSoNames.joinToString(", ").ifEmpty { "—" }}")
    appendLine("missing required libs: ${precheck.vendorDispatchMissingLibs.joinToString(", ").ifEmpty { "—" }}")
    appendLine("vendor errors: ${precheck.vendorDispatchErrors.joinToString(" | ").ifEmpty { "—" }}")
  }
  if (result != null) {
    appendLine("total duration: ${npuProbeFormatDuration(result.totalDurationMs)}")
    for (stage in result.stageResults) {
      appendLine("stage ${stage.stage.name}: ${npuProbeStageLabel(stage)}")
    }
  }
}
