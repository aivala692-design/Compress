package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.VideoJobEntity
import com.example.engine.CompressionPlanCalculator
import com.example.model.CompressionPreset
import com.example.model.JobStatus
import com.example.model.OutputMode
import com.example.model.ReplacementPermissionStatus
import com.example.model.TargetResolution
import com.example.model.VideoCodec
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
  hasHardwareEncoder: Boolean = true,
  preferHardware: Boolean = true,
  onSelectPreset: (CompressionPreset) -> Unit,
  onSelectResolution: (TargetResolution) -> Unit,
  onSelectCodec: ((VideoCodec) -> Unit)? = null,
  onChangeBitrateKbps: (Int) -> Unit,
  onSelectOutputMode: (OutputMode) -> Unit,
  onReviewInQueue: () -> Unit,
  onConfirmStart: () -> Unit,
) {
  val validCount = sharedJobs.count { it.status == JobStatus.QUEUED }
  val replaceActive = config.isReplaceOriginalActive

  val batchSummary =
    remember(sharedJobs, config, hasHardwareEncoder, preferHardware) {
      var origTotal = 0L
      var minOut = 0L
      var maxOut = 0L
      var etaTotal = 0
      var eligible = 0
      var readOnly = 0
      val perJobEst =
        sharedJobs.associate { job ->
          if (job.replacementStatus == ReplacementPermissionStatus.READ_ONLY) readOnly++
          else if (job.status != JobStatus.FAILED) eligible++

          val est =
            CompressionPlanCalculator.estimateJobCompression(
              durationMs = job.durationMs,
              originalSizeBytes = job.originalSizeBytes,
              sourceWidth = job.width,
              sourceHeight = job.height,
              sourceRotation = job.rotation,
              sourceBitrateBps = job.sourceBitrateBps,
              sourceFrameRate = job.sourceFrameRate,
              sourceAudioBitrateBps = job.sourceAudioBitrateBps,
              preset = config.preset,
              targetResolution = config.resolution,
              targetCodec = config.codec,
              customBitrateKbps = config.customBitrateKbps,
              hasHardwareEncoder = hasHardwareEncoder,
              preferHardware = preferHardware,
            )
          origTotal += est.originalBytes
          minOut += est.minOutputBytes
          maxOut += est.maxOutputBytes
          etaTotal += est.estimatedProcessingSeconds
          job.id to est
        }
      val minSaved = (origTotal - maxOut).coerceAtLeast(0L)
      val maxSaved = (origTotal - minOut).coerceAtLeast(0L)
      val minPct =
        if (origTotal > 0L) ((minSaved.toDouble() / origTotal) * 100.0).toInt().coerceIn(0, 99) else 0
      val maxPct =
        if (origTotal > 0L) ((maxSaved.toDouble() / origTotal) * 100.0).toInt().coerceIn(minPct, 99) else 0
      Triple(
        perJobEst,
        listOf(origTotal, minOut, maxOut, minSaved, maxSaved, minPct.toLong(), maxPct.toLong(), etaTotal.toLong()),
        eligible to readOnly,
      )
    }

  val perJobEstimates = batchSummary.first
  val metrics = batchSummary.second
  val totalBytes = metrics[0]
  val minOutBytes = metrics[1]
  val maxOutBytes = metrics[2]
  val minSavedBytes = metrics[3]
  val maxSavedBytes = metrics[4]
  val minPct = metrics[5].toInt()
  val maxPct = metrics[6].toInt()
  val totalEtaSec = metrics[7].toInt()
  val (eligibleCount, readOnlyCount) = batchSummary.third

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
              append("Original: ${FormatUtils.formatBytes(totalBytes)}")
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
        // Live Smart Estimator & Eligibility Summary Banner
        if (maxOutBytes > 0L) {
          Surface(
            color = CyanPrimary.copy(alpha = 0.1f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth().testTag("share_estimator_summary"),
          ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
              Text(
                text =
                  "Est. Output: ${FormatUtils.formatSizeRange(minOutBytes, maxOutBytes)} • Save ${FormatUtils.formatReductionRange(minPct, maxPct)} (${FormatUtils.formatSizeRange(minSavedBytes, maxSavedBytes)})",
                style = MaterialTheme.typography.labelMedium,
                color = CyanPrimary,
              )
              val etaLabel = if (totalEtaSec > 0) "Est. Time: ${FormatUtils.formatEta(totalEtaSec)}" else ""
              val eligLabel =
                if (readOnlyCount > 0) "Eligible: $eligibleCount • Read-only: $readOnlyCount (copies only)"
                else "Replace Eligible: $eligibleCount"
              Text(
                text = listOf(etaLabel, eligLabel).filter { it.isNotBlank() }.joinToString(" • "),
                style = MaterialTheme.typography.labelSmall,
                color = if (readOnlyCount > 0) AmberWarning else TextSecondary,
              )
            }
          }
        }

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
          modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(items = sharedJobs, key = { it.id }) { job ->
            val est = perJobEstimates[job.id]
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
                  if (est != null && est.maxOutputBytes > 0L) {
                    Text(
                      text =
                        "Est: ${FormatUtils.formatSizeRange(est.minOutputBytes, est.maxOutputBytes)} (-${FormatUtils.formatReductionRange(est.minReductionPercent, est.maxReductionPercent)})",
                      style = MaterialTheme.typography.labelSmall,
                      color = CyanPrimary,
                    )
                  }
                  if (job.replacementStatus == ReplacementPermissionStatus.READ_ONLY) {
                    Text(
                      text = "Read-only source • Save Copy only",
                      style = MaterialTheme.typography.labelSmall,
                      color = AmberWarning,
                    )
                  }
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

        // Codec selector when wired
        if (onSelectCodec != null) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              text = "Codec",
              style = MaterialTheme.typography.labelMedium,
              color = TextSecondary,
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              VideoCodec.entries.forEach { codec ->
                val selected = config.codec == codec
                FilterChip(
                  selected = selected,
                  onClick = { onSelectCodec(codec) },
                  label = { Text(codec.label) },
                  colors =
                    FilterChipDefaults.filterChipColors(
                      selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                      selectedLabelColor = CyanPrimary,
                      containerColor = StudioSurfaceHighlight,
                    ),
                  modifier = Modifier.testTag("share_codec_${codec.name}"),
                )
              }
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
                text =
                  if (readOnlyCount > 0 && replaceActive) {
                    "Read-only sources auto-switch to Save Copy"
                  } else {
                    "Tap to switch output mode"
                  },
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
