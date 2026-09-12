package com.google.ai.edge.gallery.ui.systeminfo

import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.artifactMatchesDeviceSoc
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
    fun of(uiState: ModelManagerUiState, deviceSoc: String): NpuModelAvailabilityKey =
      NpuModelAvailabilityKey(
        allowlistLoading = uiState.loadingModelAllowlist,
        importingUpdateTrigger = uiState.modelImportingUpdateTrigger,
        succeededNpuCompatibleModelNames =
          succeededNpuCompatibleModelNames(
            models = uiState.tasks.flatMap { it.models },
            downloadStatus = uiState.modelDownloadStatus,
            deviceSoc = deviceSoc,
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
 * True when the model is a downloadable-artifact candidate for the NPU probe path:
 * an LLM running through the LiteRT-LM runtime whose artifact declares NPU/TPU backend
 * compatibility and can run on [deviceSoc] (generic artifacts always can; SoC-specific
 * artifacts must declare a matching target SoC).
 */
fun isNpuCompatibleCandidate(model: Model, deviceSoc: String): Boolean =
  model.isLlm &&
    model.runtimeType == RuntimeType.LITERT_LM &&
    (model.accelerators.contains(Accelerator.NPU) || model.accelerators.contains(Accelerator.TPU)) &&
    artifactMatchesDeviceSoc(model, deviceSoc)

/** Names of NPU-compatible models whose download status is [ModelDownloadStatusType.SUCCEEDED]. */
fun succeededNpuCompatibleModelNames(
  models: Collection<Model>,
  downloadStatus: Map<String, ModelDownloadStatus>,
  deviceSoc: String,
): Set<String> =
  models.asSequence()
    .filter { downloadStatus[it.name]?.status == ModelDownloadStatusType.SUCCEEDED }
    .filter { isNpuCompatibleCandidate(it, deviceSoc) }
    .map { it.name }
    .toSet()

/**
 * Deterministic probe model selection: keep [preferred] while it is still a candidate,
 * otherwise fall back to the first candidate, or null when there is none.
 */
fun resolveNpuSelection(candidates: List<String>, preferred: String?): String? =
  when {
    candidates.isEmpty() -> null
    preferred != null && candidates.contains(preferred) -> preferred
    else -> candidates.first()
  }

fun npuModelAvailability(key: NpuModelAvailabilityKey): NpuModelAvailability =
  if (key.succeededNpuCompatibleModelNames.isEmpty()) NpuModelAvailability.UNAVAILABLE
  else NpuModelAvailability.AVAILABLE
