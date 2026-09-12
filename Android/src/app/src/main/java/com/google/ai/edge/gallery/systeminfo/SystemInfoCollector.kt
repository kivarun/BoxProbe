package com.google.ai.edge.gallery.systeminfo

import android.app.ActivityManager
import android.content.Context
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * Collects an immutable [SystemInfoSnapshot] from public Android APIs and a filesystem
 * scan of the app's bundled native libraries. No vendor runtime is loaded or validated
 * here.
 */
object SystemInfoCollector {

  private const val FEATURE_VULKAN_VERSION = "android.hardware.vulkan.version"
  private const val FEATURE_VULKAN_LEVEL = "android.hardware.vulkan.level"
  private const val VULKAN_COMPUTE_MAGIC = 20150324

  fun collect(context: Context): SystemInfoSnapshot {
    val device = collectDevice(context)
    val gpu = GpuEglProbe.probe()
    val vulkan = collectVulkan(context)
    val nativeLibs = collectNativeLibraries(context)

    val vendorStatuses = listOf(
      RuntimeVendor.MEDIATEK,
      RuntimeVendor.QUALCOMM,
      RuntimeVendor.GOOGLE_TENSOR,
    ).map { vendor ->
      RuntimeVendorStatus(
        vendor = vendor,
        bundled = vendorIsBundled(vendor, nativeLibs),
        deviceMatch = SocVendorDetector.deviceMatch(
          vendor = vendor,
          socManufacturer = device.socManufacturer,
          socModel = device.socModel,
        ),
        runtimeValidated = "Not probed",
      )
    }

    return SystemInfoSnapshot(
      device = device,
      gpu = gpu,
      vulkan = vulkan,
      nativeLibs = nativeLibs,
      vendorStatuses = vendorStatuses,
      collectedAtMillis = System.currentTimeMillis(),
    )
  }

  fun collectDevice(context: Context): DeviceSnapshot {
    val socManufacturer = safeBuildString { Build.SOC_MANUFACTURER }
    val socModel = safeBuildString { Build.SOC_MODEL }
    val probableVendor = SocVendorDetector.detect(socManufacturer, socModel)

    val totalRam = runCatching {
      val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      val memoryInfo = ActivityManager.MemoryInfo()
      am.getMemoryInfo(memoryInfo)
      memoryInfo.totalMem
    }.getOrDefault(-1L)

    return DeviceSnapshot(
      manufacturer = Build.MANUFACTURER,
      brand = Build.BRAND,
      model = Build.MODEL,
      device = Build.DEVICE,
      hardware = Build.HARDWARE,
      socManufacturer = socManufacturer,
      socModel = socModel,
      androidRelease = Build.VERSION.RELEASE,
      apiLevel = Build.VERSION.SDK_INT,
      securityPatch = Build.VERSION.SECURITY_PATCH,
      supportedAbis = Build.SUPPORTED_ABIS.toList(),
      kernelVersion = System.getProperty("os.version") ?: "",
      cpuCoreCount = Runtime.getRuntime().availableProcessors(),
      totalRamBytes = totalRam,
      probableVendor = probableVendor,
      vendorMatchStatus =
        if (probableVendor == SocVendor.UNKNOWN) DeviceMatchStatus.UNKNOWN
        else DeviceMatchStatus.YES,
    )
  }

  fun collectVulkan(context: Context): VulkanSnapshot {
    val pm = context.packageManager
    return try {
      val versionFeature =
        pm.getSystemAvailableFeatures()?.firstOrNull { it.name == FEATURE_VULKAN_VERSION }
      val levelFeature =
        pm.getSystemAvailableFeatures()?.firstOrNull { it.name == FEATURE_VULKAN_LEVEL }

      if (versionFeature == null && levelFeature == null) {
        return VulkanSnapshot.UNAVAILABLE
      }

      val versionValue: Long? = versionFeature?.let { featureValue(it) }
      val (rawHex, decoded) = versionValue?.let { Pair(toHex(it), decodeVulkanVersion(it)) }
        ?: Pair(null, null)

      val levelValue: Long? = levelFeature?.let { featureValue(it) }
      val levelRaw = levelValue?.toString()
      val levelDecoded = levelValue?.let { decodeVulkanLevel(it) }

      VulkanSnapshot(
        status = ProbeStatus.OK,
        hardwareVersionRawHex = rawHex,
        hardwareVersionDecoded = decoded,
        hardwareLevelRaw = levelRaw,
        hardwareLevelDecoded = levelDecoded,
        errorDetail = null,
      )
    } catch (t: Throwable) {
      VulkanSnapshot(
        status = ProbeStatus.ERROR,
        hardwareVersionRawHex = null,
        hardwareVersionDecoded = null,
        hardwareLevelRaw = null,
        hardwareLevelDecoded = null,
        errorDetail = "Exception: ${t.javaClass.simpleName}: ${t.message}",
      )
    }
  }

