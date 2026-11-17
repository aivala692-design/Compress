package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

object NotificationHelper {

  const val CHANNEL_PROGRESS = "compressx_progress_channel"
  const val CHANNEL_COMPLETE = "compressx_complete_channel"
  const val PROGRESS_NOTIFICATION_ID = 1001
  const val COMPLETE_NOTIFICATION_ID = 1002

  fun ensureChannels(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
  }

  fun buildProgressNotification(
    context: Context,
    fileName: String,
    jobProgress: Int,
    completedCount: Int,
    totalCount: Int,
    statusNote: String? = null,
  ): Notification {
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

    val title =
      if (totalCount > 1) {
        "Compressing (${completedCount + 1}/$totalCount) • $jobProgress%"
      } else {
        "Compressing • $jobProgress%"
      }

    val content = statusNote ?: fileName

    return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentTitle(title)
      .setContentText(content)
      .setProgress(100, jobProgress.coerceIn(0, 100), false)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openIntent)
      .addAction(0, context.getString(R.string.action_pause), pauseIntent)
      .addAction(0, context.getString(R.string.action_cancel), cancelIntent)
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
      .build()
  }

  fun postCompletionNotification(
    context: Context,
    completedCount: Int,
    failedCount: Int,
    savedFormatted: String,
  ) {
    ensureChannels(context)
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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
      if (failedCount == 0) {
        "Batch Complete • $completedCount compressed"
      } else {
        "Batch Finished • $completedCount done, $failedCount failed"
      }

    val text = "Total storage reclaimed: $savedFormatted"

    val notification =
      NotificationCompat.Builder(context, CHANNEL_COMPLETE)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .setContentIntent(openIntent)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()

    runCatching { manager.notify(COMPLETE_NOTIFICATION_ID, notification) }
  }
}
