package com.google.ai.edge.gallery.worker

import java.net.HttpURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runners.JUnit4
import org.junit.runner.RunWith

/** Tests for the resume decision logic in [DownloadWorker]'s download loop. */
@RunWith(JUnit4::class)
class DownloadResumeDecisionTest {

  // ---- parseContentRange ----

  @Test
  fun parse_fullRange() {
    assertEquals(
      ParsedContentRange(start = 500, end = 999, total = 1000),
      parseContentRange("bytes 500-999/1000"),
    )
  }

  @Test
  fun parse_unsatisfiableRange() {
    assertEquals(
      ParsedContentRange(start = null, end = null, total = 1000),
      parseContentRange("bytes */1000"),
    )
  }

  @Test
  fun parse_unknownTotal() {
    assertEquals(
      ParsedContentRange(start = 500, end = 999, total = null),
      parseContentRange("bytes 500-999/*"),
    )
  }

  @Test
  fun parse_malformed_returnsNull() {
    assertNull(parseContentRange(null))
    assertNull(parseContentRange("bytes -/"))
    assertNull(parseContentRange("garbage"))
    assertNull(parseContentRange("bytes 500-999"))
    assertNull(parseContentRange("bytes abc-def/1000"))
  }

  // ---- decideResume: 206 ----

  @Test
  fun serverHonorsRange_resumes() {
    assertEquals(
      ResumeDecision.RESUME,
      decideResume(
        responseCode = HttpURLConnection.HTTP_PARTIAL,
        rangeRequested = true,
        contentRange = "bytes 500-999/1000",
        tmpFileSize = 500,
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
      ),
    )
  }

  @Test
  fun partialWithoutStartOrEnd_restarts() {
    assertEquals(
      ResumeDecision.RESTART_FROM_ZERO,
      decideResume(
        responseCode = HttpURLConnection.HTTP_PARTIAL,
        rangeRequested = true,
        contentRange = "bytes */1000",
        tmpFileSize = 500,
      ),
    )
  }

  @Test
  fun partialMalformedContentRange_restarts() {
    assertEquals(
      ResumeDecision.RESTART_FROM_ZERO,
      decideResume(
        responseCode = HttpURLConnection.HTTP_PARTIAL,
        rangeRequested = true,
        contentRange = "garbage",
        tmpFileSize = 500,
      ),
    )
  }

  // ---- decideResume: 200 ----

  @Test
  fun cleanStartWithEmptyTmp_resumesWithoutRestart() {
    assertEquals(
      ResumeDecision.RESUME,
      decideResume(
        responseCode = HttpURLConnection.HTTP_OK,
        rangeRequested = false,
        contentRange = null,
        tmpFileSize = 0,
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
      ),
    )
  }

  // ---- decideResume: 416 ----

  @Test
  fun rangeNotSatisfiableWithCompleteTmp_finalizes() {
    assertEquals(
      ResumeDecision.FINALIZE,
      decideResume(
        responseCode = 416,
        rangeRequested = true,
        contentRange = "bytes */1000",
        tmpFileSize = 1000,
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
        contentRange = "bytes */1000",
        tmpFileSize = 500,
      ),
    )
  }

  @Test
  fun rangeNotSatisfiableWithoutContentRange_restarts() {
    // Without a parseable `bytes */TOTAL` there is no proof the tmp file is complete;
    // Content-Length is never substituted for the Content-Range total.
    assertEquals(
      ResumeDecision.RESTART_FROM_ZERO,
      decideResume(
        responseCode = 416,
        rangeRequested = true,
        contentRange = null,
        tmpFileSize = 1000,
      ),
    )
    assertEquals(
      ResumeDecision.RESTART_FROM_ZERO,
      decideResume(
        responseCode = 416,
        rangeRequested = true,
        contentRange = "garbage",
        tmpFileSize = 1000,
      ),
    )
  }

  @Test
  fun fullRange416Format_isNotAThing_neverFinalizes() {
    // The fake `bytes 1000-999/1000` shape must not be treated as a complete-range
    // marker for 416: only `bytes */TOTAL` carries an unsatisfiable range.
    assertEquals(
      ResumeDecision.RESTART_FROM_ZERO,
      decideResume(
        responseCode = 416,
        rangeRequested = true,
        contentRange = "bytes 1000-999/1000",
        tmpFileSize = 1000,
      ),
    )
  }

  // ---- decideResume: other codes ----

  @Test
  fun unexpectedResponseCode_restarts() {
    assertEquals(
      ResumeDecision.RESTART_FROM_ZERO,
      decideResume(
        responseCode = HttpURLConnection.HTTP_CREATED,
        rangeRequested = false,
        contentRange = null,
        tmpFileSize = 0,
      ),
    )
  }
}
