package com.google.ai.edge.gallery.ui.systeminfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.systeminfo.DeviceMatchStatus
import com.google.ai.edge.gallery.systeminfo.GpuSnapshot
import com.google.ai.edge.gallery.systeminfo.ProbeStatus
import com.google.ai.edge.gallery.systeminfo.RuntimeVendor
import com.google.ai.edge.gallery.systeminfo.SystemInfoSnapshot
import com.google.ai.edge.gallery.systeminfo.VulkanSnapshot
import java.text.DateFormat
import java.util.Date

/**
 * Technical, compact view of the device and the bundled AI runtime.
 *
 * The bundled-library inventory explicitly distinguishes "bundled in APK" from
 * "matches this device" from "validated at runtime". In this increment the runtime
 * validation column is always "Not probed".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemInfoScreen(
  onBackClicked: () -> Unit,
  viewModel: SystemInfoViewModel = hiltViewModel(),
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
        CpuMemorySection(snap)
        GpuSection(snap.gpu)
        VulkanSection(snap.vulkan)
        AiRuntimeSection(snap)
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
private fun AiRuntimeSection(snapshot: SystemInfoSnapshot) {
  Section("AI runtime") {
    Text(
      "Bundled .so files do NOT prove the NPU runtime works on this device.",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (vendorStatus in snapshot.vendorStatuses) {
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
        InfoRow("Runtime validated", vendorStatus.runtimeValidated)
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
