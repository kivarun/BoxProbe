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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/**
 * Minimal text-only test chat for one LLM.
 *
 * Purpose: verify by hand that a downloaded/imported model actually responds through the
 * production runtime. There is no persistence: the history lives only while this screen
 * is open. This is not the old product chat and intentionally stays bare-bones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestChatScreen(
  model: Model,
  modelManagerViewModel: ModelManagerViewModel,
  onBackClicked: () -> Unit,
  viewModel: TestChatViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val availableLocally =
    modelManagerUiState.modelDownloadStatus[model.name]?.status ==
      ModelDownloadStatusType.SUCCEEDED

  LaunchedEffect(model.name, availableLocally) {
    viewModel.start(model = model, availableLocally = availableLocally)
  }
  DisposableEffect(Unit) { onDispose { viewModel.cleanup() } }

  val listState = rememberLazyListState()
  LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.text?.length) {
    if (uiState.messages.isNotEmpty()) {
      listState.animateScrollToItem(uiState.messages.size - 1)
    }
  }

  var input by remember { mutableStateOf("") }
  val sendEnabled = uiState.status == TestChatStatus.READY && input.isNotBlank()
  val generating = uiState.status == TestChatStatus.GENERATING
  val resetEnabled = uiState.messages.isNotEmpty() && !generating

  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Text(
            text = model.displayName.ifEmpty { model.name },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          IconButton(onClick = onBackClicked) {
            Icon(
              Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = stringResource(R.string.cd_navigate_back_icon),
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
        },
        actions = {
          if (uiState.status != TestChatStatus.INITIALIZING && uiState.status != TestChatStatus.ERROR) {
            IconButton(onClick = { viewModel.reset() }, enabled = resetEnabled) {
              Icon(
                Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.cd_reset_session_icon),
                tint = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        },
      )
    },
    bottomBar = {
      Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
          if (uiState.errorMessage.isNotEmpty() && !generating) {
            Text(
              text = uiState.errorMessage,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
          }
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedTextField(
              value = input,
              onValueChange = { input = it },
              modifier = Modifier.weight(1f),
              placeholder = { Text(stringResource(R.string.chat_textinput_placeholder)) },
              enabled = uiState.status == TestChatStatus.READY,
              singleLine = true,
            )
            if (generating) {
              IconButton(onClick = { viewModel.stop() }) {
                Icon(
                  Icons.Rounded.Stop,
                  contentDescription = stringResource(R.string.cd_stop_icon),
                  tint = MaterialTheme.colorScheme.onSurface,
                )
              }
            } else {
              IconButton(
                onClick = {
                  viewModel.send(input)
                  input = ""
                },
                enabled = sendEnabled,
              ) {
                Icon(
                  Icons.AutoMirrored.Rounded.Send,
                  contentDescription = stringResource(R.string.cd_send_prompt_icon),
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
            }
          }
        }
      }
    },
  ) { innerPadding ->
    when (uiState.status) {
      TestChatStatus.INITIALIZING -> {
        Box(
          modifier = Modifier.fillMaxSize().padding(innerPadding),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator()
        }
      }
      TestChatStatus.ERROR -> {
        Box(
          modifier = Modifier.fillMaxSize().padding(innerPadding),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = uiState.errorMessage,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 24.dp),
          )
        }
      }
      TestChatStatus.READY, TestChatStatus.GENERATING -> {
        LazyColumn(
          state = listState,
          modifier =
            Modifier.fillMaxSize()
              .background(MaterialTheme.colorScheme.surfaceContainer)
              .padding(innerPadding),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
          items(items = uiState.messages, key = { it.id }) { message ->
            ChatMessageBubble(message = message)
          }
        }
      }
    }
  }
}

@Composable
private fun ChatMessageBubble(message: TestChatMessage) {
  val isUser = message.role == TestChatRole.USER
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
  ) {
    Text(
      text =
        stringResource(if (isUser) R.string.chat_you else R.string.chat_llm_agent_name),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 8.dp),
    )
    Surface(
      shape = RoundedCornerShape(12.dp),
      color =
        if (isUser) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
      modifier = Modifier.widthIn(max = 320.dp),
    ) {
      Text(
        text = message.text.ifEmpty { "…" },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      )
    }
  }
}
