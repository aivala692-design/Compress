package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.CompressionPreset
import com.example.model.JobStatus
import com.example.model.OutputMode
import com.example.model.ThermalLevel
import com.example.ui.QueueFilter
import com.example.ui.StudioUiState
import com.example.ui.components.FormatUtils
import com.example.ui.components.StudioProgressRing
import com.example.ui.components.StudioStatPill
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
fun DashboardScreen(
  uiState: StudioUiState,
  onPickVideos: () -> Unit,
  onPickSafVideos: () -> Unit,
  onPickOutputFolder: () -> Unit,
  onStartOrResume: () -> Unit,
  onPause: () -> Unit,
  onRetryFailed: () -> Unit,
  onSelectPreset: (CompressionPreset) -> Unit,
  onSelectOutputMode: (OutputMode) -> Unit,
  onOpenBatchReplace: () -> Unit,
  onSaveAllCopies: () -> Unit,
  onNavigateToQueue: (QueueFilter) -> Unit,
  onNavigateToSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val activeJob = uiState.jobs.firstOrNull { it.status == JobStatus.PROCESSING }
  val (primarySaved, secondarySaved) = FormatUtils.formatSavingsPair(uiState.stats.totalSavedBytes)

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      // Studio Brand Header & Hardware Acceleration Status
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column {
            Text(
              text = "CompressX Studio",
              style = MaterialTheme.typography.headlineMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = "Offline Batch Media Engine",
              style = MaterialTheme.typography.bodyMedium,
              color = TextSecondary,
            )
          }

          val hwReady = uiState.capabilities?.hasAnyHardwareEncoder == true
          Surface(
            color = if (hwReady) Color(0xFF0A333D) else StudioSurfaceHighlight,
            shape = RoundedCornerShape(50),
            border =
              androidx.compose.foundation.BorderStroke(
                1.dp,
                if (hwReady) CyanPrimary.copy(alpha = 0.45f) else StudioBorder,
              ),
            modifier = Modifier.clickable(onClick = onNavigateToSettings),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = if (hwReady) Icons.Default.Bolt else Icons.Default.Memory,
                contentDescription = "Hardware codec status",
                tint = if (hwReady) CyanPrimary else AmberWarning,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(5.dp))
              Text(
                text = if (hwReady) "HW MediaCodec" else "SW Fallback",
                style = MaterialTheme.typography.labelSmall,
                color = if (hwReady) CyanPrimary else AmberWarning,
              )
            }
          }
        }
      }

      // Low Storage / Battery / Thermal Guardrails Banner
      item {
        AnimatedVisibility(
          visible =
            uiState.guardrails.isLowStorage ||
              uiState.guardrails.isLowBattery ||
              uiState.guardrails.thermalLevel != ThermalLevel.NORMAL
        ) {
          val (icon, msg, color) =
            when {
              uiState.guardrails.isLowStorage ->
                Triple(
                  Icons.Default.Storage,
                  "Low storage warning: only ${FormatUtils.formatBytes(uiState.guardrails.availableStorageBytes)} free.",
                  CrimsonError,
                )
              uiState.guardrails.thermalLevel == ThermalLevel.SEVERE ->
                Triple(
                  Icons.Default.Thermostat,
                  "Device warm — sequential thermal cooldown protection active.",
                  AmberWarning,
                )
              uiState.guardrails.isLowBattery ->
                Triple(
                  Icons.Default.BatteryAlert,
                  "Low battery (${uiState.guardrails.batteryPercent}%) — plug in charger for large batches.",
                  AmberWarning,
                )
              else ->
                Triple(
                  Icons.Default.Thermostat,
                  "Thermal state elevated • Concurrent encoder limited to 1.",
                  AmberWarning,
                )
            }

          Surface(
            color = color.copy(alpha = 0.14f),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().testTag("guardrail_warning_banner"),
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
              Spacer(modifier = Modifier.width(10.dp))
              Text(text = msg, style = MaterialTheme.typography.bodyMedium, color = color)
            }
          }
        }
      }

      // Hero Storage Savings & Batch Telemetry Card
      item {
        Card(
          shape = RoundedCornerShape(22.dp),
          colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
          modifier =
            Modifier
              .fillMaxWidth()
              .border(
                1.dp,
                Brush.linearGradient(listOf(CyanPrimary.copy(alpha = 0.45f), BlueSecondary.copy(alpha = 0.35f))),
                RoundedCornerShape(22.dp),
              ),
        ) {
          Row(
            modifier =
              Modifier
                .fillMaxWidth()
                .background(
                  Brush.verticalGradient(
                    listOf(Color(0xFF0E2336), StudioSurfaceElevated)
                  )
                )
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "STORAGE RECLAIMED",
                style = MaterialTheme.typography.labelSmall,
                color = CyanPrimary,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = primarySaved,
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "$secondarySaved • ${uiState.stats.savingsRatioPercent}% avg reduction",
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldSuccess,
              )
              Spacer(modifier = Modifier.height(10.dp))
              if (uiState.stats.totalOriginalBytesPending > 0L && uiState.stats.estMaxOutputBytesPending > 0L) {
                val estOut =
                  FormatUtils.formatSizeRange(
                    uiState.stats.estMinOutputBytesPending,
                    uiState.stats.estMaxOutputBytesPending,
                  )
                val estPct =
                  FormatUtils.formatReductionRange(
                    uiState.stats.estMinReductionPercentPending,
                    uiState.stats.estMaxReductionPercentPending,
                  )
                Text(
                  text =
                    "Queued ${FormatUtils.formatBytes(uiState.stats.totalOriginalBytesPending)} → Est. $estOut (-$estPct • ${FormatUtils.formatEta(uiState.stats.estProcessingSecondsPending)})",
                  style = MaterialTheme.typography.labelMedium,
                  color = CyanPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
              }
              Text(
                text =
                  "Free space: ${FormatUtils.formatBytes(uiState.guardrails.availableStorageBytes)} • Battery ${uiState.guardrails.batteryPercent}%",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
              )
            }

            Spacer(modifier = Modifier.width(12.dp))

            StudioProgressRing(
              progressPercent = uiState.stats.overallProgressPercent,
              isActive = uiState.isBatchRunning,
              size = 94.dp,
            )
          }
        }
      }

      // Verified Videos Awaiting User Approval to Replace Originals
      if (uiState.stats.awaitingApprovalCount > 0) {
        item {
          Surface(
            color = AmberWarning.copy(alpha = 0.14f),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarning.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().testTag("dashboard_awaiting_approval_banner"),
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Default.SwapHoriz,
                  contentDescription = null,
                  tint = AmberWarning,
                  modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text =
                    "${uiState.stats.awaitingApprovalCount} Verified Video${if (uiState.stats.awaitingApprovalCount == 1) "" else "s"} Ready to Replace Originals",
                  style = MaterialTheme.typography.titleSmall,
                  color = AmberWarning,
                )
              }
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text =
                  "Compressed files passed playback verification. Approve to replace originals (keeping 1 file) or save separate copies.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
              )
              Spacer(modifier = Modifier.height(10.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                  onClick = onOpenBatchReplace,
                  enabled = !uiState.isApplyingReplacements,
                  colors =
                    ButtonDefaults.buttonColors(
                      containerColor = EmeraldSuccess,
                      contentColor = Color(0xFF02171B),
                    ),
                  modifier = Modifier.weight(1f).height(48.dp).testTag("dashboard_approve_replace_btn"),
                ) {
                  Text(
                    text = if (uiState.isApplyingReplacements) "Applying..." else "Approve & Replace",
                    style = MaterialTheme.typography.labelLarge,
                  )
                }
                OutlinedButton(
                  onClick = onSaveAllCopies,
                  enabled = !uiState.isApplyingReplacements,
                  modifier = Modifier.height(48.dp).testTag("dashboard_save_copies_btn"),
                ) {
                  Text("Save Copies")
                }
              }
            }
          }
        }
      }

      // Queue Telemetry Counts
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          StudioStatPill(
            label = "QUEUED",
            count = uiState.stats.queuedCount,
            accent = BlueSecondary,
            modifier = Modifier.weight(1f),
            onClick = { onNavigateToQueue(QueueFilter.QUEUED) },
          )
          StudioStatPill(
            label = if (uiState.stats.awaitingApprovalCount > 0) "APPROVE" else "ACTIVE",
            count =
              if (uiState.stats.awaitingApprovalCount > 0) uiState.stats.awaitingApprovalCount
              else uiState.stats.processingCount,
            accent = if (uiState.stats.awaitingApprovalCount > 0) AmberWarning else CyanPrimary,
            modifier = Modifier.weight(1f),
            onClick = {
              onNavigateToQueue(
                if (uiState.stats.awaitingApprovalCount > 0) QueueFilter.AWAITING
                else QueueFilter.PROCESSING
              )
            },
          )
          StudioStatPill(
            label = if (uiState.stats.replacedCount > 0) "REPLACED" else "DONE",
            count =
              if (uiState.stats.replacedCount > 0) uiState.stats.replacedCount
              else uiState.stats.completedCount,
            accent = EmeraldSuccess,
            modifier = Modifier.weight(1f),
            onClick = { onNavigateToQueue(QueueFilter.COMPLETED) },
          )
          StudioStatPill(
            label = "FAILED",
            count = uiState.stats.failedCount,
            accent = CrimsonError,
            modifier = Modifier.weight(1f),
            onClick = { onNavigateToQueue(QueueFilter.FAILED) },
          )
        }
      }

      // Live Active Encoding Card
      if (activeJob != null) {
        item {
          Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
            modifier =
              Modifier
                .fillMaxWidth()
                .border(1.dp, CyanPrimary.copy(alpha = 0.55f), RoundedCornerShape(18.dp)),
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "ENCODING NOW",
                  style = MaterialTheme.typography.labelSmall,
                  color = CyanPrimary,
                )
                Text(
                  text = "${activeJob.progressPercent}%",
                  style = MaterialTheme.typography.titleMedium,
                  color = CyanPrimary,
                )
              }
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = activeJob.fileName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Spacer(modifier = Modifier.height(8.dp))
              LinearProgressIndicator(
                progress = { activeJob.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)),
                color = CyanPrimary,
                trackColor = StudioSurface,
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text =
                  "Source: ${FormatUtils.formatBytes(activeJob.originalSizeBytes)} • ${activeJob.targetResolution.label} ${activeJob.targetCodec.shortTag}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
              )
            }
          }
        }
      }

      // Quick Preset Selector
      item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "Compression Preset",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text =
                "${uiState.config.resolution.label} • ${uiState.config.codec.shortTag}" +
                  if (uiState.config.preset == CompressionPreset.CUSTOM) {
                    " • ${uiState.config.customBitrateKbps} kbps"
                  } else "",
              style = MaterialTheme.typography.labelSmall,
              color = CyanPrimary,
              modifier = Modifier.clickable(onClick = onNavigateToSettings),
            )
          }

          LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(CompressionPreset.entries) { preset ->
              val selected = uiState.config.preset == preset
              Surface(
                modifier =
                  Modifier
                    .width(156.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelectPreset(preset) }
                    .testTag("preset_chip_${preset.name}"),
                color = if (selected) Color(0xFF0A333D) else StudioSurfaceElevated,
                shape = RoundedCornerShape(16.dp),
                border =
                  androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) CyanPrimary else StudioBorder,
                  ),
              ) {
                Column(modifier = Modifier.padding(12.dp)) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                      imageVector = Icons.Default.Speed,
                      contentDescription = null,
                      tint = if (selected) CyanPrimary else TextSecondary,
                      modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = preset.label,
                      style = MaterialTheme.typography.labelLarge,
                      color = if (selected) CyanPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                  }
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = preset.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 2,
                  )
                }
              }
            }
          }
        }
      }

      // Output Mode Switcher Strip (Replace Original vs Keep Original)
      item {
        val replaceActive = uiState.config.isReplaceOriginalActive
        Surface(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(14.dp))
              .clickable {
                onSelectOutputMode(
                  if (replaceActive) OutputMode.KEEP_ORIGINAL else OutputMode.REPLACE_ORIGINAL
                )
              }
              .testTag("dashboard_output_mode_button"),
          color = if (replaceActive) Color(0xFF0A3327) else StudioSurfaceElevated,
          shape = RoundedCornerShape(14.dp),
          border =
            androidx.compose.foundation.BorderStroke(
              1.dp,
              if (replaceActive) EmeraldSuccess.copy(alpha = 0.65f) else StudioBorder,
            ),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = if (replaceActive) Icons.Default.SwapHoriz else Icons.Default.SaveAlt,
              contentDescription = "Output storage mode",
              tint = if (replaceActive) EmeraldSuccess else CyanPrimary,
              modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text =
                  if (replaceActive) "Output Mode: Replace Original (1 Final File)"
                  else "Output Mode: Keep Original (2 Files)",
                style = MaterialTheme.typography.labelLarge,
                color = if (replaceActive) EmeraldSuccess else MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text =
                  if (replaceActive) {
                    "Verified first, asks your permission, then replaces original"
                  } else {
                    "Recommended: Tap to enable Replace Original Mode"
                  },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Text(
              text = if (replaceActive) "Keep Copy" else "Enable",
              style = MaterialTheme.typography.labelLarge,
              color = if (replaceActive) TextSecondary else CyanPrimary,
            )
          }
        }
      }

      // Output Folder Selector Strip
      item {
        val replaceActive = uiState.config.isReplaceOriginalActive
        Surface(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(14.dp))
              .clickable(onClick = onPickOutputFolder)
              .testTag("output_folder_button"),
          color = StudioSurfaceElevated,
          shape = RoundedCornerShape(14.dp),
          border = androidx.compose.foundation.BorderStroke(1.dp, StudioBorder),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Default.FolderOpen,
              contentDescription = "Select output folder",
              tint = BlueSecondary,
              modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text =
                  if (replaceActive) "Copy Fallback Folder (if Save Copy is chosen)"
                  else "Output Folder (Auto _compressed suffix)",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
              )
              Text(
                text = uiState.config.outputFolderName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            Text(
              text = "Change",
              style = MaterialTheme.typography.labelLarge,
              color = CyanPrimary,
            )
          }
        }
      }

      // Primary Batch Controls
      item {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Button(
              onClick = onPickVideos,
              modifier =
                Modifier.weight(1f).height(52.dp).testTag("add_videos_button"),
              shape = RoundedCornerShape(14.dp),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = CyanPrimary,
                  contentColor = Color(0xFF02171B),
                ),
            ) {
              Icon(Icons.Default.Add, contentDescription = null)
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = if (uiState.isImportingVideos) "Importing..." else "Add Videos",
                style = MaterialTheme.typography.labelLarge,
              )
            }

            if (uiState.isBatchRunning) {
              FilledTonalButton(
                onClick = onPause,
                modifier = Modifier.weight(1f).height(52.dp).testTag("pause_batch_button"),
                shape = RoundedCornerShape(14.dp),
                colors =
                  ButtonDefaults.filledTonalButtonColors(
                    containerColor = AmberWarning.copy(alpha = 0.2f),
                    contentColor = AmberWarning,
                  ),
              ) {
                Icon(Icons.Default.Pause, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Pause Batch", style = MaterialTheme.typography.labelLarge)
              }
            } else {
              FilledTonalButton(
                onClick = onStartOrResume,
                enabled = uiState.hasPendingOrPaused,
                modifier = Modifier.weight(1f).height(52.dp).testTag("start_batch_button"),
                shape = RoundedCornerShape(14.dp),
                colors =
                  ButtonDefaults.filledTonalButtonColors(
                    containerColor = BlueSecondary,
                    contentColor = Color.White,
                  ),
              ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text =
                    if (uiState.stats.pausedCount > 0) "Resume (${uiState.stats.pausedCount + uiState.stats.queuedCount})"
                    else "Compress (${uiState.stats.queuedCount})",
                  style = MaterialTheme.typography.labelLarge,
                )
              }
            }
          }

          OutlinedButton(
            onClick = onPickSafVideos,
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("browse_saf_videos_button"),
            shape = RoundedCornerShape(14.dp),
          ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = BlueSecondary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Browse Device Folders (SAF)", color = MaterialTheme.colorScheme.onSurface)
          }

          if (uiState.stats.failedCount > 0) {
            OutlinedButton(
              onClick = onRetryFailed,
              modifier = Modifier.fillMaxWidth().height(48.dp).testTag("retry_failed_button"),
              shape = RoundedCornerShape(14.dp),
            ) {
              Icon(Icons.Default.Refresh, contentDescription = null, tint = CyanPrimary)
              Spacer(modifier = Modifier.width(8.dp))
              Text("Retry ${uiState.stats.failedCount} Failed Jobs", color = CyanPrimary)
            }
          }
        }
      }

      // Empty State Studio Visual Art
      if (uiState.jobs.isEmpty()) {
        item {
          Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
            modifier =
              Modifier
                .fillMaxWidth()
                .border(1.dp, StudioBorder, RoundedCornerShape(20.dp)),
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Image(
                painter = painterResource(id = R.drawable.img_studio_empty_state_1794918726477),
                contentDescription = stringResource(R.string.cd_empty_studio),
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
              )
              Spacer(modifier = Modifier.height(12.dp))
              Text(
                text = "Ready for 1 to 100+ Videos Offline",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text =
                  "Pick multiple videos via Android Photo Picker or Storage Access Framework. CompressX processes sequentially with hardware acceleration, automatic software fallback, and zero cloud uploads.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
              )
            }
          }
        }
      }
    }
  }
}
