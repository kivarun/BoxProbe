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

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "AGTestChatVM"

/**
 * Minimal ViewModel for the text-only LLM test chat.
 *
 * This is a test frontend over the existing production runtime lifecycle: it owns the
 * model instance only while the test chat screen is open and delegates all Engine /
 * Conversation work to [Model.runtimeHelper] (i.e. [com.google.ai.edge.gallery.runtime.
 * LlmChatModelHelper] for LiteRT-LM models). No engine construction, persistence or
 * fallback backends live here.
 */
@HiltViewModel
class TestChatViewModel
@Inject
constructor(@ApplicationContext private val context: Context) : ViewModel() {

  private val _uiState = MutableStateFlow(TestChatUiState())
  val uiState = _uiState.asStateFlow()

  private var model: Model? = null
  private var started = false
  private var disposed = false

  /**
   * Accumulated response text of the current streaming turn. The runtime callback
   * delivers one incremental chunk per message (not the full response so far), so the
   * ViewModel owns the accumulation; the state only ever receives the full text.
   */
  private var streamingAccumulatedText = StringBuilder()

  /**
   * Set when a turn finished on the current runtime conversation. LiteRT-LM 0.12 keeps
   * routing the per-token callbacks of later turns of the same conversation to the
   * first turn's listener, so those turns complete with empty chunks; recreating the
   * conversation between turns restores correct chunk delivery (multi-turn runtime
   * context is sacrificed, which is acceptable for a smoke test chat).
   */
  private var needsRuntimeConversationReset = false

  /**
   * Enters the chat: verifies the model is available locally and initializes it through
   * the production lifecycle. A no-op when the chat is already started for this model.
   */
  fun start(model: Model, availableLocally: Boolean) {
    if (started) {
      return
    }
    started = true
    if (!availableLocally) {
      _uiState.value = onInitializeError(_uiState.value, "Model is not available locally")
      return
    }
    this.model = model
    viewModelScope.launch(Dispatchers.Default) {
      Log.d(TAG, "Initializing model '${model.name}' for test chat...")
      model.runtimeHelper.initialize(
        context = context,
        model = model,
        supportImage = false,
        supportAudio = false,
        onDone = { error ->
          if (disposed) {
            Log.d(TAG, "Test chat left during init; cleaning up model '${model.name}'")
            model.runtimeHelper.cleanUp(model = model, onDone = {})
            return@initialize
          }
          if (error.isNotEmpty() || model.instance == null) {
            Log.e(TAG, "Test chat init failed for '${model.name}': $error")
            _uiState.update { onInitializeError(it, error.ifEmpty { "Model initialization failed" }) }
          } else {
            Log.d(TAG, "Test chat model '${model.name}' initialized")
            _uiState.update { onInitializeSuccess(it) }
          }
        },
      )
    }
  }

  /** Appends the user message plus one assistant placeholder and starts generation. */
  fun send(text: String) {
    val curModel = model ?: return
    if (_uiState.value.status != TestChatStatus.READY) {
      return
    }
    _uiState.update { onSend(it, text) }
    streamingAccumulatedText = StringBuilder()
    viewModelScope.launch(Dispatchers.Default) {
      if (needsRuntimeConversationReset) {
        needsRuntimeConversationReset = false
        curModel.runtimeHelper.resetConversation(
          model = curModel,
          supportImage = false,
          supportAudio = false,
        )
      }
      curModel.runtimeHelper.runInference(
        model = curModel,
        input = text,
        resultListener = { partialResult, done, _ ->
          if (done) {
            needsRuntimeConversationReset = true
            _uiState.update { onGenerationDone(it) }
          } else {
            streamingAccumulatedText.append(partialResult)
            _uiState.update { onStreamingUpdate(it, streamingAccumulatedText.toString()) }
          }
        },
        cleanUpListener = {},
        onError = { message ->
          Log.e(TAG, "Test chat generation error: $message")
          _uiState.update { onGenerationError(it, message) }
        },
      )
    }
  }

  /** Delegates cancellation to the production runtime; keeps generating until done. */
  fun stop() {
    val curModel = model ?: return
    _uiState.update { onStopRequested(it) }
    curModel.runtimeHelper.stopResponse(curModel)
  }

  /**
   * Clears the in-memory history and resets the production conversation without
   * re-creating the engine.
   */
  fun reset() {
    val curModel = model ?: return
    if (_uiState.value.status == TestChatStatus.GENERATING) {
      curModel.runtimeHelper.stopResponse(curModel)
    }
    if (curModel.instance != null) {
      curModel.runtimeHelper.resetConversation(
        model = curModel,
        supportImage = false,
        supportAudio = false,
      )
    }
    _uiState.update { onReset(it) }
  }

  /**
   * Leaving the screen: stops an active response, releases the engine/conversation
   * through the production helper and never leaves `model.instance` behind.
   */
  fun cleanup() {
    val curModel = model ?: return
    disposed = true
    if (_uiState.value.status == TestChatStatus.GENERATING) {
      curModel.runtimeHelper.stopResponse(curModel)
    }
    if (curModel.instance != null) {
      Log.d(TAG, "Cleaning up test chat model '${curModel.name}'")
      curModel.runtimeHelper.cleanUp(model = curModel, onDone = {})
    }
  }
}
