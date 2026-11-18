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

  fun formatSizeRange(minBytes: Long, maxBytes: Long): String {
    val lo = minOf(minBytes, maxBytes).coerceAtLeast(0L)
    val hi = maxOf(minBytes, maxBytes).coerceAtLeast(0L)
    if (lo == 0L && hi == 0L) return "0 MB"
    val mbDiv = 1024.0 * 1024.0
    val gbDiv = 1024.0 * 1024.0 * 1024.0
    if (hi >= 1024.0 * mbDiv) {
      val loGb = lo / gbDiv
      val hiGb = hi / gbDiv
      return if (String.format(Locale.US, "%.2f", loGb) == String.format(Locale.US, "%.2f", hiGb)) {
        String.format(Locale.US, "%.2f GB", hiGb)
      } else {
        String.format(Locale.US, "%.2f–%.2f GB", loGb, hiGb)
      }
    }
    if (hi >= mbDiv) {
      val loMb = lo / mbDiv
      val hiMb = hi / mbDiv
      return if (hiMb >= 10.0) {
        val loInt = loMb.toInt()
        val hiInt = kotlin.math.ceil(hiMb).toInt().coerceAtLeast(loInt)
        if (loInt == hiInt) "$hiInt MB" else "$loInt–$hiInt MB"
      } else {
        val loStr = String.format(Locale.US, "%.1f", loMb)
        val hiStr = String.format(Locale.US, "%.1f", hiMb)
        if (loStr == hiStr) "$hiStr MB" else "$loStr–$hiStr MB"
      }
    }
    val loKb = (lo / 1024.0).toInt().coerceAtLeast(1)
    val hiKb = kotlin.math.ceil(hi / 1024.0).toInt().coerceAtLeast(loKb)
    return if (loKb == hiKb) "$hiKb KB" else "$loKb–$hiKb KB"
  }

  fun formatReductionRange(minPct: Int, maxPct: Int): String {
    val lo = minOf(minPct, maxPct)
    val hi = maxOf(minPct, maxPct)
    return if (lo == hi) "~$hi%" else "$lo–$hi%"
  }

  fun formatEta(seconds: Int): String {
    val safe = seconds.coerceAtLeast(1)
    if (safe < 60) return "~${safe}s"
    val mins = safe / 60
    val remSec = safe % 60
    return if (mins >= 60) {
      val hrs = mins / 60
      val remMin = mins % 60
      "~${hrs}h ${remMin}m"
    } else if (remSec == 0) {
      "~${mins}m"
    } else {
      "~${mins}m ${remSec}s"
    }
  }
}
