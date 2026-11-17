package com.example.ui.screens

import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.VideoJobEntity
import com.example.ui.StudioUiState
import com.example.ui.components.FormatUtils
import com.example.ui.components.VideoJobCard
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioSurfaceElevated
import com.example.ui.theme.TextSecondary

@Composable
fun HistoryScreen(
  uiState: StudioUiState,
  onClearHistory: () -> Unit,
  onDeleteJob: (String) -> Unit,
  onPreviewOutput: (VideoJobEntity) -> Unit,
  onShareOutput: (VideoJobEntity) -> Unit,
  modifier: Modifier = Modifier,
) {
  val (primarySaved, secondarySaved) = FormatUtils.formatSavingsPair(uiState.stats.totalSavedBytes)

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(modifier = Modifier.fillMaxSize().widthIn(max = 760.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(
            text = "Processing History",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text =
              if (uiState.stats.replacedCount > 0) {
                "${uiState.history.size} completed (${uiState.stats.replacedCount} originals replaced) • Tap play to verify"
              } else {
                "${uiState.history.size} compressed videos • Tap play to verify output"
              },
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
          )
        }
        if (uiState.history.isNotEmpty()) {
          TextButton(
            onClick = onClearHistory,
            modifier = Modifier.testTag("history_clear_button"),
          ) {
            Text("Clear Done", color = CyanPrimary)
          }
        }
      }

      if (uiState.history.isNotEmpty()) {
        Card(
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = StudioSurfaceElevated),
          modifier =
            Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)
              .border(1.dp, StudioBorder, RoundedCornerShape(18.dp)),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column {
              Text(
                text = "LIFETIME STORAGE SAVED",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
              )
              Text(
                text = "$primarySaved ($secondarySaved)",
                style = MaterialTheme.typography.titleLarge,
                color = EmeraldSuccess,
              )
            }
            Column(horizontalAlignment = Alignment.End) {
              Text(
                text = "BEFORE → AFTER",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
              )
              Text(
                text =
                  "${FormatUtils.formatBytes(uiState.stats.totalOriginalBytesCompleted)} → ${FormatUtils.formatBytes(uiState.stats.totalOutputBytesCompleted)}",
                style = MaterialTheme.typography.labelSmall,
                color = CyanPrimary,
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      if (uiState.history.isEmpty()) {
        Box(
          modifier = Modifier.fillMaxSize().padding(32.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              text = "No completed compressions yet",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "Completed videos with before/after size metrics will appear here.",
              style = MaterialTheme.typography.bodyMedium,
              color = TextSecondary,
            )
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(items = uiState.history, key = { it.id }) { job ->
            VideoJobCard(
              job = job,
              canMoveUp = false,
              canMoveDown = false,
              onMoveUp = {},
              onMoveDown = {},
              onCancel = {},
              onRetry = {},
              onDelete = { onDeleteJob(job.id) },
              onPreviewOutput = onPreviewOutput,
              onShareOutput = onShareOutput,
            )
          }
        }
      }
    }
  }
}
