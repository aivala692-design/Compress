package com.example.ui.components

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.data.VideoJobEntity
import com.example.engine.CompressionLogger
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioVoid
import com.example.ui.theme.TextSecondary

@Composable
fun VideoPreviewDialog(
  job: VideoJobEntity,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val playbackUri = job.outputUri ?: job.sourceUri
  var playbackError by remember(playbackUri) { mutableStateOf<String?>(null) }

  val exoPlayer = remember(playbackUri) {
    ExoPlayer.Builder(context).build().apply {
      addListener(
        object : Player.Listener {
          override fun onPlayerError(error: PlaybackException) {
            CompressionLogger.e("CompressXPreview", "Preview playback error for $playbackUri", error)
            playbackError = error.localizedMessage ?: "Unable to play video (${error.errorCodeName})"
          }
        }
      )
      runCatching {
        setMediaItem(MediaItem.fromUri(Uri.parse(playbackUri)))
        prepare()
        playWhenReady = true
      }.onFailure {
        CompressionLogger.e("CompressXPreview", "Failed initializing ExoPlayer preview", it)
        playbackError = CompressionLogger.formatUserErrorMessage(it)
      }
    }
  }

  DisposableEffect(exoPlayer) {
    onDispose { runCatching { exoPlayer.release() } }
  }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = StudioSurfaceElevated,
      border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder),
      modifier = Modifier.fillMaxWidth().testTag("video_preview_dialog"),
    ) {
      Column(modifier = Modifier.padding(14.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
              text = job.outputFileName ?: job.fileName,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text =
                "${FormatUtils.formatBytes(job.originalSizeBytes)} → ${FormatUtils.formatBytes(job.outputSizeBytes)}",
              style = MaterialTheme.typography.labelSmall,
              color = EmeraldSuccess,
            )
          }
          IconButton(
            onClick = onDismiss,
            modifier = Modifier.testTag("close_preview_button"),
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close preview",
              tint = TextSecondary,
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
          modifier =
            Modifier
              .fillMaxWidth()
              .aspectRatio(16f / 9f)
              .clip(RoundedCornerShape(14.dp))
              .background(StudioVoid),
          contentAlignment = Alignment.Center,
        ) {
          if (playbackError != null) {
            Text(
              text = playbackError ?: "Playback unavailable",
              style = MaterialTheme.typography.bodyMedium,
              color = CrimsonError,
              modifier = Modifier.padding(16.dp),
            )
          } else {
            AndroidView(
              factory = { ctx ->
                PlayerView(ctx).apply {
                  player = exoPlayer
                  useController = true
                  layoutParams =
                    FrameLayout.LayoutParams(
                      ViewGroup.LayoutParams.MATCH_PARENT,
                      ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
              },
              modifier = Modifier.matchParentSize(),
            )
          }
        }
      }
    }
  }
}
