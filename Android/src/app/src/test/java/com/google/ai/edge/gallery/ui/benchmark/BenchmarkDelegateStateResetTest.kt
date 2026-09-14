package com.google.ai.edge.gallery.ui.benchmark

import com.google.ai.edge.gallery.systeminfo.NpuDelegateVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delegate-diagnostic state must never leak between benchmark runs. */
class BenchmarkDelegateStateResetTest {

  @Test
  fun freshRunState_clearsStaleVerdict() {
    // Previous run: a CPU fallback was classified.
    val stale =
      BenchmarkUiState(
        lastAccelerator = "npu",
        delegateVerdict = NpuDelegateVerdict.CPU_FALLBACK,
        npuValidated = false,
      )

    val fresh = stale.withFreshDelegateState(accelerator = "npu")

    assertEquals(NpuDelegateVerdict.UNKNOWN, fresh.delegateVerdict)
    assertFalse(fresh.npuValidated)
    assertEquals("npu", fresh.lastAccelerator)
  }

  @Test
  fun freshRunState_clearsStaleValidatedFlag() {
    // Previous run: fully validated — must not leak into the next run either.
    val stale =
      BenchmarkUiState(
        lastAccelerator = "npu",
        delegateVerdict = NpuDelegateVerdict.DISPATCH_DELEGATED,
        npuValidated = true,
      )

    val fresh = stale.withFreshDelegateState(accelerator = "npu")

    assertEquals(NpuDelegateVerdict.UNKNOWN, fresh.delegateVerdict)
    assertFalse(fresh.npuValidated)
  }

  @Test
  fun freshRunState_tracksCurrentAccelerator() {
    val stale =
      BenchmarkUiState(
        lastAccelerator = "npu",
        delegateVerdict = NpuDelegateVerdict.DISPATCH_DELEGATED,
        npuValidated = true,
      )

    val fresh = stale.withFreshDelegateState(accelerator = "cpu")

    assertFalse(fresh.npuValidated)
    assertEquals("cpu", fresh.lastAccelerator)
  }

  @Test
  fun persistedDecision_unchangedByStateReset() {
    // The state reset helper must not touch persistence policy.
    assertTrue(
      shouldPersistBenchmark("npu", NpuDelegateVerdict.DISPATCH_DELEGATED, true),
    )
    assertFalse(
      shouldPersistBenchmark("npu", NpuDelegateVerdict.UNKNOWN, true),
    )
  }
}
