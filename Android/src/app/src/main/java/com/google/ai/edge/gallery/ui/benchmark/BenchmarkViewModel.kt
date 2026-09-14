/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.ai.edge.gallery.ui.benchmark

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.proto.BenchmarkResult
import com.google.ai.edge.gallery.proto.LlmBenchmarkBasicInfo
import com.google.ai.edge.gallery.proto.LlmBenchmarkResult
import com.google.ai.edge.gallery.proto.LlmBenchmarkStats
import com.google.ai.edge.gallery.proto.ValueSeries
import com.google.ai.edge.gallery.runtime.npu.npuNativeLibraryDirForDevice
import com.google.ai.edge.gallery.systeminfo.NpuDelegateVerdict
import com.google.ai.edge.gallery.systeminfo.captureSelfLogcatLines
import com.google.ai.edge.gallery.systeminfo.classifyDelegateLines
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGBenchmarkVM"

/**
 * Delegate-diagnostic state for a benchmark invocation. Must be reset at the start
 * of every run (and on failure) so no verdict from a previous run can survive and
 * masquerade as this run's outcome.
 */
fun BenchmarkUiState.withFreshDelegateState(accelerator: String): BenchmarkUiState =
  copy(
    lastAccelerator = accelerator,
    delegateVerdict = NpuDelegateVerdict.UNKNOWN,
    npuValidated = false,
  )

/**
 * Decides whether a finished benchmark run may enter the persistent benchmark
 * history.
 *
 * CPU/GPU runs persist on success. An NPU run persists only when the delegate
 * classifier proved DispatchDelegate delegation — a CPU/XNNPACK fallback or an
 * unknown verdict must never be stored as an NPU result. A failed run never
 * persists (the caller only reaches persistence after a successful run loop).
 */
fun shouldPersistBenchmark(
  accelerator: String,
  delegateVerdict: NpuDelegateVerdict,
  benchmarkSucceeded: Boolean,
): Boolean {
  if (!benchmarkSucceeded) {
    return false
  }
  if (!accelerator.equals("npu", ignoreCase = true)) {
    return true
  }
  return delegateVerdict == NpuDelegateVerdict.DISPATCH_DELEGATED
}

enum class Aggregation(val label: String) {
  AVG(label = "avg"),
  MEDIAN(label = "median"),
  MIN(label = "min"),
  MAX(label = "max"),
  // P25(label = "p25"),
  // P75(label = "p75"),
}

data class BenchmarkResultInfo(
  val id: String,
  val benchmarkResult: BenchmarkResult,
  val expanded: Boolean = false,
  val basicInfoExpanded: Boolean = true,
  val statsExpanded: Boolean = true,
  val aggregation: Aggregation = Aggregation.AVG,
)

data class BenchmarkUiState(
  val results: List<BenchmarkResultInfo> = listOf(),
  val baselineResult: BenchmarkResultInfo? = null,
  val showResultsViewer: Boolean = false,
  val running: Boolean = false,
  val totalRunCount: Int = 0,
  val completedRunCount: Int = 0,
  /** Set when a run failed with a runtime error; surfaces the error without crashing. */
  val runError: String = "",
  /** Accelerator string of the last benchmark invocation ("cpu"/"gpu"/"npu"). */
  val lastAccelerator: String = "",
  /** Delegate verdict captured from the partitioning logs of the last benchmark run. */
  val delegateVerdict: NpuDelegateVerdict = NpuDelegateVerdict.UNKNOWN,
  /** Whether the last NPU run produced delegation evidence (DispatchDelegate). */
  val npuValidated: Boolean = false,
)

