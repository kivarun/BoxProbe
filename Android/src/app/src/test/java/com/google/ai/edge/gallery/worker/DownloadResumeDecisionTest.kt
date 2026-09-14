package com.google.ai.edge.gallery.worker

import java.net.HttpURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for the resume decision logic in [DownloadWorker]'s download loop. */
@RunWith(JUnit4::class)
class DownloadResumeDecisionTest {

  @Test
  fun cleanStartWithEmptyTmp_resumesWithoutRestart() {
    assertEquals(
      ResumeDecision.RESUME,
      decideResume(
        responseCode = HttpURLConnection.HTTP_OK,
        rangeRequested = false,
        contentRange = null,
        tmpFileSize = 0,
        contentLength = 1000,
      ),
    )
  }

  @Test
  fun serverHonorsRange_resumes() {
    assertEquals(
      ResumeDecision.RESUME,
      decideResume(
        responseCode = HttpURLConnection.HTTP_PARTIAL,
        rangeRequested = true,
        contentRange = "bytes 500-999/1000",
        tmpFileSize = 500,
        contentLength = 0,
      ),
    )
  }

  @Test
  fun serverIgnoresRangeAndReturnsOk_restartsFromZero() {
    // Regression: appending a full 200-response onto a partial file corrupts it.
    assertEquals(
      ResumeDecision.RESTART_FROM_ZERO,
      decideResume(
        responseCode = HttpURLConnection.HTTP_OK,
        rangeRequested = true,
        contentRange = null,
        tmpFileSize = 3446,
        contentLength = 1033814016,
      ),
    )
  }

  @Test
  fun partialStartMismatchedWithTmp_restartsFromZero() {
    // Regression: two workers racing on the same tmp file must not overlap-append.
    assertEquals(
      ResumeDecision.RESTART_FROM_ZERO,
      decideResume(
        responseCode = HttpURLConnection.HTTP_PARTIAL,
        rangeRequested = true,
        contentRange = "bytes 589121629-1033814015/1033814016",
        tmpFileSize = 589113437,
        contentLength = 0,
      ),
    )
  }

  @Test
  fun tmpAlreadyComplete_returnsFinalize() {
    assertEquals(
      ResumeDecision.FINALIZE,
      decideResume(
        responseCode = HttpURLConnection.HTTP_PARTIAL,
        rangeRequested = true,
        contentRange = "bytes 1000-999/1000",
        tmpFileSize = 1000,
        contentLength = 0,
      ),
    )
  }

  @Test
  fun rangeNotSatisfiableWithCompleteTmp_finalizes() {
    assertEquals(
      ResumeDecision.FINALIZE,
      decideResume(
        responseCode = 416,
        rangeRequested = true,
        contentRange = "bytes 1000-999/1000",
        tmpFileSize = 1000,
        contentLength = 0,
      ),
    )
  }

  @Test
  fun rangeNotSatisfiableWithIncompleteTmp_restarts() {
    assertEquals(
      ResumeDecision.RESTART_FROM_ZERO,
      decideResume(
        responseCode = 416,
        rangeRequested = true,
        contentRange = "bytes 1000-999/1000",
        tmpFileSize = 500,
        contentLength = 0,
      ),
    )
  }

  @Test
  fun malformedContentRange_restartsFromZero() {
    assertEquals(
      ResumeDecision.RESTART_FROM_ZERO,
      decideResume(
        responseCode = HttpURLConnection.HTTP_PARTIAL,
        rangeRequested = true,
        contentRange = "garbage",
        tmpFileSize = 500,
        contentLength = 0,
      ),
    )
  }

  @Test
  fun parseContentRangeHandlesValidAndInvalid() {
    assertEquals(Pair(0L, 999L), parseContentRange("bytes 0-999/1000"))
    assertEquals(Pair(589113437L, 1033814015L), parseContentRange("bytes 589113437-1033814015/1033814016"))
    assertNull(parseContentRange(null))
    assertNull(parseContentRange("bytes -/"))
  }
}
