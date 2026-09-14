package com.google.ai.edge.gallery.ui.benchmark

import com.google.ai.edge.gallery.systeminfo.NpuDelegateVerdict
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence gate for benchmark results: an NPU run must never enter the
 * persistent history unless the delegate classifier proved DispatchDelegate
 * delegation.
 */
class BenchmarkPersistenceDecisionTest {

  @Test
  fun cpuSuccess_persists() {
    assertTrue(
      shouldPersistBenchmark("cpu", NpuDelegateVerdict.UNKNOWN, benchmarkSucceeded = true),
    )
  }

  @Test
  fun gpuSuccess_persists() {
    assertTrue(
      shouldPersistBenchmark("gpu", NpuDelegateVerdict.UNKNOWN, benchmarkSucceeded = true),
    )
  }

  @Test
  fun npuValidated_persists() {
    assertTrue(
      shouldPersistBenchmark(
        "npu",
        NpuDelegateVerdict.DISPATCH_DELEGATED,
        benchmarkSucceeded = true,
      ),
    )
  }

  @Test
  fun npuCpuFallback_notPersisted() {
    assertFalse(
      shouldPersistBenchmark("npu", NpuDelegateVerdict.CPU_FALLBACK, benchmarkSucceeded = true),
    )
  }

  @Test
  fun npuUnknown_notPersisted() {
    assertFalse(
      shouldPersistBenchmark("npu", NpuDelegateVerdict.UNKNOWN, benchmarkSucceeded = true),
    )
  }

  @Test
  fun failedBenchmark_neverPersists() {
    for (accelerator in listOf("cpu", "gpu", "npu")) {
      for (verdict in NpuDelegateVerdict.values()) {
        assertFalse(
          "a failed $accelerator run must not persist",
          shouldPersistBenchmark(accelerator, verdict, benchmarkSucceeded = false),
        )
      }
    }
  }

  @Test
  fun acceleratorCaseInsensitive() {
    assertTrue(
      shouldPersistBenchmark("NPU", NpuDelegateVerdict.DISPATCH_DELEGATED, true),
    )
    assertFalse(
      shouldPersistBenchmark("NPU", NpuDelegateVerdict.UNKNOWN, true),
    )
  }
}
