package com.google.ai.edge.gallery.ui.common

import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadControlStateTest {

  @Test
  fun succeeded_neverShowsProgressOrButton() {
    for (downloadStarted in listOf(false, true)) {
      for (checkingToken in listOf(false, true)) {
        assertEquals(
          "SUCCEEDED with downloadStarted=$downloadStarted checkingToken=$checkingToken",
          ModelDownloadControlState.NONE,
          modelDownloadControlState(
            status = ModelDownloadStatusType.SUCCEEDED,
            downloadStarted = downloadStarted,
            checkingToken = checkingToken,
            requiresDownload = true,
          ),
        )
      }
    }
  }

  @Test
  fun inProgress_showsProgress() {
    assertEquals(
      ModelDownloadControlState.PROGRESS,
      modelDownloadControlState(
        status = ModelDownloadStatusType.IN_PROGRESS,
        downloadStarted = false,
        checkingToken = false,
        requiresDownload = true,
      ),
    )
  }

  @Test
  fun partiallyDownloaded_showsProgress() {
    assertEquals(
      ModelDownloadControlState.PROGRESS,
      modelDownloadControlState(
        status = ModelDownloadStatusType.PARTIALLY_DOWNLOADED,
        downloadStarted = false,
        checkingToken = false,
        requiresDownload = true,
      ),
    )
  }

  @Test
  fun unzipping_showsProgress() {
    assertEquals(
      ModelDownloadControlState.PROGRESS,
      modelDownloadControlState(
        status = ModelDownloadStatusType.UNZIPPING,
        downloadStarted = false,
        checkingToken = false,
        requiresDownload = true,
      ),
    )
  }

  @Test
  fun notDownloaded_showsDownload() {
    assertEquals(
      ModelDownloadControlState.DOWNLOAD,
      modelDownloadControlState(
        status = ModelDownloadStatusType.NOT_DOWNLOADED,
        downloadStarted = false,
        checkingToken = false,
        requiresDownload = true,
      ),
    )
  }

  @Test
  fun failed_showsDownloadRetry() {
    assertEquals(
      ModelDownloadControlState.DOWNLOAD,
      modelDownloadControlState(
        status = ModelDownloadStatusType.FAILED,
        downloadStarted = false,
        checkingToken = false,
        requiresDownload = true,
      ),
    )
  }

  @Test
  fun checkingToken_showsProgress() {
    assertEquals(
      ModelDownloadControlState.PROGRESS,
      modelDownloadControlState(
        status = ModelDownloadStatusType.NOT_DOWNLOADED,
        downloadStarted = false,
        checkingToken = true,
        requiresDownload = true,
      ),
    )
  }

  @Test
  fun buttonClickedBeforeFirstProgress_showsProgress() {
    assertEquals(
      ModelDownloadControlState.PROGRESS,
      modelDownloadControlState(
        status = ModelDownloadStatusType.NOT_DOWNLOADED,
        downloadStarted = true,
        checkingToken = false,
        requiresDownload = true,
      ),
    )
  }

  @Test
  fun noStatusRecord_showsDownloadWhenRequired() {
    assertEquals(
      ModelDownloadControlState.DOWNLOAD,
      modelDownloadControlState(
        status = null,
        downloadStarted = false,
        checkingToken = false,
        requiresDownload = true,
      ),
    )
    assertEquals(
      ModelDownloadControlState.NONE,
      modelDownloadControlState(
        status = null,
        downloadStarted = false,
        checkingToken = false,
        requiresDownload = false,
      ),
    )
  }

  @Test
  fun modelNeverRequiresDownload_rendersNothing() {
    assertEquals(
      ModelDownloadControlState.NONE,
      modelDownloadControlState(
        status = ModelDownloadStatusType.NOT_DOWNLOADED,
        downloadStarted = false,
        checkingToken = false,
        requiresDownload = false,
      ),
    )
    assertEquals(
      ModelDownloadControlState.NONE,
      modelDownloadControlState(
        status = ModelDownloadStatusType.FAILED,
        downloadStarted = false,
        checkingToken = false,
        requiresDownload = false,
      ),
    )
  }
}
