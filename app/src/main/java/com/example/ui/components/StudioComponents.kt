package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.VideoJobEntity
import com.example.engine.CompressionPlanCalculator
import com.example.engine.VideoThumbnailLoader
import com.example.model.JobStatus
import com.example.model.ReplacementPermissionStatus
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BlueSecondary
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioSurfaceHighlight
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextSecondary

@Composable
fun StudioProgressRing(
  progressPercent: Int,
  isActive: Boolean,
  modifier: Modifier = Modifier,
  size: Dp = 96.dp,
) {
  val animatedProgress by
    animateFloatAsState(
      targetValue = (progressPercent.coerceIn(0, 100) / 100f),
      animationSpec = spring(stiffness = 220f),
      label = "ring_progress",
    )

  Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
    Canvas(modifier = Modifier.fillMaxSize()) {
      val strokeWidth = 8.dp.toPx()
      drawArc(
        color = StudioSurfaceHighlight,
        startAngle = -90f,
        sweepAngle = 360f,
        useCenter = false,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
      )
      drawArc(
        brush =
          Brush.sweepGradient(
            listOf(
              CyanPrimary,
              BlueSecondary,
              if (isActive) EmeraldSuccess else CyanPrimary,
            )
          ),
        startAngle = -90f,
        sweepAngle = 360f * animatedProgress,
        useCenter = false,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
      )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(
        text = "${progressPercent}%",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = if (isActive) "ACTIVE" else "BATCH",
        style = MaterialTheme.typography.labelSmall,
        color = if (isActive) CyanPrimary else TextMuted,
      )
    }
  }
}

