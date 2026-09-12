package com.google.ai.edge.gallery.systeminfo

/**
 * Classifies the probable SoC vendor of a device.
 *
 * The primary signal is `Build.SOC_MANUFACTURER`; `Build.SOC_MODEL` is used as a careful
 * fallback. The presence of bundled QNN/LiteRT libraries in the APK must never influence
 * this result.
 */
object SocVendorDetector {

  fun detect(socManufacturer: String, socModel: String): SocVendor {
    val manufacturer = socManufacturer.trim().lowercase()
    val model = socModel.trim().lowercase()

    // Primary signal: SOC_MANUFACTURER.
    when {
      manufacturer.contains("mediatek") -> return SocVendor.MEDIATEK
      manufacturer.contains("qualcomm") || manufacturer == "qcom" -> return SocVendor.QUALCOMM
      manufacturer.contains("google") -> return SocVendor.GOOGLE_TENSOR
    }

    // Fallback: conservative patterns on SOC_MODEL only.
    if (model.isNotEmpty()) {
      if (model.contains("tensor")) return SocVendor.GOOGLE_TENSOR
      if (
        model.startsWith("mt") ||
          model.contains("dimensity") ||
          model.contains("helio") ||
          model.contains("kompanio")
      ) {
        return SocVendor.MEDIATEK
      }
      if (
        model.startsWith("sm") ||
          model.startsWith("qcs") ||
          model.startsWith("sdm") ||
          model.startsWith("apq") ||
          model.startsWith("msm") ||
          model.contains("snapdragon")
      ) {
        return SocVendor.QUALCOMM
      }
    }

    return SocVendor.UNKNOWN
  }

  /**
   * Maps a [RuntimeVendor] library label onto its [SocVendor] equivalent.
   */
  fun toSocVendor(vendor: RuntimeVendor): SocVendor? =
    when (vendor) {
      RuntimeVendor.MEDIATEK -> SocVendor.MEDIATEK
      RuntimeVendor.QUALCOMM -> SocVendor.QUALCOMM
      RuntimeVendor.GOOGLE_TENSOR -> SocVendor.GOOGLE_TENSOR
      RuntimeVendor.OTHER -> null
    }
  /**
   * Whether the bundled libraries labelled [vendor] plausibly match a device with the given
   * SoC identity. Only SoC manufacturer/model are consulted.
   */
  fun deviceMatch(
    vendor: RuntimeVendor,
    socManufacturer: String,
    socModel: String,
  ): DeviceMatchStatus {
    val soc = toSocVendor(vendor) ?: return DeviceMatchStatus.UNKNOWN
    val detected = detect(socManufacturer, socModel)
    if (detected == SocVendor.UNKNOWN) return DeviceMatchStatus.UNKNOWN
    return if (detected == soc) DeviceMatchStatus.YES else DeviceMatchStatus.NO
  }
}
