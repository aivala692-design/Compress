package com.example.ui.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.data.VideoJobEntity
import com.example.model.JobStatus
import com.example.ui.QueueFilter
import com.example.ui.StudioUiState
import com.example.ui.components.VideoJobCard
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextSecondary

@Composable
fun QueueScreen(
  uiState: StudioUiState,
  onSelectFilter: (QueueFilter) -> Unit,
  onAddVideos: () -> Unit,
  onStartOrResume: () -> Unit,
  onPause: () -> Unit,
  onMoveJob: (Int, Int, List<VideoJobEntity>) -> Unit,
  onCancelJob: (String) -> Unit,
  onRetryJob: (String) -> Unit,
  onRetryAllFailed: () -> Unit,
  onDeleteJob: (String) -> Unit,
  onClearCompleted: () -> Unit,
  onClearAll: () -> Unit,
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
        QueueFilter.COMPLETED -> uiState.jobs.filter { it.status == JobStatus.COMPLETED }
        QueueFilter.FAILED ->
          uiState.jobs.filter { it.status == JobStatus.FAILED || it.status == JobStatus.CANCELLED }
      }
    }

  var draggedJobId by remember { mutableStateOf<String?>(null) }
  var dragOffsetY by remember { mutableFloatStateOf(0f) }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(modifier = Modifier.fillMaxSize().widthIn(max = 760.dp)) {
      // Top Queue Bar
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
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
              Text("Select Videos (SAF)", color = CyanPrimary)
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
              onMoveUp = { onMoveJob(index, index - 1, filteredJobs) },
              onMoveDown = { onMoveJob(index, index + 1, filteredJobs) },
              onCancel = { onCancelJob(job.id) },
              onRetry = { onRetryJob(job.id) },
              onDelete = { onDeleteJob(job.id) },
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
