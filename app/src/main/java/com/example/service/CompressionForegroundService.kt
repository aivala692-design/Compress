package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.CompressXApplication
import com.example.engine.CompressionLogger
import com.example.ui.components.FormatUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CompressionForegroundService : Service() {

  companion object {
    private const val TAG = "CompressXFGS"
    const val ACTION_START_OR_RESUME = "com.example.action.START_OR_RESUME"
    const val ACTION_PAUSE = "com.example.action.PAUSE"
    const val ACTION_CANCEL_ACTIVE = "com.example.action.CANCEL_ACTIVE"
    const val ACTION_CANCEL_JOB = "com.example.action.CANCEL_JOB"
    const val EXTRA_JOB_ID = "com.example.extra.JOB_ID"
    private const val WAKELOCK_TAG = "CompressXStudio::BatchEncodingWakeLock"
    private const val MAX_WAKELOCK_MS = 6 * 60 * 60 * 1000L // 6-hour safety cap

    @Volatile var isRunning: Boolean = false
      private set

    fun startQueue(context: Context): Boolean {
      val intent =
        Intent(context, CompressionForegroundService::class.java).apply {
          action = ACTION_START_OR_RESUME
        }
      return try {
        ContextCompat.startForegroundService(context, intent)
        true
      } catch (t: Throwable) {
        CompressionLogger.e(TAG, "startForegroundService failed; trying fallback path", t)
        runCatching {
          context.startService(intent)
          true
        }
          .onFailure { fallbackErr ->
            CompressionLogger.e(TAG, "startService fallback also rejected by OS", fallbackErr)
          }
          .getOrDefault(false)
      }
    }

    fun pauseQueue(context: Context) {
      val app = context.applicationContext as? CompressXApplication
      app?.batchQueueRunner?.requestPause()
      app?.cancelInProcessFallback()
      if (!isRunning) return

      val intent =
        Intent(context, CompressionForegroundService::class.java).apply { action = ACTION_PAUSE }
      runCatching { context.startService(intent) }
        .onFailure { CompressionLogger.w(TAG, "pauseQueue startService failed safely", it) }
    }

    fun cancelSpecificJob(context: Context, jobId: String) {
      val app = context.applicationContext as? CompressXApplication
      app?.batchQueueRunner?.cancelSpecificJob(jobId)
      if (!isRunning) return

      val intent =
        Intent(context, CompressionForegroundService::class.java).apply {
          action = ACTION_CANCEL_JOB
          putExtra(EXTRA_JOB_ID, jobId)
        }
      runCatching { context.startService(intent) }
        .onFailure { CompressionLogger.w(TAG, "cancelSpecificJob startService failed safely", it) }
    }

    fun cancelActiveJob(context: Context) {
      val app = context.applicationContext as? CompressXApplication
      val activeId = app?.batchQueueRunner?.activeJobId
      if (!isRunning) {
        if (activeId != null) {
          app.batchQueueRunner.cancelSpecificJob(activeId)
        } else {
          app?.compressionEngine?.cancelActive()
        }
        return
      }

      val intent =
        Intent(context, CompressionForegroundService::class.java).apply {
          action = ACTION_CANCEL_ACTIVE
        }
      runCatching { context.startService(intent) }
        .onFailure {
          CompressionLogger.w(TAG, "cancelActiveJob startService failed; cancelling directly", it)
          if (activeId != null) {
            app?.cancelJobDirect(activeId)
          } else {
            app?.compressionEngine?.cancelActive()
          }
        }
    }
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var workerJob: Job? = null
  private val foregroundActive = AtomicBoolean(false)
  private var wakeLock: PowerManager.WakeLock? = null
  @Volatile private var lastNotificationPostMs: Long = 0L

  override fun onCreate() {
    super.onCreate()
    isRunning = true
    NotificationHelper.ensureChannels(this)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    isRunning = true
    return when (intent?.action) {
      ACTION_PAUSE -> {
        handlePause(reason = null)
        START_NOT_STICKY
      }
      ACTION_CANCEL_ACTIVE -> {
        handleCancelActive()
        if (workerJob?.isActive == true) START_STICKY else START_NOT_STICKY
      }
      ACTION_CANCEL_JOB -> {
        val targetJobId = intent.getStringExtra(EXTRA_JOB_ID)
        if (!targetJobId.isNullOrBlank()) {
          handleCancelSpecificJob(targetJobId)
        }
        if (workerJob?.isActive == true) START_STICKY else START_NOT_STICKY
      }
      ACTION_START_OR_RESUME, null -> {
        val app = application as CompressXApplication
        app.batchQueueRunner.resetPauseRequest()
        val promoted = startForegroundSafely("Preparing compression queue...", 0, 0, 1)
        if (!promoted) {
          CompressionLogger.e(
            TAG,
            "Unable to promote foreground service on Android ${Build.VERSION.SDK_INT}; handoff to in-process runner",
          )
          app.startInProcessFallbackQueue()
          stopSelf(startId)
          return START_NOT_STICKY
        }
        ensureWorkerRunning()
        START_STICKY
      }
      else -> START_NOT_STICKY
    }
  }

  override fun onTimeout(startId: Int) {
    CompressionLogger.w(TAG, "Foreground service timeout reached (API 34+); pausing cleanly")
    handlePause(reason = "Android paused long background session. Tap to resume remaining videos.")
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    CompressionLogger.w(
      TAG,
      "Foreground service mediaProcessing timeout reached (type=$fgsType); pausing cleanly",
    )
    handlePause(reason = "Android paused long background session. Tap to resume remaining videos.")
  }

  override fun onDestroy() {
    isRunning = false
    foregroundActive.set(false)
    releaseWakeLockSafely()
    val app = application as CompressXApplication
    app.compressionEngine.cancelActive()
    app.compressionEngine.cleanupOrphanedTempFiles()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun startForegroundSafely(
    fileName: String,
    progress: Int,
    completed: Int,
    total: Int,
    statusNote: String? = null,
  ): Boolean {
    val notification =
      NotificationHelper.buildProgressNotification(
        context = this,
        fileName = fileName,
        jobProgress = progress,
        completedCount = completed,
        totalCount = total,
        statusNote = statusNote,
      )

    val candidateTypes =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        listOf(
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
      } else {
        listOf(0)
      }

    for (fgsType in candidateTypes) {
      try {
        ServiceCompat.startForeground(
          this,
          NotificationHelper.PROGRESS_NOTIFICATION_ID,
          notification,
          fgsType,
        )
        foregroundActive.set(true)
        return true
      } catch (t: Throwable) {
        CompressionLogger.e(
          TAG,
          "ServiceCompat.startForeground failed for fgsType=$fgsType (SDK=${Build.VERSION.SDK_INT})",
          t,
        )
      }
    }
    return false
  }

  private fun updateNotificationThrottled(
    fileName: String,
    progress: Int,
    completed: Int,
    total: Int,
    statusNote: String? = null,
    force: Boolean = false,
  ) {
    if (!NotificationHelper.canPostNotifications(this)) return
    val now = System.currentTimeMillis()
    if (!force && progress in 1..99 && now - lastNotificationPostMs < 350L) {
      return
    }
    lastNotificationPostMs = now

    val notification =
      NotificationHelper.buildProgressNotification(
        context = this,
        fileName = fileName,
        jobProgress = progress,
        completedCount = completed,
        totalCount = total,
        statusNote = statusNote,
      )
    runCatching {
      NotificationManagerCompat.from(this)
        .notify(NotificationHelper.PROGRESS_NOTIFICATION_ID, notification)
    }.onFailure { CompressionLogger.w(TAG, "Progress notification update failed", it) }
  }

  private fun ensureWorkerRunning() {
    if (workerJob?.isActive == true) return
    workerJob =
      serviceScope.launch {
        val app = application as CompressXApplication
        acquireWakeLockSafely()
        try {
          val summary =
            app.batchQueueRunner.runBatch(
              progressScope = serviceScope,
              onJobStarted = { fileName, completed, total ->
                updateNotificationThrottled(fileName, 0, completed, total, force = true)
              },
              onProgressChanged = { fileName, pct, completed, total ->
                updateNotificationThrottled(fileName, pct, completed, total, force = pct == 100)
              },
              onStatusNote = { fileName, completed, total, note ->
                updateNotificationThrottled(fileName, 0, completed, total, statusNote = note, force = true)
              },
            )

          if (summary.wasPaused && !summary.pauseReason.isNullOrBlank()) {
            val remaining = runCatching { app.queueRepository.countPendingOrPaused() }.getOrDefault(0)
            NotificationHelper.postRecoveryNotification(
              context = this@CompressionForegroundService,
              pendingCount = remaining,
              reason = summary.pauseReason,
            )
          } else if (
            !summary.wasPaused &&
              (summary.completedCount > 0 || summary.awaitingApprovalCount > 0 || summary.failedCount > 0)
          ) {
            NotificationHelper.postCompletionNotification(
              context = this@CompressionForegroundService,
              completedCount = summary.completedCount,
              awaitingApprovalCount = summary.awaitingApprovalCount,
              failedCount = summary.failedCount,
              savedFormatted = FormatUtils.formatBytes(summary.savedBytes),
            )
          }
        } catch (t: Throwable) {
          CompressionLogger.e(TAG, "Unexpected exception in foreground service batch loop", t)
        } finally {
          releaseWakeLockSafely()
          isRunning = false
          stopForegroundSafely()
          stopSelf()
        }
      }
  }

  private fun handlePause(reason: String?) {
    val app = application as CompressXApplication
    app.batchQueueRunner.requestPause()
    serviceScope.launch {
      runCatching { app.queueRepository.pauseActiveProcessingJobs() }
        .onFailure { CompressionLogger.e(TAG, "Failed pausing active jobs in DB", it) }
      if (!reason.isNullOrBlank()) {
        val remaining = runCatching { app.queueRepository.countPendingOrPaused() }.getOrDefault(0)
        NotificationHelper.postRecoveryNotification(
          context = this@CompressionForegroundService,
          pendingCount = remaining,
          reason = reason,
        )
      }
      workerJob?.cancel()
      stopForegroundSafely()
      isRunning = false
      stopSelf()
    }
  }

  private fun handleCancelSpecificJob(targetId: String) {
    val app = application as CompressXApplication
    app.batchQueueRunner.cancelSpecificJob(targetId)
    serviceScope.launch {
      if (workerJob?.isActive != true) {
        stopForegroundSafely()
        isRunning = false
        stopSelf()
      }
    }
  }

  private fun handleCancelActive() {
    val app = application as CompressXApplication
    val targetId = app.batchQueueRunner.activeJobId
    serviceScope.launch {
      if (targetId != null) {
        runCatching { app.queueRepository.cancelJob(targetId) }
          .onFailure { CompressionLogger.e(TAG, "Failed cancelling job $targetId", it) }
        app.batchQueueRunner.cancelSpecificJob(targetId)
      } else {
        app.compressionEngine.cancelActive()
      }
      if (workerJob?.isActive != true) {
        stopForegroundSafely()
        isRunning = false
        stopSelf()
      }
    }
  }

  private fun stopForegroundSafely() {
    if (!foregroundActive.getAndSet(false)) return
    runCatching {
      ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }.onFailure { CompressionLogger.w(TAG, "stopForeground failed safely", it) }
  }

  private fun acquireWakeLockSafely() {
    runCatching {
      if (wakeLock?.isHeld == true) return
      val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
      wakeLock =
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
          setReferenceCounted(false)
          acquire(MAX_WAKELOCK_MS)
        }
    }.onFailure { CompressionLogger.w(TAG, "Unable to acquire partial wake lock", it) }
  }

  private fun releaseWakeLockSafely() {
    runCatching {
      wakeLock?.let { if (it.isHeld) it.release() }
      wakeLock = null
    }.onFailure { CompressionLogger.w(TAG, "Unable to release wake lock", it) }
  }
}
