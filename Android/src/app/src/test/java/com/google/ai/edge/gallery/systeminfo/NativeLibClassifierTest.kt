package com.google.ai.edge.gallery.systeminfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeLibClassifierTest {

  private val DISPATCH = "LITE_RT_DISPATCH"
  private val PLUGIN = "LITE_RT_COMPILER_PLUGIN"
  private val HTP = "QNN_HTP"
  private val CORE = "QNN_CORE"
  private val OTHER = "OTHER"

  private fun enum(name: String): NativeLibKind = NativeLibKind.valueOf(name)

  @Test
  fun liteRtVendorLibs_classifiedWithVendorLabel() {
    val mtkDispatch = NativeLibClassifier.classify("libLiteRtDispatch_MediaTek.so")
    assertEquals(enum(DISPATCH), mtkDispatch.kind)
    assertEquals("MediaTek", mtkDispatch.vendorLabel)
    assertNull(mtkDispatch.htpGeneration)

    val qcDispatch = NativeLibClassifier.classify("libLiteRtDispatch_Qualcomm.so")
    assertEquals(enum(DISPATCH), qcDispatch.kind)
    assertEquals("Qualcomm", qcDispatch.vendorLabel)

    val gtDispatch = NativeLibClassifier.classify("libLiteRtDispatch_GoogleTensor.so")
    assertEquals(enum(DISPATCH), gtDispatch.kind)
    assertEquals("GoogleTensor", gtDispatch.vendorLabel)

    val plugin = NativeLibClassifier.classify("libLiteRtCompilerPlugin_Qualcomm.so")
    assertEquals(enum(PLUGIN), plugin.kind)
    assertEquals("Qualcomm", plugin.vendorLabel)
  }

  @Test
  fun qnnLibs_classifiedAndGenerationsExtracted() {
    assertEquals(enum(HTP), NativeLibClassifier.classify("libQnnHtp.so").kind)
    assertNull(NativeLibClassifier.classify("libQnnHtp.so").htpGeneration)

    val skel = NativeLibClassifier.classify("libQnnHtpV73Skel.so")
    assertEquals(enum(HTP), skel.kind)
    assertEquals("V73", skel.htpGeneration)

    val stub = NativeLibClassifier.classify("libQnnHtpV79Stub.so")
    assertEquals(enum(HTP), stub.kind)
    assertEquals("V79", stub.htpGeneration)

    val v69 = NativeLibClassifier.extractHtpGeneration("libQnnHtpV69Skel.so")
    assertEquals("V69", v69)
    val v81 = NativeLibClassifier.extractHtpGeneration("libQnnHtpV81Stub.so")
    assertEquals("V81", v81)

    assertEquals(enum(CORE), NativeLibClassifier.classify("libQnnSystem.so").kind)
    assertNull(NativeLibClassifier.classify("libQnnSystem.so").htpGeneration)
  }

  @Test
  fun otherLibs_fallThroughToOther() {
    assertEquals(enum(OTHER), NativeLibClassifier.classify("libandroidx.graphics.path.so").kind)
    assertNull(NativeLibClassifier.classify("libandroidx.graphics.path.so").vendorLabel)
    assertNull(NativeLibClassifier.extractHtpGeneration("libQnnSystem.so"))
  }

  @Test
  fun vendorLabelToRuntimeVendor() {
    assertEquals(RuntimeVendor.MEDIATEK, NativeLibClassifier.vendorLabelToRuntimeVendor("MediaTek"))
    assertEquals(RuntimeVendor.QUALCOMM, NativeLibClassifier.vendorLabelToRuntimeVendor("qualcomm"))
    assertEquals(RuntimeVendor.GOOGLE_TENSOR, NativeLibClassifier.vendorLabelToRuntimeVendor("GoogleTensor"))
    assertEquals(RuntimeVendor.OTHER, NativeLibClassifier.vendorLabelToRuntimeVendor("Acme"))
  }

  @Test
  fun apkLibEntries_accepted() {
    assertEquals(
      "libLiteRtDispatch_MediaTek.so",
      NativeLibClassifier.parseApkLibEntry("lib/arm64-v8a/libLiteRtDispatch_MediaTek.so"),
    )
    assertEquals(
      "libQnnHtpV79Skel.so",
      NativeLibClassifier.parseApkLibEntry("lib/arm64-v8a/libQnnHtpV79Skel.so"),
    )
    assertEquals(
      "libLiteRtDispatch_Qualcomm.so",
      NativeLibClassifier.parseApkLibEntry("lib/armeabi-v7a/libLiteRtDispatch_Qualcomm.so"),
    )
  }

  @Test
  fun apkNonLibEntries_ignored() {
    assertNull(NativeLibClassifier.parseApkLibEntry("assets/foo.so"))
    assertNull(NativeLibClassifier.parseApkLibEntry("META-INF/foo"))
    assertNull(NativeLibClassifier.parseApkLibEntry("lib/arm64-v8a/not-a-library.txt"))
    assertNull(NativeLibClassifier.parseApkLibEntry("lib/arm64-v8a/not-a-library.so.txt"))
    assertNull(NativeLibClassifier.parseApkLibEntry("lib/libLiteRtDispatch_MediaTek.so"))
    assertNull(NativeLibClassifier.parseApkLibEntry("lib/unknown-abi/libFoo.so"))
  }

  @Test
  fun apkEntryParsing_thenClassification_endToEnd() {
    val name = NativeLibClassifier.parseApkLibEntry("lib/arm64-v8a/libLiteRtDispatch_MediaTek.so")
    val lib = NativeLibClassifier.classify(name!!)
    assertEquals("LITE_RT_DISPATCH", lib.kind.name)
    assertEquals("MediaTek", lib.vendorLabel)

    val gtName = NativeLibClassifier.parseApkLibEntry("lib/arm64-v8a/libLiteRtDispatch_GoogleTensor.so")
    val gtLib = NativeLibClassifier.classify(gtName!!)
    assertEquals("GoogleTensor", gtLib.vendorLabel)

    val qcPluginName = NativeLibClassifier.parseApkLibEntry("lib/arm64-v8a/libLiteRtCompilerPlugin_Qualcomm.so")
    assertEquals("LITE_RT_COMPILER_PLUGIN", NativeLibClassifier.classify(qcPluginName!!).kind.name)

    val htpName = NativeLibClassifier.parseApkLibEntry("lib/arm64-v8a/libQnnHtpV79Skel.so")
    assertEquals("V79", NativeLibClassifier.classify(htpName!!).htpGeneration)
  }
}
