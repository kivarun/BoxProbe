/*
 * Copyright 2025 Google LLC
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

package com.box.gallery

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.gallery.GalleryApp
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

  private val modelManagerViewModel: ModelManagerViewModel by viewModels()
  private var contentSet: Boolean = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    handleIntentExtras(intent)

    fun setContent() {
      if (contentSet) {
        return
      }

      setContent {
        GalleryTheme {
          GalleryApp(modelManagerViewModel = modelManagerViewModel)
        }
      }

      @OptIn(ExperimentalApi::class)
      ExperimentalFlags.enableBenchmark = false

      contentSet = true
    }

    modelManagerViewModel.loadModelAllowlist()

    // Disable the system splash screen's custom exit animation and just show content.
    val splashScreen = installSplashScreen()
    setContent()

    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Fix for three-button nav not properly going edge-to-edge.
      // See: https://issuetracker.google.com/issues/298296168
      window.isNavigationBarContrastEnforced = false
    }
    // Keep the screen on while the app is running for a better demo experience.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  private fun handleIntentExtras(intent: Intent) {
    // Debug: Dump all intent extras to see what push unloads
    intent.extras?.let { extras ->
      for (key in extras.keySet()) {
        Log.d(TAG, "Intent Extra -> Key: $key, Value: ${extras.get(key)}")
      }
    }

    // Convert push data extras to intent data for GalleryNavGraph to pick up
    intent.getStringExtra("deeplink")?.let { link ->
      Log.d(TAG, "Found deeplink extra: $link")
      if (link.startsWith("http://") || link.startsWith("https://")) {
        val browserIntent = Intent(Intent.ACTION_VIEW, link.toUri())
        startActivity(browserIntent)
      } else {
        intent.data = link.toUri()
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntentExtras(intent)
  }

  companion object {
    private const val TAG = "AGMainActivity"
  }
}
