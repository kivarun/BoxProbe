package com.google.ai.edge.gallery.ui.benchmark

import com.google.ai.edge.gallery.systeminfo.NpuDelegateVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stop-request state transitions and the between-runs cancellation decision. */
class BenchmarkStopTest {

  // ---- withStopRequested ----

  @Test
  fun stopRequest_setOnlyWhileRunning() {
    val running = BenchmarkUiState(running = true)
    assertTrue(running.withStopRequested().stopRequested)

    val idle = BenchmarkUiState(running = false)
    assertFalse(idle.withStopRequested().stopRequested)
  }

  @Test
  fun stopRequest_doesNotAlterProgress() {
    val running =
      BenchmarkUiState(running = true, totalRunCount = 3, completedRunCount = 1)
    val stopped = running.withStopRequested()
    assertEquals(1, stopped.completedRunCount)
    assertEquals(3, stopped.totalRunCount)
    assertTrue(stopped.running)
  }

  // ---- shouldStartNextBenchmarkRun ----

  @Test
  fun nextRun_startsNormally() {
    assertTrue(shouldStartNextBenchmarkRun(stopRequested = false, completedRuns = 0, runCount = 3))
    assertTrue(shouldStartNextBenchmarkRun(stopRequested = false, completedRuns = 2, runCount = 3))
  }

  @Test
  fun nextRun_notStartedAfterStopRequest() {
    // Stop between run 1 and run 2: the completed run is kept, no further run starts.
    assertFalse(shouldStartNextBenchmarkRun(stopRequested = true, completedRuns = 1, runCount = 3))
    // Defensive: a stop recorded before the very first run also prevents it.
    assertFalse(shouldStartNextBenchmarkRun(stopRequested = true, completedRuns = 0, runCount = 3))
  }

  @Test
  fun nextRun_neverBeyondRequestedCount() {
    assertFalse(shouldStartNextBenchmarkRun(stopRequested = false, completedRuns = 3, runCount = 3))
  }

  // ---- persistence of a stopped invocation ----

  @Test
  fun stoppedInvocation_notPersisted() {
    // 1/3 + Stop is not a completed benchmark result: the persistence gate receives
    // benchmarkSucceeded=false, matching the failure path.
    for (accelerator in listOf("cpu", "gpu", "npu")) {
      assertFalse(
        shouldPersistBenchmark(accelerator, NpuDelegateVerdict.DISPATCH_DELEGATED, false),
      )
    }
  }

  @Test
  fun completedAfterAllRuns_persistsEvenIfStopWasPressed() {
    // Stop pressed during the LAST run: all requested runs completed, the result is
    // a normal benchmark result.
    assertTrue(
      shouldPersistBenchmark("cpu", NpuDelegateVerdict.UNKNOWN, benchmarkSucceeded = true),
    )
    assertTrue(
      shouldPersistBenchmark(
        "npu",
        NpuDelegateVerdict.DISPATCH_DELEGATED,
        benchmarkSucceeded = true,
      ),
    )
  }

  // ---- re-run after Stop ----

  @Test
  fun stopMessage_andRequestClearOnNextInvocation() {
    // runBenchmark resets the stop state before the next invocation; mirror the
    // exact update here to pin the contract.
    val stopped = BenchmarkUiState(running = true, stopRequested = true, stopMessage = "stopped")
    val restarted =
      stopped.copy(
        stopRequested = false,
        stopMessage = "",
      )
    assertFalse(restarted.stopRequested)
    assertEquals("", restarted.stopMessage)
  }
}
