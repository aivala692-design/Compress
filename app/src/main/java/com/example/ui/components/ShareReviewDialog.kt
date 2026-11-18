package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.VideoJobEntity
import com.example.model.CompressionPreset
import com.example.model.JobStatus
import com.example.model.OutputMode
import com.example.model.TargetResolution
import com.example.model.VideoCompressionConfig
import com.example.ui.IncomingShareReviewState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioSurfaceHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareReviewDialog(
  reviewState: IncomingShareReviewState,
  sharedJobs: List<VideoJobEntity>,
  config: VideoCompressionConfig,
  onSelectPreset: (CompressionPreset) -> Unit,
  onSelectResolution: (TargetResolution) -> Unit,
  onChangeBitrateKbps: (Int) -> Unit,
  onSelectOutputMode: (OutputMode) -> Unit,
  onReviewInQueue: () -> Unit,
  onConfirmStart: () -> Unit,
) {
  val validCount = sharedJobs.count { it.status == JobStatus.QUEUED }
  val totalBytes = sharedJobs.sumOf { it.originalSizeBytes.coerceAtLeast(0L) }
  val replaceActive = config.isReplaceOriginalActive

  AlertDialog(
    onDismissRequest = onReviewInQueue,
    containerColor = StudioSurfaceElevated,
    icon = {
      Icon(
        imageVector = Icons.Default.Share,
        contentDescription = null,
        tint = CyanPrimary,
      )
    },
    title = {
      Column {
        Text(
          text = "Shared Video${if (sharedJobs.size == 1) "" else "s"} Added (${sharedJobs.size})",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text =
            buildString {
              append("Total: ${FormatUtils.formatBytes(totalBytes)}")
              if (reviewState.skippedDuplicateCount > 0) {
                append(" • ${reviewState.skippedDuplicateCount} duplicate skipped")
              }
            },
          style = MaterialTheme.typography.labelSmall,
          color = CyanPrimary,
        )
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (!reviewState.captionText.isNullOrBlank()) {
          Surface(
            color = StudioSurfaceHighlight,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, StudioBorder),
          ) {
            Text(
              text = "Shared note: ${reviewState.captionText}",
              style = MaterialTheme.typography.bodySmall,
              color = TextSecondary,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            )
          }
        }

        // Scrollable list supporting 1, 10, 50+ shared videos smoothly
        LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(items = sharedJobs, key = { it.id }) { job ->
            Surface(
              color = StudioSurfaceHighlight,
              shape = RoundedCornerShape(10.dp),
              border =
                BorderStroke(
                  1.dp,
                  if (job.status == JobStatus.FAILED) CrimsonError.copy(alpha = 0.5f) else StudioBorder,
                ),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                VideoThumbnailBox(
                  uriString = job.sourceUri,
                  durationMs = job.durationMs,
                  modifier = Modifier.size(width = 64.dp, height = 48.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = job.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  val dimText =
                    if (job.width > 0 && job.height > 0) "${job.width}×${job.height} • " else ""
                  Text(
                    text = "$dimText${FormatUtils.formatBytes(job.originalSizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                  )
                  if (!job.errorMessage.isNullOrBlank()) {
                    Text(
                      text = job.errorMessage,
                      style = MaterialTheme.typography.labelSmall,
                      color = CrimsonError,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  }
                }
              }
            }
          }
        }

        // Compression Quality Presets
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = "Quality Preset",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            CompressionPreset.entries.forEach { preset ->
              val selected = config.preset == preset
              FilterChip(
                selected = selected,
                onClick = { onSelectPreset(preset) },
                label = { Text(preset.label) },
                colors =
                  FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                    selectedLabelColor = CyanPrimary,
                    containerColor = StudioSurfaceHighlight,
                  ),
                modifier = Modifier.testTag("share_preset_${preset.name}"),
              )
            }
          }
        }

        // Resolution Options
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = "Target Resolution",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
          )
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            TargetResolution.entries.forEach { res ->
              val selected = config.resolution == res
              FilterChip(
                selected = selected,
                onClick = { onSelectResolution(res) },
                label = { Text(res.label) },
                colors =
                  FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                    selectedLabelColor = CyanPrimary,
                    containerColor = StudioSurfaceHighlight,
                  ),
                modifier = Modifier.testTag("share_resolution_${res.name}"),
              )
            }
          }
        }

        // Target Video Bitrate Slider
        Column(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Target Bitrate",
              style = MaterialTheme.typography.labelMedium,
              color = TextSecondary,
            )
            Text(
              text = "${config.customBitrateKbps} kbps",
              style = MaterialTheme.typography.labelMedium,
              color = CyanPrimary,
            )
          }
          Slider(
            value = config.customBitrateKbps.toFloat().coerceIn(300f, 15000f),
            onValueChange = { raw ->
              val rounded = ((raw / 100f).roundToInt() * 100).coerceIn(300, 15000)
              onChangeBitrateKbps(rounded)
            },
            valueRange = 300f..15000f,
            colors =
              SliderDefaults.colors(
                thumbColor = CyanPrimary,
                activeTrackColor = CyanPrimary,
              ),
            modifier = Modifier.fillMaxWidth().testTag("share_bitrate_slider"),
          )
        }

        // Output Mode Switch Strip
        Surface(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .clickable {
                onSelectOutputMode(
                  if (replaceActive) OutputMode.KEEP_ORIGINAL else OutputMode.REPLACE_ORIGINAL
                )
              }
              .testTag("share_output_mode_toggle"),
          color = if (replaceActive) Color(0xFF0A3327) else StudioSurfaceHighlight,
          shape = RoundedCornerShape(10.dp),
          border =
            BorderStroke(
              1.dp,
              if (replaceActive) EmeraldSuccess.copy(alpha = 0.5f) else StudioBorder,
            ),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Default.SwapHoriz,
              contentDescription = null,
              tint = if (replaceActive) EmeraldSuccess else AmberWarning,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text =
                  if (replaceActive) "Replace Original (1 Final File)"
                  else "Keep Original (2 Files)",
                style = MaterialTheme.typography.labelMedium,
                color = if (replaceActive) EmeraldSuccess else MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "Tap to switch output mode",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
              )
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = onConfirmStart,
        enabled = validCount > 0,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = CyanPrimary,
            contentColor = Color(0xFF02171B),
          ),
        modifier = Modifier.testTag("share_confirm_start_button"),
      ) {
        Icon(
          imageVector = Icons.Default.PlayArrow,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("Start ($validCount)")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onReviewInQueue,
        modifier = Modifier.testTag("share_review_queue_button"),
      ) {
        Text("Queue Only")
      }
    },
  )
}
