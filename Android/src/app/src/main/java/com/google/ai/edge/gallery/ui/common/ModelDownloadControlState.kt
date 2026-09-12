package com.google.ai.edge.gallery.ui.common

import com.google.ai.edge.gallery.data.ModelDownloadStatusType

/** Which download control UI should be rendered for a model. */
enum class ModelDownloadControlState {
  /** Render the Download (or retry) button. */
  DOWNLOAD,

  /** Render the progress UI (percent bar + stop control). */
  PROGRESS,

  /** Render nothing: the model is fully downloaded or never needs a download. */
  NONE,
}

/**
 * Resolves the download control UI state from explicit inputs.
 *
 * Precedence: a `SUCCEEDED` download always renders nothing (no button, no stale
 * progress bar); an in-flight transient (button clicked / token check running) renders
 * progress; then the download status decides: `IN_PROGRESS`, `PARTIALLY_DOWNLOADED` and
 * `UNZIPPING` render progress, `NOT_DOWNLOADED` and `FAILED` render the download
 * (retry) button, and models that never need a download render nothing.
 */
fun modelDownloadControlState(
  status: ModelDownloadStatusType?,
  downloadStarted: Boolean,
  checkingToken: Boolean,
  requiresDownload: Boolean,
): ModelDownloadControlState =
  when {
    status == ModelDownloadStatusType.SUCCEEDED -> ModelDownloadControlState.NONE
    downloadStarted || checkingToken -> ModelDownloadControlState.PROGRESS
    status == ModelDownloadStatusType.UNZIPPING ||
      status == ModelDownloadStatusType.IN_PROGRESS ||
      status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED -> ModelDownloadControlState.PROGRESS
    status == ModelDownloadStatusType.NOT_DOWNLOADED ||
      status == ModelDownloadStatusType.FAILED ->
      if (requiresDownload) ModelDownloadControlState.DOWNLOAD else ModelDownloadControlState.NONE
    else -> if (requiresDownload) ModelDownloadControlState.DOWNLOAD else ModelDownloadControlState.NONE
  }
