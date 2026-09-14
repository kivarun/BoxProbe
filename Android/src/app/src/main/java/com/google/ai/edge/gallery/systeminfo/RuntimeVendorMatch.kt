package com.google.ai.edge.gallery.systeminfo

import com.google.ai.edge.gallery.device.SocVendor
import com.google.ai.edge.gallery.device.SocVendorDetector

/**
 * Diagnostics-side mapping between the bundled runtime inventory ([RuntimeVendor]) and the
 * neutral SoC classification ([SocVendor]), plus the bundled-vs-device match decision.
 *
 * The neutral SoC detection itself lives in the device layer; nothing here is needed by
 * the production runtime.
 */
object RuntimeVendorMatch {

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
    val detected = SocVendorDetector.detect(socManufacturer, socModel)
    if (detected == SocVendor.UNKNOWN) return DeviceMatchStatus.UNKNOWN
    return if (detected == soc) DeviceMatchStatus.YES else DeviceMatchStatus.NO
  }
}
