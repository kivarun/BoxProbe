package com.google.ai.edge.gallery.systeminfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuVendorDispatchTest {

  private val nativeLibraryDir = "/data/app/com.kivarun.boxprobe/lib/arm64"

  private fun expectedTarget(name: String) = "$nativeLibraryDir/$name"

  private val mediatekRequired = listOf(
    "libLiteRtDispatch_MediaTek.so",
    "libLiteRtCompilerPlugin_MediaTek.so",
  )

  @Test
  fun mediatekVendor_selectionAndRequiredNames() {
    assertEquals(NpuDispatchVendor.MEDIATEK, npuDispatchVendorForDevice(SocVendor.MEDIATEK))
    assertEquals("mediatek", NpuDispatchVendor.MEDIATEK.dirName)
    assertEquals(mediatekRequired, npuRequiredLibNames(NpuDispatchVendor.MEDIATEK))
  }

  @Test
  fun nonMediaTekVendors_notImplementedYet() {
    assertTrue(npuRequiredLibNames(NpuDispatchVendor.QUALCOMM).isEmpty())
    assertTrue(npuRequiredLibNames(NpuDispatchVendor.GOOGLE_TENSOR).isEmpty())
    assertNull(npuDispatchVendorForDevice(SocVendor.UNKNOWN))
  }

  @Test
  fun missingRequiredLibrary_isDetected() {
    val available = listOf(
      "libLiteRtDispatch_MediaTek.so",
      "libLiteRtDispatch_GoogleTensor.so",
      "libLiteRtDispatch_Qualcomm.so",
    )
    assertEquals(
      listOf("libLiteRtCompilerPlugin_MediaTek.so"),
      missingVendorDispatchLibs(mediatekRequired, available),
    )
    assertEquals(emptyList<String>(), missingVendorDispatchLibs(mediatekRequired, mediatekRequired))
  }

  @Test
  fun plan_foreignVendorEntries_removed() {
    val plan =
      planVendorDirSync(
        requiredLibNames = mediatekRequired,
        existingEntries =
          mapOf(
            "libLiteRtDispatch_GoogleTensor.so" to expectedTarget("libLiteRtDispatch_GoogleTensor.so"),
            "libLiteRtDispatch_Qualcomm.so" to expectedTarget("libLiteRtDispatch_Qualcomm.so"),
          ),
        sourceDirPath = nativeLibraryDir,
      )
    val removed = plan.filter { it.kind == VendorDispatchSyncKind.REMOVE }.map { it.entryName }
    assertEquals(
      setOf("libLiteRtDispatch_GoogleTensor.so", "libLiteRtDispatch_Qualcomm.so"),
      removed.toSet(),
    )
    // Required libs are still created.
    assertEquals(
      setOf(mediatekRequired[0], mediatekRequired[1]),
      plan.filter { it.kind == VendorDispatchSyncKind.CREATE }.map { it.entryName }.toSet(),
    )
  }

  @Test
  fun plan_healthySymlinks_kept() {
    val plan =
      planVendorDirSync(
        requiredLibNames = mediatekRequired,
        existingEntries =
          mapOf(
            mediatekRequired[0] to expectedTarget(mediatekRequired[0]),
            mediatekRequired[1] to expectedTarget(mediatekRequired[1]),
          ),
        sourceDirPath = nativeLibraryDir,
      )
    assertTrue(plan.all { it.kind == VendorDispatchSyncKind.KEEP })
  }

  @Test
  fun plan_staleOrBrokenSymlink_repaired() {
    val plan =
      planVendorDirSync(
        requiredLibNames = mediatekRequired,
        existingEntries =
          mapOf(
            // Wrong target.
            mediatekRequired[0] to expectedTarget("libLiteRtDispatch_GoogleTensor.so"),
            // Regular file / broken symlink.
            mediatekRequired[1] to null,
          ),
        sourceDirPath = nativeLibraryDir,
      )
    val repaired = plan.filter { it.kind == VendorDispatchSyncKind.REPAIR }.map { it.entryName }
    assertEquals(setOf(mediatekRequired[0], mediatekRequired[1]), repaired.toSet())
  }

  @Test
  fun plan_repeatedExecution_isIdempotent() {
    val first =
      planVendorDirSync(
        requiredLibNames = mediatekRequired,
        existingEntries =
          mapOf(
            "libLiteRtDispatch_GoogleTensor.so" to expectedTarget("libLiteRtDispatch_GoogleTensor.so"),
          ),
        sourceDirPath = nativeLibraryDir,
      )
    // Simulate executing the plan: foreign entry removed, required entries created.
    val afterFirst =
      mapOf(
        mediatekRequired[0] to expectedTarget(mediatekRequired[0]),
        mediatekRequired[1] to expectedTarget(mediatekRequired[1]),
      )
    val second =
      planVendorDirSync(
        requiredLibNames = mediatekRequired,
        existingEntries = afterFirst,
        sourceDirPath = nativeLibraryDir,
      )
    assertTrue(second.all { it.kind == VendorDispatchSyncKind.KEEP })
    assertTrue(second.none { it.kind == VendorDispatchSyncKind.REMOVE })
    assertFalse(first == second)
  }
}