@Composable
fun VideoThumbnailBox(
  uriString: String,
  durationMs: Long,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var bitmap by remember(uriString) { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(uriString) {
    bitmap = VideoThumbnailLoader.loadThumbnail(context, uriString)
  }

  Box(
    modifier =
      modifier
        .size(width = 86.dp, height = 64.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(StudioSurfaceHighlight)
        .border(1.dp, StudioBorder, RoundedCornerShape(12.dp)),
    contentAlignment = Alignment.Center,
  ) {
    val bmp = bitmap
    if (bmp != null) {
      Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = stringResource(R.string.cd_video_thumbnail),
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
    } else {
      Icon(
        imageVector = Icons.Default.VideoFile,
        contentDescription = stringResource(R.string.cd_video_thumbnail),
        tint = TextSecondary,
        modifier = Modifier.size(28.dp),
      )
    }

    if (durationMs > 0L) {
      Surface(
        color = Color(0xCC070B11),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
      ) {
        Text(
          text = FormatUtils.formatDuration(durationMs),
          style = MaterialTheme.typography.labelSmall,
          color = Color.White,
          modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
      }
    }
  }
}

@Composable
fun StatusBadgeChip(status: JobStatus, replacedOriginal: Boolean = false) {
  val (label, tint, bg) =
    when (status) {
      JobStatus.QUEUED -> Triple("QUEUED", TextSecondary, StudioSurfaceHighlight)
      JobStatus.PROCESSING -> Triple("ENCODING", CyanPrimary, Color(0xFF0A333D))
      JobStatus.PAUSED -> Triple("PAUSED", AmberWarning, Color(0xFF3A280A))
      JobStatus.AWAITING_APPROVAL -> Triple("APPROVE", AmberWarning, Color(0xFF3A280A))
      JobStatus.COMPLETED ->
        if (replacedOriginal) {
          Triple("REPLACED", EmeraldSuccess, Color(0xFF0A3327))
        } else {
          Triple("SAVED", EmeraldSuccess, Color(0xFF0A3327))
        }
      JobStatus.FAILED -> Triple("FAILED", CrimsonError, Color(0xFF3C1317))
      JobStatus.CANCELLED -> Triple("CANCELLED", TextMuted, StudioSurfaceHighlight)
    }

  Surface(
    color = bg,
    shape = RoundedCornerShape(50),
    border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = tint,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
  }
}

@Composable
fun ReplacementPermissionChip(
  status: ReplacementPermissionStatus,
  modifier: Modifier = Modifier,
) {
  if (status == ReplacementPermissionStatus.NOT_APPLICABLE) return

  val (label, tint, bg) =
    when (status) {
      ReplacementPermissionStatus.ELIGIBLE ->
        Triple("Replace Eligible", CyanPrimary, Color(0xFF0A2E38))
      ReplacementPermissionStatus.READ_ONLY ->
        Triple("Read-Only Source", AmberWarning, Color(0xFF3A280A))
      ReplacementPermissionStatus.PENDING_BATCH_APPROVAL ->
        Triple("Needs Approval", AmberWarning, Color(0xFF3A280A))
      ReplacementPermissionStatus.BATCH_APPROVED ->
        Triple("Approved", EmeraldSuccess, Color(0xFF0A3327))
      ReplacementPermissionStatus.DENIED ->
        Triple("Permission Denied", CrimsonError, Color(0xFF3C1317))
      ReplacementPermissionStatus.REPLACED ->
        Triple("Replaced Original", EmeraldSuccess, Color(0xFF0A3327))
      ReplacementPermissionStatus.SAVED_COPY ->
        Triple("Saved Copy", BlueSecondary, Color(0xFF0E233E))
      ReplacementPermissionStatus.FAILED ->
        Triple("Replace Failed", CrimsonError, Color(0xFF3C1317))
      ReplacementPermissionStatus.NOT_APPLICABLE ->
        return
    }

  Surface(
    color = bg,
    shape = RoundedCornerShape(50),
    border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
    modifier = modifier,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = tint,
      modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
    )
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VideoJobCard(
  job: VideoJobEntity,
  canMoveUp: Boolean,
  canMoveDown: Boolean,
  isDragging: Boolean = false,
  dragModifier: Modifier = Modifier,
  hasHardwareEncoder: Boolean = true,
  preferHardware: Boolean = true,
  isSelectedForApproval: Boolean = false,
  onToggleApprovalSelect: ((String) -> Unit)? = null,
  onMoveUp: () -> Unit,
  onMoveDown: () -> Unit,
  onCancel: () -> Unit,
  onRetry: () -> Unit,
  onDelete: () -> Unit,
  onApproveReplace: ((String) -> Unit)? = null,
  onSaveCopyInstead: ((String) -> Unit)? = null,
  onPreviewOutput: ((VideoJobEntity) -> Unit)? = null,
  onShareOutput: ((VideoJobEntity) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val isReadOnlySource = job.replacementStatus == ReplacementPermissionStatus.READ_ONLY
  val borderColor =
    when {
      isDragging -> CyanPrimary
      job.status == JobStatus.PROCESSING -> CyanPrimary.copy(alpha = 0.65f)
      job.status == JobStatus.AWAITING_APPROVAL && isSelectedForApproval ->
        EmeraldSuccess.copy(alpha = 0.75f)
      job.status == JobStatus.AWAITING_APPROVAL -> AmberWarning.copy(alpha = 0.65f)
      job.status == JobStatus.FAILED -> CrimsonError.copy(alpha = 0.5f)
      else -> StudioBorder
    }

  val compressionEstimate =
    remember(
      job.id,
      job.durationMs,
      job.originalSizeBytes,
      job.width,
      job.height,
      job.rotation,
      job.sourceBitrateBps,
      job.sourceFrameRate,
      job.sourceAudioBitrateBps,
      job.preset,
      job.targetResolution,
      job.targetCodec,
      job.customBitrateKbps,
      hasHardwareEncoder,
      preferHardware,
    ) {
      if (
        job.status == JobStatus.QUEUED ||
          job.status == JobStatus.PAUSED ||
          job.status == JobStatus.PROCESSING
      ) {
        CompressionPlanCalculator.estimateJobCompression(
          durationMs = job.durationMs,
          originalSizeBytes = job.originalSizeBytes,
          sourceWidth = job.width,
          sourceHeight = job.height,
          sourceRotation = job.rotation,
          sourceBitrateBps = job.sourceBitrateBps,
          sourceFrameRate = job.sourceFrameRate,
          sourceAudioBitrateBps = job.sourceAudioBitrateBps,
          preset = job.preset,
          targetResolution = job.targetResolution,
          targetCodec = job.targetCodec,
          customBitrateKbps = job.customBitrateKbps,
          hasHardwareEncoder = hasHardwareEncoder,
          preferHardware = preferHardware,
        )
      } else null
    }

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .testTag("job_card_${job.id}")
        .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
    shape = RoundedCornerShape(16.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = if (isDragging) StudioSurfaceHighlight else StudioSurfaceElevated
      ),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (job.status == JobStatus.QUEUED || job.status == JobStatus.PAUSED) {
          Box(
            modifier =
              dragModifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Default.DragHandle,
              contentDescription = "Drag to reorder ${job.fileName}",
              tint = TextSecondary,
              modifier = Modifier.size(20.dp),
            )
          }
        } else if (
          job.status == JobStatus.AWAITING_APPROVAL &&
            !isReadOnlySource &&
            onToggleApprovalSelect != null
        ) {
          Checkbox(
            checked = isSelectedForApproval,
            onCheckedChange = { onToggleApprovalSelect(job.id) },
            colors =
              CheckboxDefaults.colors(
                checkedColor = EmeraldSuccess,
                uncheckedColor = TextSecondary,
                checkmarkColor = Color(0xFF02171B),
              ),
            modifier = Modifier.testTag("select_approval_${job.id}"),
          )
        }

        VideoThumbnailBox(uriString = job.sourceUri, durationMs = job.durationMs)

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = job.fileName,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            StatusBadgeChip(status = job.status, replacedOriginal = job.replacedOriginal)
          }

          Spacer(modifier = Modifier.height(4.dp))

          val dimText =
            if (job.width > 0 && job.height > 0) "${job.width}×${job.height}" else "Video"
          val fpsTag =
            if (job.sourceFrameRate >= 1f) " • ${job.sourceFrameRate.toInt()}fps" else ""
          Text(
            text =
              "$dimText$fpsTag • ${job.preset.label} • ${job.targetResolution.label} ${job.targetCodec.shortTag}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )

          Spacer(modifier = Modifier.height(5.dp))

          // Original size + Estimated or Actual Compressed Size
          FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              text = "Orig: ${FormatUtils.formatBytes(job.originalSizeBytes)}",
              style = MaterialTheme.typography.labelSmall,
              color = TextSecondary,
            )

            if (
              (job.status == JobStatus.COMPLETED || job.status == JobStatus.AWAITING_APPROVAL) &&
                job.outputSizeBytes > 0L
            ) {
              val reduction =
                FormatUtils.formatReductionPercent(job.originalSizeBytes, job.outputSizeBytes)
              Text(
                text = "→ ${FormatUtils.formatBytes(job.outputSizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (job.status == JobStatus.AWAITING_APPROVAL) AmberWarning else EmeraldSuccess,
              )
              if (reduction > 0) {
                Surface(
                  color = EmeraldSuccess.copy(alpha = 0.16f),
                  shape = RoundedCornerShape(4.dp),
                ) {
                  Text(
                    text = "-$reduction%",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldSuccess,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                  )
                }
              }
            } else if (compressionEstimate != null && compressionEstimate.maxOutputBytes > 0L) {
              val estRange =
                FormatUtils.formatSizeRange(
                  compressionEstimate.minOutputBytes,
                  compressionEstimate.maxOutputBytes,
                )
              val pctRange =
                FormatUtils.formatReductionRange(
                  compressionEstimate.minReductionPercent,
                  compressionEstimate.maxReductionPercent,
                )
              Text(
                text = "→ Est: $estRange",
                style = MaterialTheme.typography.labelSmall,
                color = CyanPrimary,
              )
              if (compressionEstimate.maxReductionPercent > 0) {
                Surface(
                  color = CyanPrimary.copy(alpha = 0.14f),
                  shape = RoundedCornerShape(4.dp),
                ) {
                  Text(
                    text =
                      "Save $pctRange • ${FormatUtils.formatEta(compressionEstimate.estimatedProcessingSeconds)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanPrimary,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                  )
                }
              }
            }
          }

          if (job.replacementStatus != ReplacementPermissionStatus.NOT_APPLICABLE) {
            Spacer(modifier = Modifier.height(5.dp))
            ReplacementPermissionChip(status = job.replacementStatus)
          }
        }
      }

      if (job.status == JobStatus.PROCESSING || job.status == JobStatus.PAUSED) {
        Spacer(modifier = Modifier.height(10.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          LinearProgressIndicator(
            progress = { (job.progressPercent.coerceIn(0, 100)) / 100f },
            modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
            color = if (job.status == JobStatus.PAUSED) AmberWarning else CyanPrimary,
            trackColor = StudioSurface,
          )
          Spacer(modifier = Modifier.width(10.dp))
          Text(
            text = "${job.progressPercent}%",
            style = MaterialTheme.typography.labelSmall,
            color = CyanPrimary,
          )
        }
      }

      // Clear permission / read-only guidance without looking like an app crash
      val isPermissionNotice =
        job.replacementStatus == ReplacementPermissionStatus.READ_ONLY ||
          job.replacementStatus == ReplacementPermissionStatus.DENIED
      val displayNotice =
        when {
          isReadOnlySource && job.status != JobStatus.COMPLETED && job.status != JobStatus.FAILED ->
            job.errorMessage
              ?: "Read-only provider (${job.sourceAuthority ?: "external"}) — replacement unavailable; original stays untouched."
          !job.errorMessage.isNullOrBlank() -> job.errorMessage
          else -> null
        }

      if (!displayNotice.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        val noticeColor = if (isPermissionNotice) AmberWarning else CrimsonError
        Row(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(noticeColor.copy(alpha = 0.12f))
              .padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector =
              when {
                isReadOnlySource -> Icons.Default.Lock
                isPermissionNotice -> Icons.Default.Info
                else -> Icons.Default.ErrorOutline
              },
            contentDescription = if (isPermissionNotice) "Permission status" else "Error details",
            tint = noticeColor,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = displayNotice,
            style = MaterialTheme.typography.bodySmall,
            color = noticeColor,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      // Action bar
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (job.status == JobStatus.COMPLETED && !job.encoderName.isNullOrBlank()) {
          val modeLabel = if (job.usedHardwareEncoder == true) "HW" else "SW"
          Text(
            text = "$modeLabel • ${job.encoderName}",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
        } else {
          Spacer(modifier = Modifier.weight(1f))
        }

        if (job.status == JobStatus.QUEUED) {
          if (canMoveUp) {
            IconButton(
              onClick = onMoveUp,
              modifier = Modifier.size(48.dp).testTag("move_up_${job.id}"),
            ) {
              Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = "Move up",
                tint = TextSecondary,
              )
            }
          }
          if (canMoveDown) {
            IconButton(
              onClick = onMoveDown,
              modifier = Modifier.size(48.dp).testTag("move_down_${job.id}"),
            ) {
              Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = "Move down",
                tint = TextSecondary,
              )
            }
          }
        }

        when (job.status) {
          JobStatus.QUEUED,
          JobStatus.PROCESSING,
          JobStatus.PAUSED -> {
            IconButton(
              onClick = onCancel,
              modifier = Modifier.size(48.dp).testTag("cancel_job_${job.id}"),
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Cancel job",
                tint = CrimsonError,
              )
            }
          }
          JobStatus.AWAITING_APPROVAL -> {
            if (onPreviewOutput != null) {
              IconButton(
                onClick = { onPreviewOutput(job) },
                modifier = Modifier.size(48.dp).testTag("preview_staged_${job.id}"),
              ) {
                Icon(
                  imageVector = Icons.Default.PlayArrow,
                  contentDescription = "Preview verified compressed video",
                  tint = CyanPrimary,
                )
              }
            }
            if (isReadOnlySource) {
              if (onSaveCopyInstead != null) {
                FilledTonalButton(
                  onClick = { onSaveCopyInstead(job.id) },
                  colors =
                    ButtonDefaults.filledTonalButtonColors(
                      containerColor = CyanPrimary.copy(alpha = 0.2f),
                      contentColor = CyanPrimary,
                    ),
                  modifier = Modifier.height(40.dp).testTag("save_copy_${job.id}"),
                ) {
                  Icon(
                    imageVector = Icons.Default.SaveAlt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Save Copy", style = MaterialTheme.typography.labelMedium)
                }
              }
            } else {
              if (onSaveCopyInstead != null) {
                IconButton(
                  onClick = { onSaveCopyInstead(job.id) },
                  modifier = Modifier.size(48.dp).testTag("save_copy_${job.id}"),
                ) {
                  Icon(
                    imageVector = Icons.Default.SaveAlt,
                    contentDescription = "Save compressed copy instead",
                    tint = TextSecondary,
                  )
                }
              }
              if (onApproveReplace != null) {
                FilledTonalButton(
                  onClick = { onApproveReplace(job.id) },
                  colors =
                    ButtonDefaults.filledTonalButtonColors(
                      containerColor = EmeraldSuccess.copy(alpha = 0.2f),
                      contentColor = EmeraldSuccess,
                    ),
                  modifier = Modifier.height(40.dp).testTag("approve_replace_${job.id}"),
                ) {
                  Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Replace", style = MaterialTheme.typography.labelMedium)
                }
              }
            }
            IconButton(
              onClick = onDelete,
              modifier = Modifier.size(48.dp).testTag("discard_staged_${job.id}"),
            ) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Discard compressed file and keep original",
                tint = CrimsonError,
              )
            }
          }
          JobStatus.FAILED,
          JobStatus.CANCELLED -> {
            IconButton(
              onClick = onRetry,
              modifier = Modifier.size(48.dp).testTag("retry_job_${job.id}"),
            ) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Retry job",
                tint = CyanPrimary,
              )
            }
            IconButton(
              onClick = onDelete,
              modifier = Modifier.size(48.dp).testTag("delete_job_${job.id}"),
            ) {
              Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Remove job",
                tint = TextSecondary,
              )
            }
          }
          JobStatus.COMPLETED -> {
            if (onPreviewOutput != null) {
              IconButton(
                onClick = { onPreviewOutput(job) },
                modifier = Modifier.size(48.dp).testTag("preview_job_${job.id}"),
              ) {
                Icon(
                  imageVector = Icons.Default.PlayArrow,
                  contentDescription = "Play compressed video",
                  tint = CyanPrimary,
                )
              }
            }
            if (onShareOutput != null) {
              IconButton(
                onClick = { onShareOutput(job) },
                modifier = Modifier.size(48.dp).testTag("share_job_${job.id}"),
              ) {
                Icon(
                  imageVector = Icons.Default.Share,
                  contentDescription = "Share compressed video",
                  tint = TextSecondary,
                )
              }
            }
            IconButton(
              onClick = onDelete,
              modifier = Modifier.size(48.dp).testTag("delete_job_${job.id}"),
            ) {
              Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Delete record",
                tint = TextMuted,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
fun StudioStatPill(
  label: String,
  count: Int,
  accent: Color,
  modifier: Modifier = Modifier,
  onClick: () -> Unit = {},
) {
  Surface(
    modifier = modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    color = StudioSurfaceElevated,
    shape = RoundedCornerShape(14.dp),
    border = BorderStroke(1.dp, StudioBorder),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalAlignment = Alignment.Start,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(accent))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = count.toString(),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}
