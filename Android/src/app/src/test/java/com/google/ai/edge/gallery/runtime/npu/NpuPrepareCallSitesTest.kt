package com.google.ai.edge.gallery.runtime.npu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural pin for the CPU/GPU invariants: the vendor dispatch preparation must only
 * ever be reachable from the production NPU paths (the lazy NPU backend branch in
 * LlmChatModelHelper and the NpuRuntimeProbe PRECHECK). CPU/GPU initialization shares
 * no call path into it, so it can never prepare the MediaTek or Qualcomm directories.
 */
class NpuPrepareCallSitesTest {

  private val mainRoot = File("src/main/java/com/google/ai/edge/gallery")

  private fun filesContaining(needle: String): List<String> =
    mainRoot.walkTopDown()
      .filter { it.isFile && it.extension == "kt" }
      .filter { it.readText().contains(needle) }
      .map { it.relativeTo(mainRoot).path }
      .sorted()
      .toList()

  @Test
  fun devicePreparation_isReferencedOnlyFromNpuProductionPaths() {
    val files = filesContaining("prepareVendorDispatchRuntimeForDevice")
    assertEquals(
      listOf(
        "runtime/npu/VendorDispatchRuntime.kt",
        "systeminfo/NpuRuntimeProbe.kt",
      ),
      files,
    )
  }

  @Test
  fun probe_preparesTheVendorRuntimeExactlyOncePerRun() {
    val source = File(mainRoot, "systeminfo/NpuRuntimeProbe.kt").readText()
    assertEquals(
      1,
      Regex("prepareVendorDispatchRuntimeForDevice\\(").findAll(source).count(),
    )
  }

  @Test
  fun lazyNpuLibraryDir_isOnlyDereferencedInsideNpuBackendBranches() {
    val source = File(mainRoot, "runtime/LlmChatModelHelper.kt").readText()
    assertTrue(source.contains("by lazy { npuNativeLibraryDirForDevice(context) }"))
    // Every dereference of the lazy value must sit in a Backend.NPU(...) argument.
    val dereferences = Regex("npuNativeLibraryDir(?![A-Za-z])").findAll(source).count()
    val lazyDeclaration = 1
    val npuBackendUsages = Regex("Backend\\.NPU\\(nativeLibraryDir = npuNativeLibraryDir\\)")
      .findAll(source)
      .count()
    assertEquals(lazyDeclaration + npuBackendUsages, dereferences)
    assertTrue(npuBackendUsages >= 1)
  }
}