@HiltViewModel
class BenchmarkViewModel
@Inject
constructor(
  @ApplicationContext private val appContext: Context,
  val dataStoreRepository: DataStoreRepository,
) : ViewModel() {
  protected val _uiState = MutableStateFlow(BenchmarkUiState())
  val uiState = _uiState.asStateFlow()

  init {
    // Load results from storage.
    val storedResults = dataStoreRepository.getAllBenchmarkResults()
    Log.d(TAG, "Loaded ${storedResults.size} benchmark results")
    setBenchmarkResults(results = storedResults)
    collapseAll()
  }

  @OptIn(ExperimentalApi::class)
  fun runBenchmark(
    model: Model,
    accelerator: String,
    prefillTokens: Int,
    decodeTokens: Int,
    runCount: Int,
  ) {
    viewModelScope.launch(Dispatchers.Default) {
      setRunning(running = true)
      setRunError(error = "")
      // No verdict from a previous run may bleed into this one.
      _uiState.update { it.withFreshDelegateState(accelerator) }
      setRunProgress(completedRunCount = 0)
      setTotalRunCount(totalRunCount = runCount)
      setShowResultsViewer(showResultsViewer = true)

      val parts: List<String> =
        listOf(
          "- model: ${model.name}",
          "- accelerator: $accelerator",
          "- prefill tokens: $prefillTokens",
          "- decode tokens: $decodeTokens",
          "- runs: $runCount",
        )
      Log.d(TAG, "Running benchmark: ${parts.joinToString("\n")}")

      // Create a temporary cache dir to run benchmark in.
      val timestamp = System.currentTimeMillis()
      var needCleanUpCacheDir = true
      val benchmarkCacheDir = File(appContext.cacheDir, "benchmark_$timestamp")
      val runStartWallClock = System.currentTimeMillis()

      try {
      val startMs = System.currentTimeMillis()
      val prefillSpeeds = mutableListOf<Double>()
      val decodeSpeeds = mutableListOf<Double>()
      val timesToFirstToken = mutableListOf<Double>()
      var firstInitTime = 0.0
      val nonFirstInitTimes = mutableListOf<Double>()
      var cacheDirPath = benchmarkCacheDir.absolutePath
      if (!benchmarkCacheDir.mkdirs()) {
        Log.e(TAG, "Failed to create benchmark cache directory: ${benchmarkCacheDir.absolutePath}")
        cacheDirPath = appContext.cacheDir.absolutePath
        needCleanUpCacheDir = false
      }
      Log.d(TAG, "Using benchmark cache dir: $cacheDirPath")
      val backend: Backend =
        when (accelerator.lowercase()) {
          "gpu" -> Backend.GPU()
          "npu" -> Backend.NPU(nativeLibraryDir = npuNativeLibraryDirForDevice(appContext))
          else -> Backend.CPU()
        }
      val modelPath = model.getPath(context = appContext)
      for (i in 0 until runCount) {
        Log.d(TAG, "Start running #$i...")
        val benchmarkInfo =
          benchmark(
            modelPath = modelPath,
            backend = backend,
            prefillTokens = prefillTokens,
            decodeTokens = decodeTokens,
            cacheDir = cacheDirPath,
          )
        Log.d(TAG, "Done #$i")

        val initTimeMs = benchmarkInfo.initTimeInSecond * 1000.0
        if (i == 0) {
          firstInitTime = initTimeMs
        } else {
          nonFirstInitTimes.add(initTimeMs)
        }
        prefillSpeeds.add(benchmarkInfo.lastPrefillTokensPerSecond)
        decodeSpeeds.add(benchmarkInfo.lastDecodeTokensPerSecond)
        timesToFirstToken.add(benchmarkInfo.timeToFirstTokenInSecond)

        // Mark finish for this run.
        setRunProgress(completedRunCount = i + 1)
      }
      val endMs = System.currentTimeMillis()

      // Delegate evidence must exist BEFORE any persistence decision: an NPU-marked
      // benchmark whose partitioning logs do not prove DispatchDelegate delegation
      // must never enter the persistent benchmark history as an NPU result.
      var delegateVerdict = NpuDelegateVerdict.UNKNOWN
      if (accelerator.lowercase() == "npu") {
        val captured = captureSelfLogcatLines(sinceMs = runStartWallClock)
        delegateVerdict = classifyDelegateLines(captured).verdict
        if (delegateVerdict != NpuDelegateVerdict.DISPATCH_DELEGATED) {
          Log.w(
            TAG,
            "NPU benchmark delegate guard: $delegateVerdict — result will not be persisted",
          )
        }
      }

      // Create benchmark result and persist it only when the run qualifies.
      val basicInfo =
        LlmBenchmarkBasicInfo.newBuilder()
          .setStartMs(startMs)
          .setEndMs(endMs)
          .setModelName(model.name)
          .setAccelerator(accelerator)
          .setPrefillTokens(prefillTokens)
          .setDecodeTokens(decodeTokens)
          .setNumberOfRuns(runCount)
          .setAppVersion(BuildConfig.VERSION_NAME)
          .build()
      val stats =
        LlmBenchmarkStats.newBuilder()
          .setPrefillSpeed(calculateValueSeries(prefillSpeeds))
          .setDecodeSpeed(calculateValueSeries(decodeSpeeds))
          .setTimeToFirstToken(calculateValueSeries(timesToFirstToken))
          .setFirstInitTimeMs(firstInitTime)
          .setNonFirstInitTimeMs(calculateValueSeries(nonFirstInitTimes))
          .build()

      val result =
        BenchmarkResult.newBuilder()
          .setLlmResult(
            LlmBenchmarkResult.newBuilder().setBaiscInfo(basicInfo).setStats(stats).build()
          )
          .build()
      if (shouldPersistBenchmark(accelerator, delegateVerdict, benchmarkSucceeded = true)) {
        val newId = addBenchmarkResult(result = result)
        collapseAll()
        setExpanded(id = newId, expanded = true)
      }
      _uiState.update {
        it.copy(
          lastAccelerator = accelerator,
          delegateVerdict = delegateVerdict,
          npuValidated = delegateVerdict == NpuDelegateVerdict.DISPATCH_DELEGATED,
        )
      }
      } catch (t: Throwable) {
        Log.e(TAG, "Benchmark run failed", t)
        setRunError(error = t.message ?: t.javaClass.simpleName)
        // The failed run's diagnostic state is its own: unknown verdict, nothing
        // validated — never the verdict of an earlier run.
        _uiState.update { it.withFreshDelegateState(accelerator) }
      } finally {
        if (needCleanUpCacheDir && benchmarkCacheDir.isDirectory) {
          benchmarkCacheDir.deleteRecursively()
          Log.d(TAG, "Cleaned up benchmark cache dir: ${benchmarkCacheDir.absolutePath}")
        }
      }

      setRunning(running = false)
    }
  }

  /** Kept for structural symmetry with other setters; error state is cleared per run. */
  fun setRunError(error: String) {
    _uiState.update { _uiState.value.copy(runError = error) }
  }

  fun setShowResultsViewer(showResultsViewer: Boolean) {
    _uiState.update { _uiState.value.copy(showResultsViewer = showResultsViewer) }
  }

  fun setRunning(running: Boolean) {
    _uiState.update { _uiState.value.copy(running = running) }
  }

  fun setTotalRunCount(totalRunCount: Int) {
    _uiState.update { _uiState.value.copy(totalRunCount = totalRunCount) }
  }

  fun setRunProgress(completedRunCount: Int) {
    _uiState.update { _uiState.value.copy(completedRunCount = completedRunCount) }
  }

  fun addBenchmarkResult(result: BenchmarkResult): String {
    val newResults = _uiState.value.results.toMutableList()
    // Add the new result to the beginning of the list.
    val newId = "${Random.nextDouble()}"
    newResults.add(
      0,
      BenchmarkResultInfo(
        benchmarkResult = result,
        id = newId,
        basicInfoExpanded = true,
        statsExpanded = true,
      ),
    )
    _uiState.update { _uiState.value.copy(results = newResults) }

    // Save to storage.
    dataStoreRepository.addBenchmarkResult(result)

    return newId
  }

  fun setBenchmarkResults(results: List<BenchmarkResult>) {
    _uiState.update {
      _uiState.value.copy(
        results =
          results.map { result ->
            BenchmarkResultInfo(
              benchmarkResult = result,
              expanded = false,
              id = "${Random.nextDouble()}",
              basicInfoExpanded = false,
              statsExpanded = true,
            )
          }
      )
    }
  }

  fun deleteBenchmarkResult(id: String) {
    val newResults = _uiState.value.results.toMutableList()
    val index = newResults.indexOfFirst { it.id == id }
    if (index != -1) {
      val deletedResult = newResults.removeAt(index)
      _uiState.update { _uiState.value.copy(results = newResults) }
      if (deletedResult.id == uiState.value.baselineResult?.id) {
        _uiState.update { _uiState.value.copy(baselineResult = null) }
      }

      // Update storage.
      dataStoreRepository.deleteBenchmarkResult(index = index)
    } else {
      Log.w(TAG, "Benchmark result with id $id not found.")
    }
  }

  fun setBaseline(id: String) {
    if (id == uiState.value.baselineResult?.id) {
      clearBaseline()
    } else {
      val result = _uiState.value.results.firstOrNull { it.id == id }
      if (result == null) {
        Log.w(TAG, "Benchmark result with id $id not found.")
        return
      }
      _uiState.update { _uiState.value.copy(baselineResult = result) }
    }
  }

  fun clearBaseline() {
    _uiState.update { _uiState.value.copy(baselineResult = null) }
  }

  fun setExpanded(id: String, expanded: Boolean) {
    val newResults = _uiState.value.results.toMutableList()
    val index = newResults.indexOfFirst { it.id == id }
    if (index != -1) {
      newResults[index] =
        newResults[index].copy(
          expanded = expanded,
          basicInfoExpanded = expanded,
          statsExpanded = expanded,
        )
      _uiState.update { _uiState.value.copy(results = newResults) }
    } else {
      Log.w(TAG, "Benchmark result with id $id not found.")
    }
  }

  fun setBasicInfoExpanded(id: String, expanded: Boolean) {
    val newResults = _uiState.value.results.toMutableList()
    val index = newResults.indexOfFirst { it.id == id }
    if (index != -1) {
      newResults[index] = newResults[index].copy(basicInfoExpanded = expanded)
      _uiState.update { _uiState.value.copy(results = newResults) }
    } else {
      Log.w(TAG, "Benchmark result with id $id not found.")
    }
  }

  fun setStatsExpanded(id: String, expanded: Boolean) {
    val newResults = _uiState.value.results.toMutableList()
    val index = newResults.indexOfFirst { it.id == id }
    if (index != -1) {
      newResults[index] = newResults[index].copy(statsExpanded = expanded)
      _uiState.update { _uiState.value.copy(results = newResults) }
    } else {
      Log.w(TAG, "Benchmark result with id $id not found.")
    }
  }

  fun expandAll() {
    val newResults = _uiState.value.results.toMutableList()
    for (i in newResults.indices) {
      newResults[i] =
        newResults[i].copy(expanded = true, statsExpanded = true, basicInfoExpanded = true)
    }
    _uiState.update { _uiState.value.copy(results = newResults) }
  }

  fun collapseAll() {
    val newResults = _uiState.value.results.toMutableList()
    for (i in newResults.indices) {
      newResults[i] =
        newResults[i].copy(expanded = false, statsExpanded = false, basicInfoExpanded = false)
    }
    _uiState.update { _uiState.value.copy(results = newResults) }
  }

  fun setAggregation(id: String, aggregation: Aggregation) {
    val newResults = _uiState.value.results.toMutableList()
    val index = newResults.indexOfFirst { it.id == id }
    if (index >= 0) {
      newResults[index] = newResults[index].copy(aggregation = aggregation)
      if (uiState.value.baselineResult?.id == newResults[index].id) {
        _uiState.update { _uiState.value.copy(baselineResult = newResults[index]) }
      }
    }
    _uiState.update { _uiState.value.copy(results = newResults) }
  }

  private fun calculateValueSeries(values: List<Double>): ValueSeries {
    if (values.isEmpty()) {
      return ValueSeries.getDefaultInstance()
    }

    val sortedValues = values.sorted()
    val size = sortedValues.size

    val min = sortedValues.first()
    val max = sortedValues.last()
    val avg = values.average()

    // Helper function to get the value at a specific percentile (0.0 to 1.0)
    fun getPercentile(p: Double): Double {
      if (size == 1) return sortedValues[0]
      val index = p * (size - 1)
      val lower = floor(index).toInt()
      val upper = ceil(index).toInt()
      if (lower == upper) {
        return sortedValues[lower]
      }
      val weight = index - lower
      return sortedValues[lower] * (1 - weight) + sortedValues[upper] * weight
    }

    val median = getPercentile(0.5)
    val pct25 = getPercentile(0.25)
    val pct75 = getPercentile(0.75)

    return ValueSeries.newBuilder()
      .addAllValue(values)
      .setMin(min)
      .setMax(max)
      .setAvg(avg)
      .setMedium(median) // Proto field is named 'medium'
      .setPct25(pct25)
      .setPct75(pct75)
      .build()
  }
}
