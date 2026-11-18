package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.data.VideoJobEntity
import com.example.model.CompressionPreset
import com.example.model.JobStatus
import com.example.ui.QueueFilter
import com.example.ui.StudioUiState
import com.example.ui.components.FormatUtils
import com.example.ui.components.VideoJobCard
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.BlueSecondary
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.StudioSurfaceHighlight
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QueueScreen(
  uiState: StudioUiState,
  onSelectFilter: (QueueFilter) -> Unit,
  onSelectPreset: (CompressionPreset) -> Unit,
  onAddVideos: () -> Unit,
  onAddSafVideos: () -> Unit,
  onStartOrResume: () -> Unit,
  onPause: () -> Unit,
  onMoveJob: (Int, Int, List<VideoJobEntity>) -> Unit,
  onCancelJob: (String) -> Unit,
  onRetryJob: (String) -> Unit,
  onRetryAllFailed: () -> Unit,
  onDeleteJob: (String) -> Unit,
  onClearCompleted: () -> Unit,
  onClearAll: () -> Unit,
  onToggleApprovalSelect: (String) -> Unit,
  onSelectAllEligible: () -> Unit,
  onOpenBatchReplace: () -> Unit,
  onSaveAllReadOnlyCopies: () -> Unit,
  onSaveAllCopies: () -> Unit,
  onApproveReplace: (String) -> Unit,
  onSaveCopyInstead: (String) -> Unit,
  onPreviewOutput: (VideoJobEntity) -> Unit,
  onShareOutput: (VideoJobEntity) -> Unit,
  modifier: Modifier = Modifier,
) {
  val filteredJobs =
    remember(uiState.jobs, uiState.selectedFilter) {
      when (uiState.selectedFilter) {
        QueueFilter.ALL -> uiState.jobs
        QueueFilter.QUEUED ->
          uiState.jobs.filter { it.status == JobStatus.QUEUED || it.status == JobStatus.PAUSED }
        QueueFilter.PROCESSING -> uiState.jobs.filter { it.status == JobStatus.PROCESSING }
        QueueFilter.AWAITING -> uiState.jobs.filter { it.status == JobStatus.AWAITING_APPROVAL }
        QueueFilter.COMPLETED -> uiState.jobs.filter { it.status == JobStatus.COMPLETED }
        QueueFilter.FAILED ->
          uiState.jobs.filter { it.status == JobStatus.FAILED || it.status == JobStatus.CANCELLED }
      }
    }

  val hasHardwareEncoder = uiState.capabilities?.hasAnyHardwareEncoder == true
  val preferHardware = uiState.config.preferHardwareAcceleration
  val eligibleAwaiting = uiState.eligibleAwaitingApprovalJobs
  val readOnlyAwaiting = uiState.readOnlyAwaitingApprovalJobs
  val selectedEligibleCount =
    eligibleAwaiting.count { it.id in uiState.selectedApprovalJobIds }

  var draggedJobId by remember { mutableStateOf<String?>(null) }
  var dragOffsetY by remember { mutableFloatStateOf(0f) }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(modifier = Modifier.fillMaxSize().widthIn(max = 760.dp)) {
      // Top Queue Bar
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(
            text = "Batch Queue (${uiState.jobs.size})",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "Long-press drag handle or arrows to reorder",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
          )
        }

        Row {
          if (uiState.isBatchRunning) {
            OutlinedButton(
              onClick = onPause,
              modifier = Modifier.height(48.dp).testTag("queue_pause_button"),
            ) {
              Icon(Icons.Default.Pause, contentDescription = null)
              Spacer(modifier = Modifier.width(4.dp))
              Text("Pause")
            }
          } else if (uiState.hasPendingOrPaused) {
            OutlinedButton(
              onClick = onStartOrResume,
              modifier = Modifier.height(48.dp).testTag("queue_start_button"),
            ) {
              Icon(Icons.Default.PlayArrow, contentDescription = null, tint = CyanPrimary)
              Spacer(modifier = Modifier.width(4.dp))
              Text("Start", color = CyanPrimary)
            }
          }
        }
      }

      // Batch Smart Estimator & Eligibility Overview Card (for 1 to 100+ videos)
      if (uiState.jobs.isNotEmpty()) {
        val totalBatchOrigBytes = remember(uiState.jobs) { uiState.jobs.sumOf { it.originalSizeBytes } }
        Card(
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
          border = BorderStroke(1.dp, StudioBorder),
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 4.dp)
              .testTag("queue_batch_estimator_card"),
        ) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column {
                Text(
                  text = "Total Orig: ${FormatUtils.formatBytes(totalBatchOrigBytes)}",
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                if (uiState.stats.estMaxOutputBytesPending > 0L) {
                  val estOut =
                    FormatUtils.formatSizeRange(
                      uiState.stats.estMinOutputBytesPending,
                      uiState.stats.estMaxOutputBytesPending,
                    )
                  val estSaved =
                    FormatUtils.formatSizeRange(
                      uiState.stats.estMinSavedBytesPending,
                      uiState.stats.estMaxSavedBytesPending,
                    )
                  val pctRange =
                    FormatUtils.formatReductionRange(
                      uiState.stats.estMinReductionPercentPending,
                      uiState.stats.estMaxReductionPercentPending,
                    )
                  Text(
                    text =
                      "Est. Output: $estOut • Save $estSaved ($pctRange) • ${FormatUtils.formatEta(uiState.stats.estProcessingSecondsPending)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanPrimary,
                  )
                } else if (uiState.stats.totalOriginalBytesCompleted > 0L) {
                  Text(
                    text =
                      "Saved so far: ${FormatUtils.formatBytes(uiState.stats.totalSavedBytes)} (${uiState.stats.savingsRatioPercent}%)",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmeraldSuccess,
                  )
                }
              }
            }

            // Replace Eligible vs Read-Only Counts + Instant Quality Switcher
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Surface(
                color = EmeraldSuccess.copy(alpha = 0.14f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, EmeraldSuccess.copy(alpha = 0.35f)),
                modifier = Modifier.testTag("queue_replace_eligible_count"),
              ) {
                Text(
                  text = "Replace Eligible: ${uiState.stats.replaceEligibleCount}",
                  style = MaterialTheme.typography.labelSmall,
                  color = EmeraldSuccess,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
              }

              Surface(
                color =
                  if (uiState.stats.readOnlyCount > 0) AmberWarning.copy(alpha = 0.14f)
                  else StudioSurfaceHighlight,
                shape = RoundedCornerShape(50),
                border =
                  BorderStroke(
                    1.dp,
                    if (uiState.stats.readOnlyCount > 0) AmberWarning.copy(alpha = 0.35f)
                    else StudioBorder,
                  ),
                modifier = Modifier.testTag("queue_readonly_count"),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (uiState.stats.readOnlyCount > 0) AmberWarning else TextSecondary,
                    modifier = Modifier.size(12.dp),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(
                    text = "Read-only: ${uiState.stats.readOnlyCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uiState.stats.readOnlyCount > 0) AmberWarning else TextSecondary,
                  )
                }
              }

              // Quick preset chips right in queue to immediately recalculate batch & item estimates
              if (uiState.stats.queuedCount > 0) {
                listOf(
                  CompressionPreset.HIGH_QUALITY,
                  CompressionPreset.BALANCED,
                  CompressionPreset.SMALL_SIZE,
                ).forEach { preset ->
                  val selected = uiState.config.preset == preset
                  FilterChip(
                    selected = selected,
                    onClick = { onSelectPreset(preset) },
                    label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
                    colors =
                      FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                        selectedLabelColor = CyanPrimary,
                        containerColor = StudioSurfaceHighlight,
                      ),
                    modifier = Modifier.height(28.dp).testTag("queue_preset_${preset.name}"),
                  )
                }
              }
            }
          }
        }
      }

      // Centralized Batch Approval Action Bar
      if (uiState.stats.awaitingApprovalCount > 0) {
        Surface(
          color = AmberWarning.copy(alpha = 0.14f),
          shape = RoundedCornerShape(14.dp),
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 6.dp)
              .border(1.dp, AmberWarning.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
              .testTag("queue_awaiting_approval_banner"),
        ) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Default.SwapHoriz,
                contentDescription = null,
                tint = AmberWarning,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.width(8.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text =
                    when {
                      eligibleAwaiting.isNotEmpty() && readOnlyAwaiting.isNotEmpty() ->
                        "${eligibleAwaiting.size} eligible for ONE batch approval • ${readOnlyAwaiting.size} read-only (Save Copy)"
                      eligibleAwaiting.isNotEmpty() ->
                        "${eligibleAwaiting.size} verified video(s) ready for ONE-dialog batch replacement"
                      else ->
                        "${readOnlyAwaiting.size} read-only source(s) cannot replace in-place — Save Compressed Copies"
                    },
                  style = MaterialTheme.typography.labelLarge,
                  color = AmberWarning,
                )
              }
            }

            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              if (eligibleAwaiting.isNotEmpty()) {
                OutlinedButton(
                  onClick = onSelectAllEligible,
                  enabled = !uiState.isApplyingReplacements,
                  modifier = Modifier.height(44.dp).testTag("queue_select_all_eligible"),
                ) {
                  Icon(
                    imageVector = Icons.Default.CheckBox,
                    contentDescription = null,
                    tint = CyanPrimary,
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Select All Eligible (${eligibleAwaiting.size})", color = CyanPrimary)
                }

                Button(
                  onClick = onOpenBatchReplace,
                  enabled = !uiState.isApplyingReplacements && selectedEligibleCount > 0,
                  colors =
                    ButtonDefaults.buttonColors(
                      containerColor = EmeraldSuccess,
                      contentColor = Color(0xFF02171B),
                    ),
                  modifier = Modifier.height(44.dp).testTag("queue_approve_replace_all"),
                ) {
                  Text(
                    text =
                      if (uiState.isApplyingReplacements) "Applying..."
                      else "Approve & Replace ($selectedEligibleCount)",
                    style = MaterialTheme.typography.labelLarge,
                  )
                }
              }

              if (readOnlyAwaiting.isNotEmpty()) {
                OutlinedButton(
                  onClick = onSaveAllReadOnlyCopies,
                  enabled = !uiState.isApplyingReplacements,
                  modifier = Modifier.height(44.dp).testTag("queue_save_readonly_copies"),
                ) {
                  Icon(
                    imageVector = Icons.Default.SaveAlt,
                    contentDescription = null,
                    tint = AmberWarning,
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Save Read-Only (${readOnlyAwaiting.size})", color = AmberWarning)
                }
              }

              OutlinedButton(
                onClick = onSaveAllCopies,
                enabled = !uiState.isApplyingReplacements,
                modifier = Modifier.height(44.dp).testTag("queue_save_all_copies"),
              ) {
                Text("Save All Copies", color = BlueSecondary)
              }
            }
          }
        }
      }

      // Filter Chips & Batch Cleanup Actions
      LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(QueueFilter.entries) { filter ->
          val selected = uiState.selectedFilter == filter
          FilterChip(
            selected = selected,
            onClick = { onSelectFilter(filter) },
            label = { Text(filter.label) },
            colors =
              FilterChipDefaults.filterChipColors(
                selectedContainerColor = CyanPrimary.copy(alpha = 0.2f),
                selectedLabelColor = CyanPrimary,
                containerColor = StudioSurfaceElevated,
              ),
            modifier = Modifier.testTag("queue_filter_${filter.name}"),
          )
        }
      }

      if (uiState.jobs.isNotEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TextButton(
            onClick = onAddVideos,
            modifier = Modifier.testTag("queue_add_more_button"),
          ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add More")
          }

          Row {
            if (uiState.stats.failedCount > 0) {
              TextButton(
                onClick = onRetryAllFailed,
                modifier = Modifier.testTag("queue_retry_failed_button"),
              ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Retry Failed")
              }
            }
            if (uiState.stats.completedCount > 0) {
              TextButton(
                onClick = onClearCompleted,
                modifier = Modifier.testTag("queue_clear_done_button"),
              ) {
                Text("Clear Done")
              }
            }
            TextButton(
              onClick = onClearAll,
              modifier = Modifier.testTag("queue_clear_all_button"),
            ) {
              Icon(Icons.Default.DeleteSweep, contentDescription = null)
              Spacer(modifier = Modifier.width(4.dp))
              Text("Clear All")
            }
          }
        }
      }

      if (filteredJobs.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxSize().padding(32.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = "No videos in this view",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "Tap Add More to queue 1 to 100+ local videos.",
              style = MaterialTheme.typography.bodyMedium,
              color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(
              onClick = onAddVideos,
              modifier = Modifier.height(48.dp).testTag("empty_queue_add_button"),
            ) {
              Icon(Icons.Default.Add, contentDescription = null, tint = CyanPrimary)
              Spacer(modifier = Modifier.width(6.dp))
              Text("Select Videos (Photo Picker)", color = CyanPrimary)
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
              onClick = onAddSafVideos,
              modifier = Modifier.testTag("empty_queue_saf_button"),
            ) {
              Text("Or Browse Device Folders (SAF)", color = TextSecondary)
            }
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          itemsIndexed(items = filteredJobs, key = { _, item -> item.id }) { index, job ->
            val isDragging = draggedJobId == job.id
            val dragModifier =
              Modifier.pointerInput(job.id, filteredJobs.size) {
                detectDragGesturesAfterLongPress(
                  onDragStart = {
                    draggedJobId = job.id
                    dragOffsetY = 0f
                  },
                  onDragEnd = {
                    draggedJobId = null
                    dragOffsetY = 0f
                  },
                  onDragCancel = {
                    draggedJobId = null
                    dragOffsetY = 0f
                  },
                  onDrag = { change, dragAmount ->
                    change.consume()
                    dragOffsetY += dragAmount.y
                    val thresholdPx = 140f
                    val currentIdx = filteredJobs.indexOfFirst { it.id == job.id }
                    if (currentIdx >= 0) {
                      if (dragOffsetY > thresholdPx && currentIdx < filteredJobs.lastIndex) {
                        onMoveJob(currentIdx, currentIdx + 1, filteredJobs)
                        dragOffsetY -= thresholdPx
                      } else if (dragOffsetY < -thresholdPx && currentIdx > 0) {
                        onMoveJob(currentIdx, currentIdx - 1, filteredJobs)
                        dragOffsetY += thresholdPx
                      }
                    }
                  },
                )
              }

            VideoJobCard(
              job = job,
              canMoveUp = index > 0 && filteredJobs[index - 1].status == JobStatus.QUEUED,
              canMoveDown =
                index < filteredJobs.lastIndex && filteredJobs[index + 1].status == JobStatus.QUEUED,
              isDragging = isDragging,
              dragModifier = dragModifier,
              hasHardwareEncoder = hasHardwareEncoder,
              preferHardware = preferHardware,
              isSelectedForApproval = job.id in uiState.selectedApprovalJobIds,
              onToggleApprovalSelect = onToggleApprovalSelect,
              onMoveUp = { onMoveJob(index, index - 1, filteredJobs) },
              onMoveDown = { onMoveJob(index, index + 1, filteredJobs) },
              onCancel = { onCancelJob(job.id) },
              onRetry = { onRetryJob(job.id) },
              onDelete = { onDeleteJob(job.id) },
              onApproveReplace = onApproveReplace,
              onSaveCopyInstead = onSaveCopyInstead,
              onPreviewOutput = onPreviewOutput,
              onShareOutput = onShareOutput,
              modifier =
                Modifier
                  .zIndex(if (isDragging) 1f else 0f)
                  .graphicsLayer {
                    translationY = if (isDragging) dragOffsetY else 0f
                  },
            )
          }
        }
      }
    }
  }
}
