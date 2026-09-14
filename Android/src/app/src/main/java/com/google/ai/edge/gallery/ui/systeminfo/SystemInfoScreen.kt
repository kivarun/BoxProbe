package com.google.ai.edge.gallery.ui.systeminfo

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.systeminfo.DeviceMatchStatus
import com.google.ai.edge.gallery.systeminfo.GpuSnapshot
import com.google.ai.edge.gallery.systeminfo.NpuProbeStatus
import com.google.ai.edge.gallery.systeminfo.ProbeStatus
import com.google.ai.edge.gallery.systeminfo.RuntimeVendor
import com.google.ai.edge.gallery.systeminfo.SystemInfoSnapshot
import com.google.ai.edge.gallery.systeminfo.VulkanSnapshot
import com.google.ai.edge.gallery.systeminfo.npuProbeFormatDuration
import com.google.ai.edge.gallery.systeminfo.npuProbeRuntimeValidatedLabel
import com.google.ai.edge.gallery.systeminfo.npuProbeStageLabel
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import java.text.DateFormat
import java.util.Date

/**
 * Technical, compact view of the device and the bundled AI runtime.
 *
 * The bundled-library inventory explicitly distinguishes "bundled in APK" from
 * "matches this device" from "validated at runtime". For the device-matched vendor,
 * runtime validation comes from the active NPU initialization probe; other vendors
 * are never probed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemInfoScreen(
  onBackClicked: () -> Unit,
  viewModel: SystemInfoViewModel = hiltViewModel(),
  modelManagerViewModel: ModelManagerViewModel,
) {
  val snapshot by viewModel.snapshot.collectAsState()
  val collecting by viewModel.collecting.collectAsState()

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text("System Info") },
        navigationIcon = {
          IconButton(onClick = onBackClicked) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          if (collecting) {
            CircularProgressIndicator(
              strokeWidth = 2.dp,
              modifier = Modifier.padding(horizontal = 12.dp).size(18.dp),
            )
          } else {
            IconButton(onClick = { viewModel.collect() }) {
              Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
            }
          }
        },
      )
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      snapshot?.let { snap ->
        Text(
          text =
            "Collected: " + DateFormat.getDateTimeInstance().format(Date(snap.collectedAtMillis)),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DeviceSection(snap)
        BuildSection(viewModel.buildInfo)
        CpuMemorySection(snap)
        GpuSection(snap.gpu)
        VulkanSection(snap.vulkan)
        AiRuntimeSection(snap, viewModel, modelManagerViewModel)
        NativeLibrariesSection(snap)
      } ?: run {
        Text("Collecting device information…", style = MaterialTheme.typography.bodyMedium)
      }
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      title,
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
    )
    content()
  }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
  Row(modifier = Modifier.fillMaxWidth()) {
    Text(
      label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.fillMaxWidth(0.36f),
    )
    Text(
      value.ifEmpty { "—" },
      style =
        if (monospace) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        else MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun DeviceSection(snapshot: SystemInfoSnapshot) {
  val d = snapshot.device
  Section("Device") {
    InfoRow("Manufacturer", d.manufacturer)
    InfoRow("Brand", d.brand)
    InfoRow("Model", d.model)
    InfoRow("Device", d.device)
    InfoRow("Hardware", d.hardware)
    InfoRow("SoC manufacturer", d.socManufacturer)
    InfoRow("SoC model", d.socModel)
    InfoRow("Probable vendor", vendorLabel(d.probableVendor.name))
    InfoRow("Vendor match", matchLabel(d.vendorMatchStatus))
    InfoRow("Android", "${d.androidRelease} (API ${d.apiLevel})")
    InfoRow("Security patch", d.securityPatch)
  }
}

@Composable
private fun BuildSection(info: BuildInfoSnapshot) {
  Section("Build") {
    InfoRow("App version", "${info.versionName} (${info.versionCode})")
    InfoRow("Git commit", info.gitCommit, monospace = true)
    InfoRow("Package", info.applicationId, monospace = true)
    InfoRow("Build type", info.buildType)
  }
}

@Composable
private fun CpuMemorySection(snapshot: SystemInfoSnapshot) {
  val d = snapshot.device
  Section("CPU / Memory") {
    InfoRow("Logical CPUs", d.cpuCoreCount.toString())
    InfoRow("Total RAM", formatBytes(d.totalRamBytes))
    InfoRow("ABIs", d.supportedAbis.joinToString(", "))
    InfoRow("Kernel", d.kernelVersion, monospace = true)
  }
}

@Composable
private fun GpuSection(gpu: GpuSnapshot) {
  Section("GPU (EGL probe)") {
    when (gpu.status) {
      ProbeStatus.OK -> {
        InfoRow("GL_VENDOR", gpu.glVendor ?: "—")
        InfoRow("GL_RENDERER", gpu.glRenderer ?: "—")
        InfoRow("GL_VERSION", gpu.glVersion ?: "—")
        InfoRow("GLSL version", gpu.glslVersion ?: "—")
      }
      ProbeStatus.UNAVAILABLE -> InfoRow("Status", "unavailable")
      ProbeStatus.ERROR -> {
        InfoRow("Status", "error")
        gpu.errorDetail?.let { InfoRow("Detail", it, monospace = true) }
      }
    }
  }
}

@Composable
private fun VulkanSection(vulkan: VulkanSnapshot) {
  Section("Vulkan") {
    when (vulkan.status) {
      ProbeStatus.OK -> {
        InfoRow("Hardware version", vulkan.hardwareVersionRawHex ?: "—", monospace = true)
        InfoRow("Decoded", vulkan.hardwareVersionDecoded ?: "—")
        InfoRow("Hardware level", vulkan.hardwareLevelRaw ?: "—", monospace = true)
        InfoRow("Level decoded", vulkan.hardwareLevelDecoded ?: "—")
      }
      ProbeStatus.UNAVAILABLE -> InfoRow("Status", "unavailable on this device")
      ProbeStatus.ERROR -> {
        InfoRow("Status", "error")
        vulkan.errorDetail?.let { InfoRow("Detail", it, monospace = true) }
      }
    }
  }
}

@Composable
private fun AiRuntimeSection(
  snapshot: SystemInfoSnapshot,
  viewModel: SystemInfoViewModel,
  modelManagerViewModel: ModelManagerViewModel,
) {
  val npuProbeState by viewModel.npuProbeState.collectAsState()
  val npuCandidates by viewModel.npuCandidates.collectAsState()
  LaunchedEffect(Unit) { viewModel.observeNpuModelAvailability(modelManagerViewModel) }

  Section("AI runtime") {
    Text(
      "Bundled .so files do NOT prove the NPU runtime works on this device.",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (vendorStatus in snapshot.vendorStatuses) {
      val deviceMatched = vendorStatus.deviceMatch == DeviceMatchStatus.YES
      Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          vendorLabel(vendorStatus.vendor.name),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
        )
        InfoRow("Bundled", if (vendorStatus.bundled) "yes" else "no")
        InfoRow("Device match", matchLabel(vendorStatus.deviceMatch))
        InfoRow(
          "Runtime validated",
          if (deviceMatched) {
            npuProbeRuntimeValidatedLabel(npuProbeState.status, npuProbeState.result)
          } else {
            vendorStatus.runtimeValidated
          },
        )
        if (deviceMatched) {
          NpuProbePanel(
            state = npuProbeState,
            candidates = npuCandidates,
            snapshot = snapshot,
            buildInfo = viewModel.buildInfo,
            onSelectModel = { viewModel.selectNpuCandidate(it) },
            onRunProbe = { viewModel.runNpuProbe(modelManagerViewModel) },
          )
        }
      }
    }
  }
}

@Composable
private fun NpuProbePanel(
  state: NpuProbeUiState,
  candidates: List<Model>,
  snapshot: SystemInfoSnapshot,
  buildInfo: BuildInfoSnapshot,
  onSelectModel: (String) -> Unit,
  onRunProbe: () -> Unit,
) {
  if (state.status == NpuProbeStatus.RUNNING) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
      Text("Probing NPU initialization…", style = MaterialTheme.typography.bodySmall)
    }
    return
  }

  if (candidates.isEmpty()) {
    Text(
      "No downloaded NPU-compatible model",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  } else {
    NpuModelSelector(
      selectedModelName = state.selectedModelName,
      candidates = candidates,
      onSelect = onSelectModel,
    )
  }

  val selectedModel = candidates.firstOrNull { it.name == state.selectedModelName }
  if (selectedModel != null) {
    Column(
      modifier = Modifier.padding(top = 4.dp),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      InfoRow("Model", selectedModel.displayName.ifEmpty { selectedModel.name })
      InfoRow("Artifact", selectedModel.downloadFileName, monospace = true)
      InfoRow("Backend", selectedModel.accelerators.joinToString(" / ") { it.label })
      InfoRow("Target SoC", selectedModel.targetSoc ?: "generic")
      InfoRow("Detected SoC", Build.SOC_MODEL.ifEmpty { "—" }, monospace = true)
    }
  }

  var diagnosticsCopied by remember { mutableStateOf(false) }

  Button(onClick = onRunProbe, enabled = selectedModel != null) {
    Text("Probe NPU", style = MaterialTheme.typography.labelLarge)
  }

  val clipboard = LocalClipboardManager.current
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.padding(top = 8.dp),
  ) {
    OutlinedButton(
      onClick = {
        clipboard.setText(
          AnnotatedString(
            composeNpuDiagnostics(
              build = buildInfo,
              device = snapshot.device,
              probeStatus = state.status,
              result = state.result,
            )
          )
        )
        diagnosticsCopied = true
      },
    ) {
      Text("Copy diagnostics", style = MaterialTheme.typography.labelLarge)
    }
  }
  if (diagnosticsCopied) {
    Text(
      "Diagnostics copied to clipboard",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  val result = state.result ?: return
  val precheck = result.precheck ?: return
  Column(
    modifier = Modifier.padding(top = 4.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    InfoRow("Requested backend", "NPU")
    InfoRow("nativeLibraryDir", precheck.nativeLibraryDir, monospace = true)
    InfoRow("Model path", precheck.modelPath, monospace = true)
    InfoRow("Vendor", precheck.vendorLabel)
    if (precheck.socModel.isNotEmpty()) {
      InfoRow("Precheck SoC", precheck.socModel, monospace = true)
    }
    if (precheck.htpGeneration.isNotEmpty()) {
      InfoRow("HTP generation", precheck.htpGeneration)
    }
    if (precheck.vendorDispatchMissingLibs.isNotEmpty()) {
      InfoRow(
        "Missing required libs",
        precheck.vendorDispatchMissingLibs.joinToString("\n"),
        monospace = true,
      )
    }
    if (precheck.vendorDispatchErrors.isNotEmpty()) {
      InfoRow(
        "Vendor errors",
        precheck.vendorDispatchErrors.joinToString("\n"),
        monospace = true,
      )
    }
    InfoRow(
      "Vendor dispatch dir",
      precheck.vendorDispatchDirPath.ifEmpty { "—" },
      monospace = true,
    )
    InfoRow("Vendor dir exists", if (precheck.vendorDispatchDirExists) "yes" else "no")
    InfoRow("Vendor dir .so count", precheck.vendorDispatchVisibleSoCount.toString())
    if (precheck.vendorDispatchVisibleSoNames.isNotEmpty()) {
      InfoRow(
        "Vendor dir .so names",
        precheck.vendorDispatchVisibleSoNames.joinToString("\n"),
        monospace = true,
      )
    }
    InfoRow("Directory exists", if (precheck.directoryExists) "yes" else "no")
    InfoRow("Directory readable", if (precheck.directoryReadable) "yes" else "no")
    InfoRow("Visible .so count", precheck.visibleSoCount.toString())
    if (precheck.visibleSoNames.isNotEmpty()) {
      InfoRow("Visible .so names", precheck.visibleSoNames.joinToString("\n"), monospace = true)
    }
    InfoRow("Total duration", npuProbeFormatDuration(result.totalDurationMs))
    for (stage in result.stageResults) {
      InfoRow("Stage ${stage.stage.name}", npuProbeStageLabel(stage), monospace = !stage.passed)
    }
    val failedStageResult = result.stageResults.lastOrNull { !it.passed }
    if (failedStageResult != null) {
      InfoRow(
        "Raw error",
        listOfNotNull(failedStageResult.exceptionClass, failedStageResult.exceptionMessage)
          .joinToString(": "),
        monospace = true,
      )
    }
  }
}

@Composable
private fun NpuModelSelector(
  selectedModelName: String?,
  candidates: List<Model>,
  onSelect: (String) -> Unit,
) {
  var menuOpen by remember { mutableStateOf(false) }
  val selected = candidates.firstOrNull { it.name == selectedModelName }
  Box {
    OutlinedButton(
      onClick = { menuOpen = true },
      contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
      Text(
        if (selected != null) selected.displayName.ifEmpty { selected.name } else "Select model",
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium,
      )
      Icon(Icons.Rounded.ArrowDropDown, contentDescription = "Select probe model")
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
      for (model in candidates) {
        DropdownMenuItem(
          text = {
            Text(
              model.displayName.ifEmpty { model.name },
              style = MaterialTheme.typography.bodyMedium,
            )
          },
          onClick = {
            onSelect(model.name)
            menuOpen = false
          },
        )
      }
    }
  }
}

@Composable
private fun NativeLibrariesSection(snapshot: SystemInfoSnapshot) {
  val libs = snapshot.nativeLibs
  Section("Bundled native libraries (${libs.totalScanned} .so in package)") {
    libs.errorDetail?.let {
      InfoRow("Error", it, monospace = true)
      return@Section
    }
    InfoRow("Package archives scanned", libs.archivesScanned.toString())
    for (diagnostic in libs.diagnostics) {
      InfoRow("Diagnostic", diagnostic, monospace = true)
    }
    InfoRowGroup("LiteRT dispatch", libs.dispatchLibs.map { it.fileName })
    InfoRowGroup("LiteRT compiler plugins", libs.compilerPluginLibs.map { it.fileName })
    InfoRowGroup("QNN core", libs.qnnCoreLibs.map { it.fileName })
    InfoRowGroup("QNN HTP", libs.qnnHtpLibs.map { it.fileName })
    InfoRowGroup("Other", libs.otherLibs.map { it.fileName })
    InfoRow(
      "HTP generations",
      if (libs.htpGenerations.isEmpty()) "—" else libs.htpGenerations.joinToString(", "),
    )
  }
}

@Composable
private fun InfoRowGroup(label: String, values: List<String>) {
  if (values.isEmpty()) return
  InfoRow(label, values.joinToString("\n"), monospace = true)
}

private fun vendorLabel(raw: String): String =
  when (raw) {
    "MEDIATEK" -> "MediaTek"
    "QUALCOMM" -> "Qualcomm"
    "GOOGLE_TENSOR" -> "Google Tensor"
    else -> raw.lowercase().replaceFirstChar { it.uppercase() }
  }

private fun matchLabel(status: DeviceMatchStatus): String =
  when (status) {
    DeviceMatchStatus.YES -> "yes"
    DeviceMatchStatus.NO -> "no"
    DeviceMatchStatus.UNKNOWN -> "unknown"
  }

private fun formatBytes(bytes: Long): String =
  when {
    bytes < 0 -> "—"
    bytes >= 1L shl 30 -> "%.1f GiB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes.toDouble() / (1L shl 20))
    else -> "$bytes B"
  }
