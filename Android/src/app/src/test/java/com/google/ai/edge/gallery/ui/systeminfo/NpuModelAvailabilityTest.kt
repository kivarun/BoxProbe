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
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuModelAvailabilityTest {

  private fun npuModel(name: String) =
    Model(
      name = name,
      isLlm = true,
      runtimeType = RuntimeType.LITERT_LM,
      accelerators = listOf(Accelerator.GPU, Accelerator.NPU),
      downloadFileName = "$name.litertlm",
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

  @Test
  fun noModel_isUnavailable() {
    val key = NpuModelAvailabilityKey.of(uiState(models = emptyList()))
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
      )
    assertEquals(NpuModelAvailability.UNAVAILABLE, npuModelAvailability(key))
  }

  @Test
  fun succeededNpuModel_isAvailable() {
    val model = npuModel("npu-1b")
    val key =
      NpuModelAvailabilityKey.of(
        uiState(
          models = listOf(model),
          downloadStatus = mapOf(model.name to status(ModelDownloadStatusType.SUCCEEDED)),
        ),
      )
    assertEquals(NpuModelAvailability.AVAILABLE, npuModelAvailability(key))
    assertEquals(setOf(model.name), key.succeededNpuCompatibleModelNames)
  }

  @Test
  fun succeededCpuGpuOnlyModel_isUnavailable() {
    val model = gpuOnlyModel("gpu-1b")
    val key =
      NpuModelAvailabilityKey.of(
        uiState(
          models = listOf(model),
          downloadStatus = mapOf(model.name to status(ModelDownloadStatusType.SUCCEEDED)),
        ),
      )
    assertEquals(NpuModelAvailability.UNAVAILABLE, npuModelAvailability(key))
  }

  @Test
  fun deleteLastNpuModel_isUnavailable() {
    val model = npuModel("npu-1b")
    val before =
      NpuModelAvailabilityKey.of(
        uiState(
          models = listOf(model),
          downloadStatus = mapOf(model.name to status(ModelDownloadStatusType.SUCCEEDED)),
        ),
      )
    val after =
      NpuModelAvailabilityKey.of(
        uiState(
          models = emptyList(),
          downloadStatus = emptyMap(),
          importingUpdateTrigger = 42L,
        ),
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
      )
    val succeeded =
      NpuModelAvailabilityKey.of(
        uiState(
          models = listOf(model),
          downloadStatus = mapOf(model.name to status(ModelDownloadStatusType.SUCCEEDED)),
        ),
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
      )
    assertEquals(progress1, progress2)
  }

  @Test
  fun importingUpdateTriggerChangesKey() {
    val model = npuModel("imported-npu")
    val succeeded = status(ModelDownloadStatusType.SUCCEEDED)
    val key1 =
      NpuModelAvailabilityKey.of(
        uiState(
          models = listOf(model),
          downloadStatus = mapOf(model.name to succeeded),
          importingUpdateTrigger = 1L,
        ),
      )
    val key2 =
      NpuModelAvailabilityKey.of(
        uiState(
          models = listOf(model),
          downloadStatus = mapOf(model.name to succeeded),
          importingUpdateTrigger = 2L,
        ),
      )
    assertNotEquals(key1, key2)
  }

  @Test
  fun allowlistLoadingIsPartOfKey() {
    val keyLoading = NpuModelAvailabilityKey.of(uiState(models = emptyList(), allowlistLoading = true))
    val keyLoaded = NpuModelAvailabilityKey.of(uiState(models = emptyList(), allowlistLoading = false))
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
      Model(name = "aicore", isLlm = true, runtimeType = RuntimeType.AICORE, accelerators = listOf(Accelerator.NPU))
    val nonLlm =
      Model(name = "embed", isLlm = false, runtimeType = RuntimeType.LITERT_LM, accelerators = listOf(Accelerator.NPU))
    assertTrue(isNpuCompatibleCandidate(tpuModel))
    assertFalse(isNpuCompatibleCandidate(aicoreModel))
    assertFalse(isNpuCompatibleCandidate(nonLlm))
  }
}
