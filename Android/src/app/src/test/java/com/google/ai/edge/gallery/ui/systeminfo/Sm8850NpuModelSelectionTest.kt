package com.google.ai.edge.gallery.ui.systeminfo

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection logic for the SM8850 Qualcomm NPU probe candidate: the device must see the
 * SoC-targeted Qualcomm artifact as compatible, and no other device may pick it up
 * automatically.
 */
class Sm8850NpuModelSelectionTest {

  private fun npuModel(name: String, targetSoc: String?): Model =
    Model(
      name = name,
      downloadFileName = "$name.litertlm",
      isLlm = true,
      runtimeType = RuntimeType.LITERT_LM,
      accelerators = listOf(Accelerator.NPU),
      targetSoc = targetSoc,
    )

  @Test
  fun sm8850Artifact_isCandidateOnlyOnSm8850() {
    val sm8850Model = npuModel("Gemma3-270M-IT-SM8850-NPU", "sm8850")
    assertTrue(isNpuCompatibleCandidate(sm8850Model, "sm8850"))
    assertFalse(isNpuCompatibleCandidate(sm8850Model, "mt6991"))
    assertFalse(isNpuCompatibleCandidate(sm8850Model, "sm8750"))
  }

  @Test
  fun mt6991Artifact_isNotCandidateOnSm8850() {
    val mt6991Model = npuModel("Gemma3-1B-IT-MT6991-NPU", "mt6991")
    assertFalse(isNpuCompatibleCandidate(mt6991Model, "sm8850"))
    assertTrue(isNpuCompatibleCandidate(mt6991Model, "mt6991"))
  }

  @Test
  fun genericArtifact_staysCompatibleOnEverySoc() {
    val generic = npuModel("Gemma3-1B-IT", null)
    assertTrue(isNpuCompatibleCandidate(generic, "sm8850"))
    assertTrue(isNpuCompatibleCandidate(generic, "mt6991"))
  }

  @Test
  fun nonLlmOrNonLitertModels_areNeverCandidates() {
    val sm8850Model = npuModel("Gemma3-270M-IT-SM8850-NPU", "sm8850")
    assertFalse(
      isNpuCompatibleCandidate(sm8850Model.copy(isLlm = false), "sm8850"),
    )
    assertFalse(
      isNpuCompatibleCandidate(
        sm8850Model.copy(runtimeType = RuntimeType.AICORE),
        "sm8850",
      ),
    )
    assertFalse(
      isNpuCompatibleCandidate(
        sm8850Model.copy(accelerators = listOf(Accelerator.GPU)),
        "sm8850",
      ),
    )
  }

  @Test
  fun succeededDownload_gateAppliesToSelection() {
    val models =
      listOf(
        npuModel("Gemma3-270M-IT-SM8850-NPU", "sm8850"),
        npuModel("Gemma3-1B-IT-MT6991-NPU", "mt6991"),
      )
    val downloaded = mapOf("Gemma3-270M-IT-SM8850-NPU" to ModelDownloadStatus(ModelDownloadStatusType.SUCCEEDED))
    assertEquals(
      setOf("Gemma3-270M-IT-SM8850-NPU"),
      succeededNpuCompatibleModelNames(models, downloaded, deviceSoc = "sm8850"),
    )
    assertEquals(
      emptySet<String>(),
      succeededNpuCompatibleModelNames(models, downloaded, deviceSoc = "mt6991"),
    )
  }

  @Test
  fun probeSelection_prefersStableChoiceAndFallsBackToFirst() {
    assertEquals(
      "a",
      resolveNpuSelection(candidates = listOf("a", "b"), preferred = "a"),
    )
    // A stale preference falls back to the deterministic first candidate.
    assertEquals(
      "a",
      resolveNpuSelection(candidates = listOf("a", "b"), preferred = "gone"),
    )
    assertEquals("a", resolveNpuSelection(candidates = listOf("a", "b"), preferred = null))
    assertEquals(null, resolveNpuSelection(candidates = emptyList(), preferred = "a"))
  }
}
