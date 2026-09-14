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

package com.google.ai.edge.gallery.ui.testchat

/** Role of a test chat message. */
enum class TestChatRole { USER, ASSISTANT }

/**
 * A single in-memory test chat message. Lives only while the test chat screen is open:
 * there is no persistence.
 */
data class TestChatMessage(
  val id: Long,
  val role: TestChatRole,
  val text: String,
)

/** Explicit state of the test chat screen. */
enum class TestChatStatus {
  INITIALIZING,
  READY,
  GENERATING,
  ERROR,
}

/**
 * Pure state of the test chat screen.
 *
 * [status] drives the input: Send is allowed only in [TestChatStatus.READY].
 * [streamingAssistantMessageId] identifies the single assistant placeholder message that
 * streaming updates keep rewriting.
 */
data class TestChatUiState(
  val status: TestChatStatus = TestChatStatus.INITIALIZING,
  val errorMessage: String = "",
  val messages: List<TestChatMessage> = emptyList(),
  val nextMessageId: Long = 1L,
  val streamingAssistantMessageId: Long? = null,
  /** Set when the user asked to stop; cleared when the runtime confirms completion. */
  val stopRequested: Boolean = false,
)

/** Initialization succeeded: unlock input. */
fun onInitializeSuccess(state: TestChatUiState): TestChatUiState =
  state.copy(status = TestChatStatus.READY, errorMessage = "")

/** Initialization failed: show the error; input stays locked. */
fun onInitializeError(state: TestChatUiState, error: String): TestChatUiState =
  state.copy(status = TestChatStatus.ERROR, errorMessage = error)

/**
 * Send: appends the user message and exactly one empty assistant placeholder, and
 * switches to [TestChatStatus.GENERATING].
 */
fun onSend(state: TestChatUiState, text: String): TestChatUiState {
  if (state.status != TestChatStatus.READY || text.isBlank()) {
    return state
  }
  val userMessage = TestChatMessage(id = state.nextMessageId, role = TestChatRole.USER, text = text)
  val assistantMessage =
    TestChatMessage(id = state.nextMessageId + 1, role = TestChatRole.ASSISTANT, text = "")
  return state.copy(
    status = TestChatStatus.GENERATING,
    messages = state.messages + userMessage + assistantMessage,
    nextMessageId = state.nextMessageId + 2,
    streamingAssistantMessageId = assistantMessage.id,
    stopRequested = false,
    errorMessage = "",
  )
}

/**
 * Streaming callback: the runtime delivers one incremental chunk per callback, so the
 * ViewModel accumulates chunks and rewrites the same assistant message with the full
 * response so far instead of creating new messages.
 */
fun onStreamingUpdate(state: TestChatUiState, accumulatedText: String): TestChatUiState {
  val id = state.streamingAssistantMessageId ?: return state
  return state.copy(
    messages =
      state.messages.map { if (it.id == id) it.copy(text = accumulatedText) else it },
  )
}

/**
 * Generation finished (done or cancelled): return to a usable [TestChatStatus.READY].
 */
fun onGenerationDone(state: TestChatUiState): TestChatUiState =
  state.copy(
    status = TestChatStatus.READY,
    streamingAssistantMessageId = null,
    stopRequested = false,
  )

/**
 * Generation failed: surface the error but keep the screen usable (input unlocked).
 */
fun onGenerationError(state: TestChatUiState, error: String): TestChatUiState =
  onGenerationDone(state).copy(errorMessage = error)

/**
 * Stop: only delegate cancellation to the runtime; the state stays GENERATING until the
 * runtime's done callback confirms.
 */
fun onStopRequested(state: TestChatUiState): TestChatUiState =
  if (state.status == TestChatStatus.GENERATING) state.copy(stopRequested = true) else state

/**
 * Reset: clears only the in-memory history. Does not change the status, so a failed or
 * still-initializing model is not unlocked by a reset.
 */
fun onReset(state: TestChatUiState): TestChatUiState =
  state.copy(
    messages = emptyList(),
    nextMessageId = 1L,
    streamingAssistantMessageId = null,
    stopRequested = false,
    errorMessage = "",
  )
