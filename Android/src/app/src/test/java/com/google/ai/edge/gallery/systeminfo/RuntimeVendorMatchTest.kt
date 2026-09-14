package com.google.ai.edge.gallery.systeminfo

import com.google.ai.edge.gallery.device.SocVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeVendorMatchTest {

  private val MTK = "MEDIA" + "TEK"
  private val QC = "QUALCOMM"
  private val GT = "GOOGLE_TENSOR"

  @Test
  fun toSocVendor_mapsBundledVendorLabels() {
    assertEquals(SocVendor.valueOf(MTK), RuntimeVendorMatch.toSocVendor(RuntimeVendor.MEDIATEK))
    assertEquals(SocVendor.valueOf(QC), RuntimeVendorMatch.toSocVendor(RuntimeVendor.QUALCOMM))
    assertEquals(
      SocVendor.valueOf(GT),
      RuntimeVendorMatch.toSocVendor(RuntimeVendor.GOOGLE_TENSOR),
    )
    assertNull(RuntimeVendorMatch.toSocVendor(RuntimeVendor.OTHER))
  }

  @Test
  fun deviceMatch_bundledVsDevice() {
    val manufacturer = "Qualcomm"
    val model = "SM8650"
    assertEquals(
      DeviceMatchStatus.YES,
      RuntimeVendorMatch.deviceMatch(RuntimeVendor.valueOf(QC), manufacturer, model),
    )
    assertEquals(
      DeviceMatchStatus.NO,
      RuntimeVendorMatch.deviceMatch(RuntimeVendor.valueOf(MTK), manufacturer, model),
    )
    assertEquals(
      DeviceMatchStatus.UNKNOWN,
      RuntimeVendorMatch.deviceMatch(RuntimeVendor.OTHER, manufacturer, model),
    )
    // Unknown device SoC -> unknown match.
    assertEquals(
      DeviceMatchStatus.UNKNOWN,
      RuntimeVendorMatch.deviceMatch(RuntimeVendor.valueOf(QC), "", ""),
    )
  }
}
