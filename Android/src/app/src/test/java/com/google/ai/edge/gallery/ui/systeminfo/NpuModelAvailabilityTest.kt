package com.google.ai.edge.gallery.ui.systeminfo

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.CategoryInfo
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuModelAvailabilityTest {

  private val deviceSoc = "mt6991"

  private fun npuModel(name: String, targetSoc: String? = null) =
    Model(
      name = name,
      isLlm = true,
      runtimeType = RuntimeType.LITERT_LM,
      accelerators = listOf(Accelerator.GPU, Accelerator.NPU),
      downloadFileName = "$name.litertlm",
      targetSoc = targetSoc,
    )

  private fun gpuOnlyModel(name: String) =
    Model(
      name = name,
      isLlm = true,
      runtimeType = RuntimeType.LITERT_LM,
      accelerators = listOf(Accelerator.GPU, Accelerator.CPU),
      downloadFileName = "$name.litertlm",
    )

  private fun task(vararg models: Model) =
    Task(
      id = "llm_chat",
      label = "AI Chat",
      category = CategoryInfo(id = "llm", label = "AI Chat"),
      models = mutableListOf(*models),
      description = "test",
    )

  private fun status(type: ModelDownloadStatusType) = ModelDownloadStatus(status = type)

  private fun uiState(
    models: List<Model>,
    downloadStatus: Map<String, ModelDownloadStatus> = emptyMap(),
    allowlistLoading: Boolean = false,
    importingUpdateTrigger: Long = 0L,
  ) =
    ModelManagerUiState(
      tasks = listOf(task(*models.toTypedArray())),
      modelDownloadStatus = downloadStatus,
      modelInitializationStatus = emptyMap(),
      loadingModelAllowlist = allowlistLoading,
      modelImportingUpdateTrigger = importingUpdateTrigger,
    )

  private fun succeeded(model: Model) = model.name to status(ModelDownloadStatusType.SUCCEEDED)

  @Test
  fun noModel_isUnavailable() {
    val key = NpuModelAvailabilityKey.of(uiState(models = emptyList()), deviceSoc)
    assertEquals(NpuModelAvailability.UNAVAILABLE, npuModelAvailability(key))
    assertTrue(key.succeededNpuCompatibleModelNames.isEmpty())
  }

  @Test
  fun inProgressModel_isUnavailable() {
    val model = npuModel("npu-1b")
    val key =
      NpuModelAvailabilityKey.of(
        uiState(
          models = listOf(model),
          downloadStatus = mapOf(model.name to status(ModelDownloadStatusType.IN_PROGRESS)),
        ),
        deviceSoc,
      )
    assertEquals(NpuModelAvailability.UNAVAILABLE, npuModelAvailability(key))
  }

  @Test
  fun downloadedGenericNpuArtifact_isAvailable() {
    val model = npuModel("npu-1b")
    val key =
      NpuModelAvailabilityKey.of(
        uiState(models = listOf(model), downloadStatus = mapOf(succeeded(model))),
        deviceSoc,
      )
    assertEquals(NpuModelAvailability.AVAILABLE, npuModelAvailability(key))
    assertEquals(setOf(model.name), key.succeededNpuCompatibleModelNames)
  }

  @Test
  fun downloadedDeviceMatchingNpuArtifact_isAvailable() {
    val model = npuModel("gemma-mt6991", targetSoc = "mt6991")
    val key =
      NpuModelAvailabilityKey.of(
        uiState(models = listOf(model), downloadStatus = mapOf(succeeded(model))),
        deviceSoc,
      )
    assertEquals(NpuModelAvailability.AVAILABLE, npuModelAvailability(key))
  }

  @Test
  fun wrongSocNpuArtifact_isUnavailable() {
    val model = npuModel("gemma-sm8650", targetSoc = "sm8650")
    val key =
      NpuModelAvailabilityKey.of(
        uiState(models = listOf(model), downloadStatus = mapOf(succeeded(model))),
        deviceSoc,
      )
    assertEquals(NpuModelAvailability.UNAVAILABLE, npuModelAvailability(key))
    assertTrue(key.succeededNpuCompatibleModelNames.isEmpty())
  }

  @Test
  fun succeededCpuGpuOnlyModel_isUnavailable() {
    val model = gpuOnlyModel("gpu-1b")
    val key =
      NpuModelAvailabilityKey.of(
        uiState(models = listOf(model), downloadStatus = mapOf(succeeded(model))),
        deviceSoc,
      )
    assertEquals(NpuModelAvailability.UNAVAILABLE, npuModelAvailability(key))
  }

  @Test
  fun deleteLastNpuModel_isUnavailable() {
    val model = npuModel("npu-1b", targetSoc = deviceSoc)
    val before =
      NpuModelAvailabilityKey.of(
        uiState(models = listOf(model), downloadStatus = mapOf(succeeded(model))),
        deviceSoc,
      )
    val after =
      NpuModelAvailabilityKey.of(
        uiState(
          models = emptyList(),
          downloadStatus = emptyMap(),
          importingUpdateTrigger = 42L,
        ),
        deviceSoc,
      )
    assertEquals(NpuModelAvailability.AVAILABLE, npuModelAvailability(before))
    assertEquals(NpuModelAvailability.UNAVAILABLE, npuModelAvailability(after))
    assertNotEquals(before, after)
  }

  @Test
  fun downloadCompletionChangesKey_fromInProgressToSucceeded() {
    val model = npuModel("npu-1b")
    val inProgress =
      NpuModelAvailabilityKey.of(
        uiState(
          models = listOf(model),
          downloadStatus = mapOf(model.name to status(ModelDownloadStatusType.IN_PROGRESS)),
        ),
        deviceSoc,
      )
    val succeeded =
      NpuModelAvailabilityKey.of(
        uiState(models = listOf(model), downloadStatus = mapOf(succeeded(model))),
        deviceSoc,
      )
    assertNotEquals(inProgress, succeeded)
  }

  @Test
  fun progressUpdatesDoNotChangeKey() {
    val model = npuModel("npu-1b")
    val progress1 =
      NpuModelAvailabilityKey.of(
        uiState(
          models = listOf(model),
          downloadStatus =
            mapOf(
              model.name to
                ModelDownloadStatus(
                  status = ModelDownloadStatusType.IN_PROGRESS,
                  receivedBytes = 1L,
                  totalBytes = 10L,
                ),
            ),
        ),
        deviceSoc,
      )
    val progress2 =
      NpuModelAvailabilityKey.of(
        uiState(
          models = listOf(model),
          downloadStatus =
            mapOf(
              model.name to
                ModelDownloadStatus(
                  status = ModelDownloadStatusType.IN_PROGRESS,
                  receivedBytes = 9L,
                  totalBytes = 10L,
                ),
            ),
        ),
        deviceSoc,
      )
    assertEquals(progress1, progress2)
  }

  @Test
  fun importingUpdateTriggerChangesKey() {
    val model = npuModel("imported-npu")
    val key1 =
      NpuModelAvailabilityKey.of(
        uiState(
          models = listOf(model),
          downloadStatus = mapOf(succeeded(model)),
          importingUpdateTrigger = 1L,
        ),
        deviceSoc,
      )
    val key2 =
      NpuModelAvailabilityKey.of(
        uiState(
          models = listOf(model),
          downloadStatus = mapOf(succeeded(model)),
          importingUpdateTrigger = 2L,
        ),
        deviceSoc,
      )
    assertNotEquals(key1, key2)
  }

  @Test
  fun allowlistLoadingIsPartOfKey() {
    val keyLoading =
      NpuModelAvailabilityKey.of(uiState(models = emptyList(), allowlistLoading = true), deviceSoc)
    val keyLoaded =
      NpuModelAvailabilityKey.of(uiState(models = emptyList(), allowlistLoading = false), deviceSoc)
    assertFalse(keyLoading == keyLoaded)
    assertEquals(NpuModelAvailability.UNAVAILABLE, npuModelAvailability(keyLoaded))
  }

  @Test
  fun candidateRequiresNpuOrTpuAccelerator() {
    val tpuModel =
      Model(
        name = "tpu-only",
        isLlm = true,
        runtimeType = RuntimeType.LITERT_LM,
        accelerators = listOf(Accelerator.TPU),
      )
    val aicoreModel =
      Model(
        name = "aicore",
        isLlm = true,
        runtimeType = RuntimeType.AICORE,
        accelerators = listOf(Accelerator.NPU),
      )
    val nonLlm =
      Model(
        name = "embed",
        isLlm = false,
        runtimeType = RuntimeType.LITERT_LM,
        accelerators = listOf(Accelerator.NPU),
      )
    assertTrue(isNpuCompatibleCandidate(tpuModel, deviceSoc))
    assertFalse(isNpuCompatibleCandidate(aicoreModel, deviceSoc))
    assertFalse(isNpuCompatibleCandidate(nonLlm, deviceSoc))
  }

  @Test
  fun selection_preferredKept() {
    assertEquals("b", resolveNpuSelection(listOf("a", "b"), "b"))
  }

  @Test
  fun selection_preferredDeleted_fallsBackToFirstDeterministically() {
    assertEquals("a", resolveNpuSelection(listOf("a"), "removed"))
    assertEquals(
      "first",
      resolveNpuSelection(listOf("first", "second"), "removed"),
    )
  }

  @Test
  fun selection_emptyCandidates_clears() {
    assertNull(resolveNpuSelection(emptyList(), "a"))
    assertNull(resolveNpuSelection(emptyList(), null))
  }

  @Test
  fun selection_noPreferred_selectsFirst() {
    assertEquals("a", resolveNpuSelection(listOf("a", "b"), null))
  }
}
