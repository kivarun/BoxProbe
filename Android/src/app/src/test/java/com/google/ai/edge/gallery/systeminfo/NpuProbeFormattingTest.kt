package com.google.ai.edge.gallery.systeminfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NpuProbeFormattingTest {

  @org.junit.Test
  fun handshakeParse_mapsInterfaceKeyToPresentFlags() {
    val parsed =
      parseNpuDispatchHandshakeJson(
        "{\"status\":\"OK\",\"major\":0,\"minor\":1,\"patch\":0,\"interface\":true," +
          "\"async\":false,\"graph\":false,\"errorCode\":0}",
      )
    assertEquals(true, parsed?.interfacePresent)
    assertEquals(false, parsed?.asyncPresent)
    assertEquals(false, parsed?.graphPresent)
    assertEquals("0.1.0", "${parsed?.major}.${parsed?.minor}.${parsed?.patch}")
  }

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
    stoppedAfterStage: NpuProbeStage? = null,
  ) = NpuProbeResult(
    precheck = null,
    stageResults = stages,
    failedStage = failedStage,
    stoppedAfterStage = stoppedAfterStage,
    totalDurationMs = 150L,
  )

  @Test
  fun stageOrder_matchesSpec() {
    assertEquals(
      listOf(
        "PRECHECK",
        "LITERT_CORE_LIBRARY_LOAD",
        "DISPATCH_LIBRARY_LOAD",
        "DISPATCH_API_HANDSHAKE",
        "DISPATCH_INITIALIZE",
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
        stages = listOf(passedStage(NpuProbeStage.PRECHECK), failedStage(NpuProbeStage.DISPATCH_LIBRARY_LOAD)),
        failedStage = NpuProbeStage.DISPATCH_LIBRARY_LOAD,
      )
    assertEquals(
      "Failed at DISPATCH_LIBRARY_LOAD",
      npuProbeRuntimeValidatedLabel(NpuProbeStatus.INITIALIZATION_FAILED, probeResult),
    )
  }

  @Test
  fun runtimeValidatedLabel_diagnosticStopAfterDispatchLoad_showsDispatchLoaded() {
    val probeResult =
      result(
        stages = listOf(passedStage(NpuProbeStage.PRECHECK), passedStage(NpuProbeStage.DISPATCH_LIBRARY_LOAD)),
        failedStage = null,
        stoppedAfterStage = NpuProbeStage.DISPATCH_LIBRARY_LOAD,
      )
    assertEquals(
      "Core + dispatch loaded",
      npuProbeRuntimeValidatedLabel(NpuProbeStatus.INITIALIZATION_PASSED, probeResult),
    )
  }

  @Test
  fun runtimeValidatedLabel_coreLoadFailure_stopsBeforeDispatch() {
    val probeResult =
      result(
        stages = listOf(passedStage(NpuProbeStage.PRECHECK), failedStage(NpuProbeStage.LITERT_CORE_LIBRARY_LOAD)),
        failedStage = NpuProbeStage.LITERT_CORE_LIBRARY_LOAD,
        stoppedAfterStage = NpuProbeStage.LITERT_CORE_LIBRARY_LOAD,
      )
    assertEquals(
      "Failed at LITERT_CORE_LIBRARY_LOAD",
      npuProbeRuntimeValidatedLabel(NpuProbeStatus.INITIALIZATION_FAILED, probeResult),
    )
  }

  @Test
  fun runtimeValidatedLabel_diagnosticStopAfterHandshake_showsHandshake() {
    val probeResult =
      result(
        stages =
          listOf(
            passedStage(NpuProbeStage.PRECHECK),
            passedStage(NpuProbeStage.DISPATCH_LIBRARY_LOAD),
            passedStage(NpuProbeStage.DISPATCH_API_HANDSHAKE),
          ),
        failedStage = null,
        stoppedAfterStage = NpuProbeStage.DISPATCH_API_HANDSHAKE,
      )
    assertEquals(
      "Core + dispatch + API handshake",
      npuProbeRuntimeValidatedLabel(NpuProbeStatus.INITIALIZATION_PASSED, probeResult),
    )
  }

  @Test
  fun handshakeRows_showVersionAndInterfacePresence() {
    val rows =
      npuProbeHandshakeRows(
        NpuDispatchHandshakeResult(
          status = "OK",
          major = 0,
          minor = 1,
          patch = 0,
          interfacePresent = true,
          asyncPresent = false,
          graphPresent = false,
        ),
      )
    assertEquals(
      listOf(
        "Dispatch API status" to "OK",
        "API version" to "0.1.0",
        "Basic interface" to "present",
        "Async interface" to "absent",
        "Graph interface" to "absent",
      ),
      rows,
    )
  }

  @Test
  fun handshakeRows_includeRawErrorWhenPresent() {
    val rows =
      npuProbeHandshakeRows(
        NpuDispatchHandshakeResult(
          status = "ERROR",
          error = "dlopen failed: nope",
        ),
      )
    assertEquals(6, rows.size)
    assertEquals("Handshake raw error" to "dlopen failed: nope", rows.last())
  }

  @Test
  fun initializeRows_okShowsOk() {
    val rows =
      npuProbeInitializeRows(
        NpuDispatchInitializeResult(status = "OK", initStatus = 0, statusString = "OK"),
      )
    assertEquals(listOf("Dispatch initialize" to "OK"), rows)
  }

  @Test
  fun initializeRows_failureCarriesStatusAndRawError() {
    val rows =
      npuProbeInitializeRows(
        NpuDispatchInitializeResult(
          status = "ERROR",
          initStatus = 14,
          statusString = "RuntimeFailure",
          error = "adapter not found",
        ),
      )
    assertEquals(
      listOf(
        "Dispatch initialize" to "ERROR — 14 — RuntimeFailure",
        "Initialize raw error" to "adapter not found",
      ),
      rows,
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