  fun collectNativeLibraries(context: Context): NativeLibrariesSnapshot {
    val dir = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull()
    if (dir.isNullOrEmpty()) {
      return NativeLibrariesSnapshot.error("nativeLibraryDir is unavailable")
    }
    val files = runCatching { File(dir).listFiles { f -> f.isFile && f.name.endsWith(".so") } }
      .getOrNull()
    if (files == null) {
      return NativeLibrariesSnapshot.error("Failed to list $dir")
    }

    val classified = files.map { NativeLibClassifier.classify(it.name) }.sortedBy { it.fileName }
    val dispatch = classified.filter { it.kind == NativeLibKind.LITE_RT_DISPATCH }
    val compiler = classified.filter { it.kind == NativeLibKind.LITE_RT_COMPILER_PLUGIN }
    val qnnCore = classified.filter { it.kind == NativeLibKind.QNN_CORE }
    val qnnHtp = classified.filter { it.kind == NativeLibKind.QNN_HTP }
    val other = classified.filter { it.kind == NativeLibKind.OTHER }

    val htpGenerations = qnnHtp.mapNotNull { it.htpGeneration }.distinct().sorted()
    val liteRtVendorLabels =
      (dispatch.mapNotNull { it.vendorLabel } + compiler.mapNotNull { it.vendorLabel })
        .distinct().sorted()

    return NativeLibrariesSnapshot(
      dispatchLibs = dispatch,
      compilerPluginLibs = compiler,
      qnnCoreLibs = qnnCore,
      qnnHtpLibs = qnnHtp,
      otherLibs = other,
      htpGenerations = htpGenerations,
      liteRtVendorLabels = liteRtVendorLabels,
      totalScanned = files.size,
      errorDetail = null,
    )
  }

  /**
   * The vulkan.version / vulkan.level feature payload is carried in
   * [FeatureInfo.reqGlEsVersion]; [FeatureInfo.version] is used by some vendors as well.
   */
  private fun featureValue(feature: FeatureInfo): Long? {
    if (feature.reqGlEsVersion != 0) {
      return feature.reqGlEsVersion.toLong() and 0xFFFFFFFFL
    }
    if (feature.version > 0) return feature.version.toLong()
    return null
  }

  private fun decodeVulkanVersion(encoded: Long): String {
    val major = (encoded shr 22) and 0x3FFL
    val minor = (encoded shr 12) and 0xFFFL
    val patch = encoded and 0xFFFL
    val variant = (encoded shr 29) and 0x7L
    return buildString {
      append("variant ").append(variant)
      append(", Vulkan ").append(major).append('.').append(minor).append('.').append(patch)
    }
  }

  private fun decodeVulkanLevel(level: Long): String =
    when (level) {
      0L -> "0 (no Vulkan requirements)"
      1L -> "1 (Vulkan 1.x baseline)"
      VULKAN_COMPUTE_MAGIC.toLong() -> "20150324 (compute)"
      else -> "raw $level"
    }

  private fun toHex(value: Long): String = "0x%08X".format(value)

  private fun vendorIsBundled(vendor: RuntimeVendor, libs: NativeLibrariesSnapshot): Boolean =
    (libs.dispatchLibs + libs.compilerPluginLibs).any {
      it.vendorLabel != null &&
        SocVendorDetector.toSocVendor(
          NativeLibClassifier.vendorLabelToRuntimeVendor(it.vendorLabel!!),
        ) == SocVendorDetector.toSocVendor(vendor)
    }

  private fun safeBuildString(block: () -> String): String = runCatching(block).getOrDefault("")
}
