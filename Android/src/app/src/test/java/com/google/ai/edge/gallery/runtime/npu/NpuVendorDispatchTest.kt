package com.google.ai.edge.gallery.runtime.npu

import com.google.ai.edge.gallery.device.SocVendor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuVendorDispatchTest {

  private val nativeLibraryDir = "/data/app/com.kivarun.boxprobe/lib/arm64"

  private fun expectedTarget(name: String) = "$nativeLibraryDir/$name"

  private val vendorDispatchDir =
    "/data/data/com.kivarun.boxprobe/files/runtime-dispatch/mediatek"

  private val qualcommDispatchDir =
    "/data/data/com.kivarun.boxprobe/files/runtime-dispatch/qualcomm"

  private val mediatekRequired = listOf(
    "libLiteRtDispatch_MediaTek.so",
    "libLiteRtCompilerPlugin_MediaTek.so",
  )

  private val qualcommSm8850Required = listOf(
    "libLiteRtDispatch_Qualcomm.so",
    "libLiteRtCompilerPlugin_Qualcomm.so",
    "libQnnSystem.so",
    "libQnnHtp.so",
    "libQnnHtpPrepare.so",
    "libQnnIr.so",
    "libQnnSaver.so",
    "libQnnHtpV81Stub.so",
    "libQnnHtpV81Skel.so",
  )

  private fun preparation(errors: List<String>): VendorDispatchPreparation =
    VendorDispatchPreparation(
      vendor = NpuDispatchVendor.MEDIATEK,
      vendorDispatchDir = File(vendorDispatchDir),
      dirExists = errors.isEmpty(),
      visibleSoNames = mediatekRequired,
      missingRequired = emptyList(),
      symlinkMode = true,
      errors = errors,
    )

  @Test
  fun mediatekVendor_selectionAndRequiredNames() {
    assertEquals(NpuDispatchVendor.MEDIATEK, npuDispatchVendorForDevice(SocVendor.MEDIATEK))
    assertEquals("mediatek", NpuDispatchVendor.MEDIATEK.dirName)
    assertEquals(mediatekRequired, npuRequiredLibNames(NpuDispatchVendor.MEDIATEK))
  }

  @Test
  fun qualcommVendor_selected_googleTensorRemainsUnsupported() {
    assertEquals(NpuDispatchVendor.QUALCOMM, npuDispatchVendorForDevice(SocVendor.QUALCOMM))
    assertEquals("qualcomm", NpuDispatchVendor.QUALCOMM.dirName)
    assertNull(npuDispatchVendorForDevice(SocVendor.GOOGLE_TENSOR))
    assertNull(npuDispatchVendorForDevice(SocVendor.UNKNOWN))
  }

  @Test
  fun sm8850_mapsToHtpV81() {
    assertEquals(NpuHtpGeneration.V81, qualcommHtpGenerationForSoc("SM8850"))
    assertEquals(NpuHtpGeneration.V81, qualcommHtpGenerationForSoc(" sm8850 "))
    assertEquals("V81", qualcommHtpGenerationForSoc("SM8850")!!.label)
  }

  @Test
  fun unknownQualcommSocs_haveNoHtpGeneration() {
    assertNull(qualcommHtpGenerationForSoc("SM8750"))
    assertNull(qualcommHtpGenerationForSoc("SM8650"))
    assertNull(qualcommHtpGenerationForSoc("SM8550"))
    assertNull(qualcommHtpGenerationForSoc(""))
    assertNull(qualcommHtpGenerationForSoc("X Elite"))
  }

  @Test
  fun qualcommRequiredSet_containsV81ArtifactsAndQnnCore() {
    val required = npuRequiredLibNames(NpuDispatchVendor.QUALCOMM, "SM8850")
    assertEquals(qualcommSm8850Required, required)
    assertTrue(required.contains("libQnnHtpV81Stub.so"))
    assertTrue(required.contains("libQnnHtpV81Skel.so"))
  }

  @Test
  fun qualcommRequiredSet_doesNotContainForeignGenerations() {
    val required = npuRequiredLibNames(NpuDispatchVendor.QUALCOMM, "SM8850")
    for (generation in listOf("V69", "V73", "V75", "V79")) {
      assertFalse(
        "SM8850 set must not contain $generation artifacts",
        required.contains("libQnnHtp${generation}Stub.so"),
      )
      assertFalse(required.contains("libQnnHtp${generation}Skel.so"))
    }
  }

  @Test
  fun qualcommUnknownSoc_requiredSetIsEmpty() {
    assertEquals(
      emptyList<String>(),
      npuRequiredLibNames(NpuDispatchVendor.QUALCOMM, "SM8750"),
    )
  }

  @Test
  fun mediatekRequiredSet_isUnchangedByQualcommAddition() {
    assertEquals(mediatekRequired, npuRequiredLibNames(NpuDispatchVendor.MEDIATEK))
    assertEquals(
      mediatekRequired,
      npuRequiredLibNames(NpuDispatchVendor.MEDIATEK, "MT6991"),
    )
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

  @Test
  fun resolveNpuNativeLibraryDir_preparedRuntimeReusedWithoutSecondPrepare() {
    // A healthy preparation is used as-is for Backend.NPU(nativeLibraryDir = …).
    assertEquals(
      vendorDispatchDir,
      resolveNpuNativeLibraryDir(nativeLibraryDir, preparation(errors = emptyList())),
    )
    // A failed preparation falls back to the installer nativeLibraryDir.
    assertEquals(
      nativeLibraryDir,
      resolveNpuNativeLibraryDir(
        nativeLibraryDir,
        preparation(errors = listOf("Failed to remove stale entry: x.so")),
      ),
    )
    // No supported vendor on this device: the installer nativeLibraryDir is used.
    assertEquals(nativeLibraryDir, resolveNpuNativeLibraryDir(nativeLibraryDir, null))
    assertEquals("", resolveNpuNativeLibraryDir(null, null))
  }

  // ---- Qualcomm SM8850 vendor directory sync (existing machinery, new required set). ----

  private fun qualcommPlan(existingEntries: Map<String, String?>): List<VendorDispatchSyncAction> =
    planVendorDirSync(
      requiredLibNames = qualcommSm8850Required,
      existingEntries = existingEntries,
      sourceDirPath = nativeLibraryDir,
    )

  @Test
  fun qualcommPlan_foreignVendorDispatches_removed() {
    val plan =
      qualcommPlan(
        existingEntries =
          mapOf(
            "libLiteRtDispatch_MediaTek.so" to expectedTarget("libLiteRtDispatch_MediaTek.so"),
            "libLiteRtDispatch_GoogleTensor.so" to
              expectedTarget("libLiteRtDispatch_GoogleTensor.so"),
            "libLiteRtCompilerPlugin_MediaTek.so" to
              expectedTarget("libLiteRtCompilerPlugin_MediaTek.so"),
          ),
      )
    val removed = plan.filter { it.kind == VendorDispatchSyncKind.REMOVE }.map { it.entryName }
    assertEquals(
      setOf(
        "libLiteRtDispatch_MediaTek.so",
        "libLiteRtDispatch_GoogleTensor.so",
        "libLiteRtCompilerPlugin_MediaTek.so",
      ),
      removed.toSet(),
    )
  }

  @Test
  fun qualcommPlan_staleOtherGeneration_removed() {
    val plan =
      qualcommPlan(
        existingEntries =
          mapOf(
            "libQnnHtpV79Skel.so" to expectedTarget("libQnnHtpV79Skel.so"),
            "libQnnHtpV79Stub.so" to expectedTarget("libQnnHtpV79Stub.so"),
          ),
      )
    val removed = plan.filter { it.kind == VendorDispatchSyncKind.REMOVE }.map { it.entryName }
    assertEquals(setOf("libQnnHtpV79Skel.so", "libQnnHtpV79Stub.so"), removed.toSet())
  }

  @Test
  fun qualcommPlan_healthyEntries_kept() {
    val existing = qualcommSm8850Required.associateWith { expectedTarget(it) }
    val plan = qualcommPlan(existing)
    assertTrue(plan.all { it.kind == VendorDispatchSyncKind.KEEP })
  }

  @Test
  fun qualcommPlan_brokenOrDanglingSymlinks_repaired() {
    val plan =
      qualcommPlan(
        existingEntries =
          mapOf(
            // Wrong target.
            "libQnnHtpV81Stub.so" to expectedTarget("libQnnHtpV79Stub.so"),
            // Regular file / broken symlink.
            "libQnnHtpV81Skel.so" to null,
          ),
      )
    val repaired = plan.filter { it.kind == VendorDispatchSyncKind.REPAIR }.map { it.entryName }
    assertEquals(setOf("libQnnHtpV81Stub.so", "libQnnHtpV81Skel.so"), repaired.toSet())
  }

  @Test
  fun qualcommPlan_repeatedExecution_isIdempotent() {
    val first =
      qualcommPlan(
        existingEntries =
          mapOf(
            "libLiteRtDispatch_MediaTek.so" to expectedTarget("libLiteRtDispatch_MediaTek.so"),
            "libQnnHtpV79Stub.so" to expectedTarget("libQnnHtpV79Stub.so"),
          ),
      )
    // Simulate executing the plan: foreign entries gone, required entries healthy.
    val afterFirst = qualcommSm8850Required.associateWith { expectedTarget(it) }
    val second = qualcommPlan(afterFirst)
    assertTrue(second.all { it.kind == VendorDispatchSyncKind.KEEP })
    assertTrue(second.none { it.kind == VendorDispatchSyncKind.REMOVE })
    assertTrue(first.any { it.kind == VendorDispatchSyncKind.CREATE })
  }

  @Test
  fun preparation_carriesSocModelAndHtpGeneration() {
    val p =
      VendorDispatchPreparation(
        vendor = NpuDispatchVendor.QUALCOMM,
        vendorDispatchDir = File(qualcommDispatchDir),
        dirExists = true,
        visibleSoNames = qualcommSm8850Required,
        missingRequired = emptyList(),
        symlinkMode = true,
        errors = emptyList(),
        socModel = "SM8850",
        htpGeneration = "V81",
      )
    assertEquals("SM8850", p.socModel)
    assertEquals("V81", p.htpGeneration)
  }
}
