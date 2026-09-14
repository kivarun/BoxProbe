package com.google.ai.edge.gallery.device

import org.junit.Assert.assertEquals
import org.junit.Test

class SocVendorDetectorTest {

  private val MTK = "MEDIA" + "TEK"
  private val QC = "QUALCOMM"
  private val GT = "GOOGLE_TENSOR"
  private val UN = "UNKNOWN"

  private fun enum(name: String): SocVendor = SocVendor.valueOf(name)

  @Test
  fun socManufacturer_isPrimarySignal() {
    assertEquals(enum(MTK), SocVendorDetector.detect("MediaTek", "mt6789"))
    assertEquals(enum(MTK), SocVendorDetector.detect("  mediatek ", "whatever"))
    assertEquals(enum(QC), SocVendorDetector.detect("Qualcomm", "sm8650"))
    assertEquals(enum(QC), SocVendorDetector.detect("qcom", "sm7450"))
    assertEquals(enum(GT), SocVendorDetector.detect("Google", "tensor g3"))
  }

  @Test
  fun socModel_fallbackPatterns() {
    // Unknown manufacturer: fall back to SOC_MODEL.
    assertEquals(enum(GT), SocVendorDetector.detect("", "tensor G3"))
    assertEquals(enum(MTK), SocVendorDetector.detect("", "MT8188"))
    assertEquals(enum(MTK), SocVendorDetector.detect("", "mt6789"))
    assertEquals(enum(MTK), SocVendorDetector.detect("", "Dimensity 9200"))
    assertEquals(enum(QC), SocVendorDetector.detect("", "SM8650"))
    assertEquals(enum(QC), SocVendorDetector.detect("", "sdm845"))
    assertEquals(enum(QC), SocVendorDetector.detect("", "QCS6490"))
    assertEquals(enum(QC), SocVendorDetector.detect("", "Snapdragon 8 Gen 3"))
  }

  @Test
  fun unknown_isConservative() {
    assertEquals(enum(UN), SocVendorDetector.detect("", ""))
    assertEquals(enum(UN), SocVendorDetector.detect("Acme Corp", "ACME-1"))
    // Loose prefix must not match.
    assertEquals(enum(UN), SocVendorDetector.detect("", "exynos2400"))
  }
}
