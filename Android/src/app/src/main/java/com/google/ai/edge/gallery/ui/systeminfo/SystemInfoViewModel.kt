package com.google.ai.edge.gallery.ui.systeminfo

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.systeminfo.NpuProbeResult
import com.google.ai.edge.gallery.systeminfo.NpuProbeStatus
import com.google.ai.edge.gallery.systeminfo.NpuRuntimeProbe
import com.google.ai.edge.gallery.systeminfo.SystemInfoCollector
import com.google.ai.edge.gallery.systeminfo.SystemInfoSnapshot
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state of the active NPU probe panel. */
data class NpuProbeUiState(
  val status: NpuProbeStatus = NpuProbeStatus.NOT_PROBED,
  val result: NpuProbeResult? = null,
  val selectedModelName: String? = null,
)

@HiltViewModel
class SystemInfoViewModel
@Inject
constructor(
  @ApplicationContext private val appContext: Context,
) : ViewModel() {

  private val _snapshot = MutableStateFlow<SystemInfoSnapshot?>(null)
  val snapshot = _snapshot.asStateFlow()

  private val _collecting = MutableStateFlow(false)
  val collecting = _collecting.asStateFlow()

  private val _npuProbeState = MutableStateFlow(NpuProbeUiState())
  val npuProbeState = _npuProbeState.asStateFlow()

  init {
    collect()
  }

  fun collect() {
    if (_collecting.value) return
    viewModelScope.launch {
      _collecting.value = true
      val result =
        withContext(Dispatchers.Default) { SystemInfoCollector.collect(appContext) }
      _snapshot.value = result
      _collecting.value = false
    }
  }

  /**
   * Re-evaluates whether a downloaded LiteRT-LM model whose allowlist permits NPU is
   * available locally. No model is downloaded automatically.
   */
  fun refreshNpuModelAvailability(modelManagerViewModel: ModelManagerViewModel) {
    if (_npuProbeState.value.status == NpuProbeStatus.RUNNING) return
    val model = selectNpuCompatibleModel(modelManagerViewModel)
    _npuProbeState.value =
      _npuProbeState.value.copy(
        selectedModelName = model?.name,
        status =
          when {
            model == null && _npuProbeState.value.status == NpuProbeStatus.NOT_PROBED ->
              NpuProbeStatus.NO_MODEL
            model != null && _npuProbeState.value.status == NpuProbeStatus.NO_MODEL ->
              NpuProbeStatus.NOT_PROBED
            else -> _npuProbeState.value.status
          },
      )
  }

  fun runNpuProbe(modelManagerViewModel: ModelManagerViewModel) {
    if (_npuProbeState.value.status == NpuProbeStatus.RUNNING) return
    viewModelScope.launch {
      val model = selectNpuCompatibleModel(modelManagerViewModel)
      if (model == null) {
        _npuProbeState.value =
          NpuProbeUiState(status = NpuProbeStatus.NO_MODEL, selectedModelName = null)
        return@launch
      }
      _npuProbeState.value =
        NpuProbeUiState(status = NpuProbeStatus.RUNNING, selectedModelName = model.name)
      val result =
        withContext(Dispatchers.Default) { NpuRuntimeProbe.run(appContext, model) }
      _npuProbeState.value =
        NpuProbeUiState(
          status =
            if (result.failedStage == null) NpuProbeStatus.INITIALIZATION_PASSED
            else NpuProbeStatus.INITIALIZATION_FAILED,
          result = result,
          selectedModelName = model.name,
        )
    }
  }

  /**
   * First downloaded LiteRT-LM model whose allowlist permits the NPU path (NPU/TPU),
   * or null. Deterministic: the first model in sorted display-name order.
   */
  private fun selectNpuCompatibleModel(
    modelManagerViewModel: ModelManagerViewModel,
  ): Model? =
    modelManagerViewModel.getAllDownloadedModels()
      .filter { it.runtimeType == RuntimeType.LITERT_LM }
      .filter {
        it.accelerators.contains(Accelerator.NPU) || it.accelerators.contains(Accelerator.TPU)
      }
      .firstOrNull()
}
