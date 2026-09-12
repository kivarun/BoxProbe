package com.google.ai.edge.gallery.systeminfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NpuProbeFormattingTest {

  private fun passedStage(stage: NpuProbeStage, durationMs: Long = 10L) =
    NpuProbeStageResult(
      stage = stage,
      durationMs = durationMs,
      passed = true,
      exceptionClass = null,
      exceptionMessage = null,
    )

  private fun failedStage(
    stage: NpuProbeStage,
    durationMs: Long = 20L,
    exceptionClass: String = "java.lang.IllegalStateException",
    exceptionMessage: String = "boom",
  ) =
    NpuProbeStageResult(
      stage = stage,
      durationMs = durationMs,
      passed = false,
      exceptionClass = exceptionClass,
      exceptionMessage = exceptionMessage,
    )

  private fun result(
    stages: List<NpuProbeStageResult>,
    failedStage: NpuProbeStage?,
  ) = NpuProbeResult(
    precheck = null,
    stageResults = stages,
    failedStage = failedStage,
    totalDurationMs = 150L,
  )

  @Test
  fun stageOrder_matchesSpec() {
    assertEquals(
      listOf(
        "PRECHECK",
        "BACKEND_CREATED",
        "ENGINE_CREATED",
        "ENGINE_INITIALIZED",
        "CONVERSATION_CREATED",
        "SUCCESS",
      ),
      NpuProbeStage.values().map { it.name },
    )
  }

  @Test
  fun statusLabel_coversAllStatuses() {
    assertEquals("Not probed", npuProbeStatusLabel(NpuProbeStatus.NOT_PROBED))
    assertEquals("Running…", npuProbeStatusLabel(NpuProbeStatus.RUNNING))
    assertEquals(
      "Initialization passed",
      npuProbeStatusLabel(NpuProbeStatus.INITIALIZATION_PASSED),
    )
    assertEquals(
      "Initialization failed",
      npuProbeStatusLabel(NpuProbeStatus.INITIALIZATION_FAILED),
    )
    assertEquals(
      "No downloaded NPU-compatible LiteRT model",
      npuProbeStatusLabel(NpuProbeStatus.NO_MODEL),
    )
  }

  @Test
  fun runtimeValidatedLabel_beforeRun() {
    assertEquals("Not probed", npuProbeRuntimeValidatedLabel(NpuProbeStatus.NOT_PROBED, null))
  }

  @Test
  fun runtimeValidatedLabel_passedProbe() {
    assertEquals(
      "Initialization passed",
      npuProbeRuntimeValidatedLabel(NpuProbeStatus.INITIALIZATION_PASSED, null),
    )
  }

  @Test
  fun runtimeValidatedLabel_failedProbeNamesStage() {
    val probeResult =
      result(
        stages = listOf(passedStage(NpuProbeStage.PRECHECK), failedStage(NpuProbeStage.ENGINE_INITIALIZED)),
        failedStage = NpuProbeStage.ENGINE_INITIALIZED,
      )
    assertEquals(
      "Failed at ENGINE_INITIALIZED",
      npuProbeRuntimeValidatedLabel(NpuProbeStatus.INITIALIZATION_FAILED, probeResult),
    )
  }

  @Test
  fun runtimeValidatedLabel_failedProbeWithoutResult_fallsBack() {
    assertEquals(
      "Initialization failed",
      npuProbeRuntimeValidatedLabel(NpuProbeStatus.INITIALIZATION_FAILED, null),
    )
  }

  @Test
  fun failedStageLabel_nullWhenProbePassed() {
    assertNull(
      npuProbeFailedStageLabel(
        result(stages = listOf(passedStage(NpuProbeStage.PRECHECK)), failedStage = null),
      ),
    )
  }

  @Test
  fun failedStageLabel_containsStageName() {
    val probeResult =
      result(
        stages = listOf(failedStage(NpuProbeStage.CONVERSATION_CREATED)),
        failedStage = NpuProbeStage.CONVERSATION_CREATED,
      )
    assertEquals("Failed at CONVERSATION_CREATED", npuProbeFailedStageLabel(probeResult))
  }

  @Test
  fun stageLabel_passedStageIncludesDuration() {
    assertEquals("passed in 120 ms", npuProbeStageLabel(passedStage(NpuProbeStage.PRECHECK, 120L)))
  }

  @Test
  fun stageLabel_failedStageIncludesExceptionClassAndMessage() {
    assertEquals(
      "failed in 30 ms — java.lang.IllegalStateException: boom",
      npuProbeStageLabel(failedStage(NpuProbeStage.ENGINE_CREATED, 30L)),
    )
  }

  @Test
  fun durationFormat_msBelowSecond_secondsAbove() {
    assertEquals("999 ms", npuProbeFormatDuration(999L))
    assertEquals("1.00 s", npuProbeFormatDuration(1000L))
    assertEquals("12.50 s", npuProbeFormatDuration(12500L))
  }
}
