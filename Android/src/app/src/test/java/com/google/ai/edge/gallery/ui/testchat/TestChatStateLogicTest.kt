package com.google.ai.edge.gallery.ui.testchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestChatStateLogicTest {

  private val ready = TestChatUiState(status = TestChatStatus.READY)

  @Test
  fun initializeSuccess_unlocksInput() {
    val state = onInitializeSuccess(TestChatUiState())
    assertEquals(TestChatStatus.READY, state.status)
    assertEquals("", state.errorMessage)
  }

  @Test
  fun initializeError_showsErrorAndBlocksInput() {
    val state = onInitializeError(TestChatUiState(), "engine exploded")
    assertEquals(TestChatStatus.ERROR, state.status)
    assertEquals("engine exploded", state.errorMessage)
    assertEquals(
      "Send must be a no-op while not READY",
      state,
      onSend(state, "hello"),
    )
  }

  @Test
  fun send_appendsUserAndSingleAssistantPlaceholder() {
    val state = onSend(ready, "hello")
    assertEquals(TestChatStatus.GENERATING, state.status)
    assertEquals(2, state.messages.size)
    assertEquals(TestChatRole.USER, state.messages[0].role)
    assertEquals("hello", state.messages[0].text)
    assertEquals(TestChatRole.ASSISTANT, state.messages[1].role)
    assertEquals("", state.messages[1].text)
    assertEquals(state.messages[1].id, state.streamingAssistantMessageId)
  }

  @Test
  fun send_rejectedWhenGeneratingOrBlank() {
    val generating = onSend(ready, "first")
    assertEquals(generating, onSend(generating, "second"))
    assertEquals(ready, onSend(ready, "   "))
  }

  @Test
  fun streamingUpdates_rewriteSameAssistantMessage() {
    var state = onSend(ready, "hello")
    val assistantId = state.streamingAssistantMessageId!!

    state = onStreamingUpdate(state, "Hel")
    state = onStreamingUpdate(state, "Hello")

    assertEquals(2, state.messages.size)
    assertEquals(assistantId, state.messages[1].id)
    assertEquals("Hello", state.messages[1].text)
    assertEquals(TestChatStatus.GENERATING, state.status)
  }

  @Test
  fun streamingUpdate_ignoredWithoutActiveAssistant() {
    assertEquals(ready, onStreamingUpdate(ready, "late chunk"))
  }

  @Test
  fun completion_returnsReady() {
    val state = onGenerationDone(onSend(ready, "hello"))
    assertEquals(TestChatStatus.READY, state.status)
    assertNull(state.streamingAssistantMessageId)
    assertFalse(state.stopRequested)
    assertEquals(2, state.messages.size)
  }

  @Test
  fun generationError_returnsUsableStateAndKeepsPartialText() {
    var state = onSend(ready, "hello")
    state = onStreamingUpdate(state, "partial ")
    state = onGenerationError(state, "boom")

    assertEquals(TestChatStatus.READY, state.status)
    assertEquals("boom", state.errorMessage)
    assertNull(state.streamingAssistantMessageId)
    assertEquals("partial ", state.messages[1].text)
  }

  @Test
  fun stop_keepsGeneratingAndFlagsStopRequested() {
    val state = onStopRequested(onSend(ready, "hello"))
    assertEquals(TestChatStatus.GENERATING, state.status)
    assertTrue(state.stopRequested)
    assertEquals(2, state.messages.size)
    assertEquals(ready, onStopRequested(ready))
  }

  @Test
  fun stop_isClearedWhenGenerationConfirmsDone() {
    val stopped = onStopRequested(onSend(ready, "hello"))
    val done = onGenerationDone(stopped)
    assertEquals(TestChatStatus.READY, done.status)
    assertFalse(done.stopRequested)
    // History is preserved; the empty assistant placeholder remains.
    assertEquals(2, done.messages.size)
    assertEquals("", done.messages[1].text)
  }

  @Test
  fun reset_clearsMessagesOnly() {
    var state = onSend(ready, "hello")
    state = onStreamingUpdate(state, "partial")
    state = onGenerationDone(state)
    state = onReset(state)

    assertEquals(TestChatStatus.READY, state.status)
    assertTrue(state.messages.isEmpty())
    assertEquals(1L, state.nextMessageId)
    assertNull(state.streamingAssistantMessageId)
    assertFalse(state.stopRequested)
    assertEquals("", state.errorMessage)
  }

  @Test
  fun reset_doesNotUnlockNonReadyStates() {
    assertEquals(
      TestChatStatus.INITIALIZING,
      onReset(TestChatUiState()).status,
    )
    assertEquals(
      TestChatStatus.ERROR,
      onReset(onInitializeError(TestChatUiState(), "boom")).status,
    )
  }
}
