package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.CompressXApplication
import com.example.engine.CompressionLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Safely recovers any interrupted compression queue state after device reboot or app update.
 * Resets stale PROCESSING rows back to QUEUED, cleans orphaned temporary files, and posts a
 * non-intrusive notification inviting the user to resume (fully compliant with Android 15
 * foreground-service background start restrictions).
 */
class QueueRecoveryReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (
      action != Intent.ACTION_BOOT_COMPLETED &&
        action != Intent.ACTION_MY_PACKAGE_REPLACED
    ) {
      return
    }

    val pendingResult: PendingResult? = goAsync()
    val app = context.applicationContext as? CompressXApplication
    if (app == null) {
      runCatching { pendingResult?.finish() }
      return
    }

    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        app.compressionEngine.cleanupOrphanedTempFiles()
        app.queueRepository.resetInterruptedProcessingJobs()
        val pendingCount = app.queueRepository.countPendingOrPaused()
        if (pendingCount > 0) {
          CompressionLogger.i(
            "CompressXRecovery",
            "Recovered $pendingCount pending video(s) after $action",
          )
          NotificationHelper.postRecoveryNotification(
            context = context.applicationContext,
            pendingCount = pendingCount,
            reason = "Your $pendingCount queued video${if (pendingCount == 1) "" else "s"} survived restart. Tap to resume.",
          )
        }
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXRecovery", "Queue recovery failed safely", t)
      } finally {
        runCatching { pendingResult?.finish() }
      }
    }
  }
}
