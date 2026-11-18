package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.Modifier
import com.example.engine.ShareIntentParser
import com.example.ui.CompressXApp
import com.example.ui.CompressXViewModel
import com.example.ui.theme.CompressXStudioTheme

class MainActivity : ComponentActivity() {

  companion object {
    private const val KEY_SHARE_CONSUMED = "compressx_share_intent_consumed"
  }

  private val viewModel: CompressXViewModel by viewModels()
  private var shareIntentConsumed: Boolean = false

  @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    shareIntentConsumed = savedInstanceState?.getBoolean(KEY_SHARE_CONSUMED) ?: false
    handleIncomingShareIntent(intent, alreadyConsumed = shareIntentConsumed)

    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
    setContent {
      val windowSizeClass = calculateWindowSizeClass(this)
      CompressXStudioTheme {
        Box(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
          CompressXApp(
            widthSizeClass = windowSizeClass.widthSizeClass,
            viewModel = viewModel,
          )
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    shareIntentConsumed = false
    handleIncomingShareIntent(intent, alreadyConsumed = false)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean(KEY_SHARE_CONSUMED, shareIntentConsumed)
  }

  private fun handleIncomingShareIntent(incoming: Intent?, alreadyConsumed: Boolean) {
    if (alreadyConsumed || incoming == null) return
    if ((incoming.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return
    if (!ShareIntentParser.isShareIntent(incoming)) return

    shareIntentConsumed = true
    val payload = ShareIntentParser.parse(this, incoming)
    viewModel.onSharedPayloadReceived(payload)
  }
}
