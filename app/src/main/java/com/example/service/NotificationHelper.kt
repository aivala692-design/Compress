package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.engine.CompressionLogger

object NotificationHelper {

  private const val TAG = "CompressXNotify"
  const val CHANNEL_PROGRESS = "compressx_progress_channel"
  const val CHANNEL_COMPLETE = "compressx_complete_channel"
  const val PROGRESS_NOTIFICATION_ID = 1001
  const val COMPLETE_NOTIFICATION_ID = 1002

  fun ensureChannels(context: Context) {
    runCatching {
      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        ?: return

      val progressChannel =
        NotificationChannel(
          CHANNEL_PROGRESS,
          context.getString(R.string.channel_progress_name),
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = context.getString(R.string.channel_progress_desc)
          setShowBadge(false)
        }

      val completeChannel =
        NotificationChannel(
          CHANNEL_COMPLETE,
          context.getString(R.string.channel_complete_name),
          NotificationManager.IMPORTANCE_HIGH,
        ).apply {
          description = context.getString(R.string.channel_complete_desc)
        }

      manager.createNotificationChannel(progressChannel)
      manager.createNotificationChannel(completeChannel)
    }.onFailure { CompressionLogger.w(TAG, "Failed creating notification channels", it) }
  }

  fun canPostNotifications(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) return false
    }
    return runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
      .getOrDefault(false)
  }

  fun buildProgressNotification(
    context: Context,
    fileName: String,
    jobProgress: Int,
    completedCount: Int,
    totalCount: Int,
    statusNote: String? = null,
  ): Notification {
    ensureChannels(context)

    val openIntent =
      PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val pauseIntent =
      PendingIntent.getService(
        context,
        1,
        Intent(context, CompressionForegroundService::class.java).apply {
          action = CompressionForegroundService.ACTION_PAUSE
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val cancelIntent =
      PendingIntent.getService(
        context,
        2,
        Intent(context, CompressionForegroundService::class.java).apply {
          action = CompressionForegroundService.ACTION_CANCEL_ACTIVE
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val safeTotal = totalCount.coerceAtLeast(1)
    val clampedProgress = jobProgress.coerceIn(0, 100)
    val title =
      if (safeTotal > 1) {
        "Compressing (${(completedCount + 1).coerceAtMost(safeTotal)}/$safeTotal) • $clampedProgress%"
      } else {
        "Compressing • $clampedProgress%"
      }

    val content = statusNote ?: fileName

    return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(title)
      .setContentText(content)
      .setProgress(100, clampedProgress, false)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .setContentIntent(openIntent)
      .addAction(0, context.getString(R.string.action_pause), pauseIntent)
      .addAction(0, context.getString(R.string.action_cancel), cancelIntent)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .build()
  }

  fun postCompletionNotification(
    context: Context,
    completedCount: Int,
    awaitingApprovalCount: Int,
    failedCount: Int,
    savedFormatted: String,
  ) {
    if (!canPostNotifications(context)) return
    ensureChannels(context)

    val openIntent =
      PendingIntent.getActivity(
        context,
        10,
        Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val title =
      when {
        awaitingApprovalCount > 0 ->
          "Ready to Replace • $awaitingApprovalCount video${if (awaitingApprovalCount == 1) "" else "s"} verified"
        failedCount == 0 -> "Batch Complete • $completedCount compressed"
        else -> "Batch Finished • $completedCount done, $failedCount failed"
      }

    val text =
      if (awaitingApprovalCount > 0) {
        "Tap to confirm replacing original video${if (awaitingApprovalCount == 1) "" else "s"}."
      } else {
        "Total storage reclaimed: $savedFormatted"
      }

    val notification =
      NotificationCompat.Builder(context, CHANNEL_COMPLETE)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .setContentIntent(openIntent)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    runCatching { NotificationManagerCompat.from(context).notify(COMPLETE_NOTIFICATION_ID, notification) }
      .onFailure { CompressionLogger.w(TAG, "Failed posting completion notification", it) }
  }
}
