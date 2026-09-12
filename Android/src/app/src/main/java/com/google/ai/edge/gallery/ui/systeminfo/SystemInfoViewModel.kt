package com.google.ai.edge.gallery.ui.systeminfo

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.SOC
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state of the active NPU probe panel. */
data class NpuProbeUiState(
  val status: NpuProbeStatus = NpuProbeStatus.NOT_PROBED,
  val result: NpuProbeResult? = null,
  val selectedModelName: String? = null,
)

/** Static build identity shown by the System Info screen. */
data class BuildInfoSnapshot(
  val versionName: String,
  val versionCode: Int,
  val gitCommit: String,
  val applicationId: String,
  val buildType: String,
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

  /** Downloaded models that are explicit NPU probe candidates for this device. */
  private val _npuCandidates = MutableStateFlow<List<Model>>(emptyList())
  val npuCandidates = _npuCandidates.asStateFlow()

  /** Lowercase SoC model of this device, e.g. "mt6991". */
  private val deviceSoc: String = SOC

  val buildInfo: BuildInfoSnapshot = readBuildInfo()

  private var availabilityObservationStarted = false

  init {
    collect()
  }

  private fun readBuildInfo(): BuildInfoSnapshot =
    BuildInfoSnapshot(
      versionName = com.google.ai.edge.gallery.BuildConfig.VERSION_NAME,
      versionCode = com.google.ai.edge.gallery.BuildConfig.VERSION_CODE,
      gitCommit = com.google.ai.edge.gallery.BuildConfig.GIT_COMMIT,
      applicationId = com.google.ai.edge.gallery.BuildConfig.APPLICATION_ID,
      buildType = com.google.ai.edge.gallery.BuildConfig.BUILD_TYPE,
    )

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
   * Observes [ModelManagerViewModel.uiState] and re-evaluates NPU model availability
   * whenever the stable [NpuModelAvailabilityKey] changes: download completion, model
   * deletion, model import and catalog load completion all change the key. Download
   * progress updates do not change the key, so availability is not recomputed per byte.
   */
  fun observeNpuModelAvailability(modelManagerViewModel: ModelManagerViewModel) {
    if (availabilityObservationStarted) return
    availabilityObservationStarted = true
    viewModelScope.launch {
      modelManagerViewModel.uiState
        .map { NpuModelAvailabilityKey.of(it, deviceSoc) }
        .distinctUntilChanged()
        .collect { refreshNpuModelAvailability(modelManagerViewModel) }
    }
  }

  /** Manually selects a probe model from the candidate list. */
  fun selectNpuCandidate(modelName: String) {
    if (_npuProbeState.value.status == NpuProbeStatus.RUNNING) return
    if (_npuCandidates.value.none { it.name == modelName }) return
    _npuProbeState.value =
      _npuProbeState.value.copy(
        selectedModelName = modelName,
        status =
          if (_npuProbeState.value.status == NpuProbeStatus.NO_MODEL) NpuProbeStatus.NOT_PROBED
          else _npuProbeState.value.status,
      )
  }

  /**
   * Recomputes the candidate list and the deterministic selection: keep the current
   * selection while it is still a candidate, otherwise select the first candidate, or
   * clear (and show NO_MODEL) when there are none.
   */
  fun refreshNpuModelAvailability(modelManagerViewModel: ModelManagerViewModel) {
    if (_npuProbeState.value.status == NpuProbeStatus.RUNNING) return
    val candidates =
      modelManagerViewModel.getAllDownloadedModels()
        .filter { isNpuCompatibleCandidate(it, deviceSoc) }
    val selected =
      resolveNpuSelection(
        candidates = candidates.map { it.name },
        preferred = _npuProbeState.value.selectedModelName,
      )
    _npuCandidates.value = candidates
    _npuProbeState.value =
      _npuProbeState.value.copy(
        selectedModelName = selected,
        status =
          when {
            selected == null && _npuProbeState.value.status == NpuProbeStatus.NOT_PROBED ->
              NpuProbeStatus.NO_MODEL
            selected != null && _npuProbeState.value.status == NpuProbeStatus.NO_MODEL ->
              NpuProbeStatus.NOT_PROBED
            else -> _npuProbeState.value.status
          },
      )
  }

  fun runNpuProbe(modelManagerViewModel: ModelManagerViewModel) {
    if (_npuProbeState.value.status == NpuProbeStatus.RUNNING) return
    viewModelScope.launch {
      val model =
        _npuCandidates.value.firstOrNull { it.name == _npuProbeState.value.selectedModelName }
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
}
