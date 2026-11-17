package com.example.ui.components

import java.util.Locale

object FormatUtils {

  fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    if (safe < 1024L) return "$safe B"
    val kb = safe / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
  }

  fun formatSavingsPair(bytes: Long): Pair<String, String> {
    val safe = bytes.coerceAtLeast(0L)
    val mb = safe / (1024.0 * 1024.0)
    val gb = safe / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
      String.format(Locale.US, "%.2f GB", gb) to String.format(Locale.US, "%.0f MB", mb)
    } else {
      String.format(Locale.US, "%.1f MB", mb) to String.format(Locale.US, "%.3f GB", gb)
    }
  }

  fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
      String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
      String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
  }

  fun formatReductionPercent(originalBytes: Long, outputBytes: Long): Int {
    if (originalBytes <= 0L || outputBytes <= 0L) return 0
    return (((originalBytes - outputBytes).toDouble() / originalBytes.toDouble()) * 100.0)
      .toInt()
      .coerceIn(-99, 99)
  }
}
