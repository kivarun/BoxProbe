package com.google.ai.edge.gallery.systeminfo

import com.google.ai.edge.gallery.device.SocVendor

/** Whether a probe returned usable data or a structured error. */
enum class ProbeStatus {
  OK,
  UNAVAILABLE,
  ERROR,
}

/** Whether a bundled runtime component matches the detected device. */
enum class DeviceMatchStatus {
  YES,
  NO,
  UNKNOWN,
}

/** Classification of a bundled native library file by file name. */
enum class NativeLibKind {
  LITE_RT_DISPATCH,
  LITE_RT_COMPILER_PLUGIN,
  QNN_HTP,
  QNN_CORE,
  OTHER,
}

/** Classification of a vendor runtime component. */
enum class RuntimeVendor {
  MEDIATEK,
  QUALCOMM,
  GOOGLE_TENSOR,
  OTHER,
}

/**
 * An immutable picture of the device, collected from public Android APIs only.
 */
data class DeviceSnapshot(
  val manufacturer: String,
  val brand: String,
  val model: String,
  val device: String,
  val hardware: String,
  val socManufacturer: String,
  val socModel: String,
  val androidRelease: String,
  val apiLevel: Int,
  val securityPatch: String,
  val supportedAbis: List<String>,
  val kernelVersion: String,
  val cpuCoreCount: Int,
  val totalRamBytes: Long,
  val probableVendor: SocVendor,
  val vendorMatchStatus: DeviceMatchStatus,
)

/** GPU info from a headless EGL/GLES context. */
data class GpuSnapshot(
  val status: ProbeStatus,
  val glVendor: String?,
  val glRenderer: String?,
  val glVersion: String?,
  val glslVersion: String?,
  val errorDetail: String?,
) {
  companion object {
    val UNAVAILABLE = GpuSnapshot(
      status = ProbeStatus.UNAVAILABLE,
      glVendor = null,
      glRenderer = null,
      glVersion = null,
      glslVersion = null,
      errorDetail = null,
    )

    fun error(detail: String) = GpuSnapshot(
      status = ProbeStatus.ERROR,
      glVendor = null,
      glRenderer = null,
      glVersion = null,
      glslVersion = null,
      errorDetail = detail,
    )
  }
}

/** Vulkan info exposed via PackageManager system features. */
data class VulkanSnapshot(
  val status: ProbeStatus,
  /** Raw feature value (e.g. 0x00403000) as hex, or null. */
  val hardwareVersionRawHex: String?,
  /** Decoded variant/major/minor/patch form, or null. */
  val hardwareVersionDecoded: String?,
  /** Raw hardware level feature value as string, or null. */
  val hardwareLevelRaw: String?,
  /** Human readable level (e.g. "Vulkan 1.x baseline", "compute"), or null. */
  val hardwareLevelDecoded: String?,
  val errorDetail: String?,
) {
  companion object {
    val UNAVAILABLE = VulkanSnapshot(
      status = ProbeStatus.UNAVAILABLE,
      hardwareVersionRawHex = null,
      hardwareVersionDecoded = null,
      hardwareLevelRaw = null,
      hardwareLevelDecoded = null,
      errorDetail = null,
    )
  }
}

/** A single bundled native library with its classification. */
data class BundledNativeLib(
  val fileName: String,
  val kind: NativeLibKind,
  /** Vendor suffix for LiteRT dispatch/compiler plugin libraries, or null. */
  val vendorLabel: String?,
  /** HTP generation (e.g. "V73") for QNN HTP libraries, or null. */
  val htpGeneration: String?,
)

/** Aggregate status for one AI runtime vendor. */
data class RuntimeVendorStatus(
  val vendor: RuntimeVendor,
  /** Whether the app bundles at least one library for this vendor. */
  val bundled: Boolean,
  /** Whether the bundled libraries appear to match this device's SoC. */
  val deviceMatch: DeviceMatchStatus,
  /** Whether the runtime has been validated end to end on this device. */
  val runtimeValidated: String,
)

/** Complete inventory of the bundled LiteRT/QNN native libraries. */
data class NativeLibrariesSnapshot(
  val dispatchLibs: List<BundledNativeLib>,
  val compilerPluginLibs: List<BundledNativeLib>,
  val qnnCoreLibs: List<BundledNativeLib>,
  val qnnHtpLibs: List<BundledNativeLib>,
  val otherLibs: List<BundledNativeLib>,
  /** HTP generations available in the bundle, e.g. ["V69", "V73", "V75", "V79", "V81"]. */
  val htpGenerations: List<String>,
  /** LiteRT vendor labels seen in dispatch libraries, e.g. ["MediaTek", "Qualcomm", "GoogleTensor"]. */
  val liteRtVendorLabels: List<String>,
  /** Number of unique .so library names found in package archives. */
  val totalScanned: Int,
  /** Number of package archives (base + splits) scanned. */
  val archivesScanned: Int,
  /** Non-fatal warnings collected while reading archives. */
  val diagnostics: List<String>,
  val errorDetail: String?,
) {
  companion object {
    fun error(detail: String) = NativeLibrariesSnapshot(
      dispatchLibs = emptyList(),
      compilerPluginLibs = emptyList(),
      qnnCoreLibs = emptyList(),
      qnnHtpLibs = emptyList(),
      otherLibs = emptyList(),
      htpGenerations = emptyList(),
      liteRtVendorLabels = emptyList(),
      totalScanned = 0,
      archivesScanned = 0,
      diagnostics = emptyList(),
      errorDetail = detail,
    )
  }
}

/** Top level snapshot shown by the System Info screen. */
data class SystemInfoSnapshot(
  val device: DeviceSnapshot,
  val gpu: GpuSnapshot,
  val vulkan: VulkanSnapshot,
  val nativeLibs: NativeLibrariesSnapshot,
  /** Vendor statuses for the AI runtime inventory section. */
  val vendorStatuses: List<RuntimeVendorStatus>,
  val collectedAtMillis: Long,
)
