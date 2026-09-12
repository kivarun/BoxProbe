package com.google.ai.edge.gallery.ui.systeminfo

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerUiState

/**
 * Stable availability trigger derived from [ModelManagerUiState].
 *
 * Only fields that change when an NPU-compatible model becomes available or unavailable
 * are included: the allowlist loading state, the import/delete update trigger and the set
 * of downloaded NPU-compatible model names. Download progress updates rewrite
 * [ModelManagerUiState.modelDownloadStatus] entries (received bytes) without changing
 * this key, so consumers can de-duplicate emissions cheaply.
 */
data class NpuModelAvailabilityKey(
  val allowlistLoading: Boolean,
  val importingUpdateTrigger: Long,
  val succeededNpuCompatibleModelNames: Set<String>,
) {
  companion object {
    fun of(uiState: ModelManagerUiState): NpuModelAvailabilityKey =
      NpuModelAvailabilityKey(
        allowlistLoading = uiState.loadingModelAllowlist,
        importingUpdateTrigger = uiState.modelImportingUpdateTrigger,
        succeededNpuCompatibleModelNames =
          succeededNpuCompatibleModelNames(
            models = uiState.tasks.flatMap { it.models },
            downloadStatus = uiState.modelDownloadStatus,
          ),
      )
  }
}

/** Whether an NPU-compatible downloaded local model is currently available. */
enum class NpuModelAvailability {
  UNAVAILABLE,
  AVAILABLE,
}

/**
 * True when the model is a candidate for the NPU probe path: an LLM running through the
 * LiteRT-LM runtime whose allowlist permits the NPU backend (NPU or TPU accelerators,
 * matching the production Backend.NPU mapping).
 */
fun isNpuCompatibleCandidate(model: Model): Boolean =
  model.isLlm &&
    model.runtimeType == RuntimeType.LITERT_LM &&
    (model.accelerators.contains(Accelerator.NPU) || model.accelerators.contains(Accelerator.TPU))

/** Names of NPU-compatible models whose download status is [ModelDownloadStatusType.SUCCEEDED]. */
fun succeededNpuCompatibleModelNames(
  models: Collection<Model>,
  downloadStatus: Map<String, ModelDownloadStatus>,
): Set<String> =
  models.asSequence()
    .filter { downloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED }
    .filter(::isNpuCompatibleCandidate)
    .map { it.name }
    .toSet()

fun npuModelAvailability(key: NpuModelAvailabilityKey): NpuModelAvailability =
  if (key.succeededNpuCompatibleModelNames.isEmpty()) NpuModelAvailability.UNAVAILABLE
  else NpuModelAvailability.AVAILABLE
