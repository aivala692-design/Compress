package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.example.CompressXApplication
import com.example.data.VideoJobEntity
import com.example.engine.CompressionPlanCalculator
import com.example.model.JobStatus
import com.example.ui.components.FormatUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CompressionForegroundService : Service() {

  companion object {
    const val ACTION_START_OR_RESUME = "com.example.action.START_OR_RESUME"
    const val ACTION_PAUSE = "com.example.action.PAUSE"
    const val ACTION_CANCEL_ACTIVE = "com.example.action.CANCEL_ACTIVE"

    fun startQueue(context: Context) {
      val intent =
        Intent(context, CompressionForegroundService::class.java).apply {
          action = ACTION_START_OR_RESUME
        }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun pauseQueue(context: Context) {
      val intent =
        Intent(context, CompressionForegroundService::class.java).apply { action = ACTION_PAUSE }
      context.startService(intent)
    }

    fun cancelActiveJob(context: Context) {
      val intent =
        Intent(context, CompressionForegroundService::class.java).apply {
          action = ACTION_CANCEL_ACTIVE
        }
      context.startService(intent)
    }
  }

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private var workerJob: Job? = null
  @Volatile private var activeJobId: String? = null
  @Volatile private var isPauseRequested: Boolean = false

  override fun onCreate() {
    super.onCreate()
    NotificationHelper.ensureChannels(this)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PAUSE -> handlePause()
      ACTION_CANCEL_ACTIVE -> handleCancelActive()
      ACTION_START_OR_RESUME, null -> {
        isPauseRequested = false
        startForegroundSafely("Preparing compression queue...", 0, 0, 1)
        ensureWorkerRunning()
      }
    }
    return START_STICKY
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    // Android 15+ mediaProcessing FGS 6-hour/24h quota timeout safeguard
    handlePause()
    stopSelf()
  }

  override fun onDestroy() {
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
  ) {
    val notification =
      NotificationHelper.buildProgressNotification(
        context = this,
        fileName = fileName,
        jobProgress = progress,
        completedCount = completed,
        totalCount = total,
        statusNote = statusNote,
      )
    val fgsType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
      } else {
        0
      }

    ServiceCompat.startForeground(
      this,
      NotificationHelper.PROGRESS_NOTIFICATION_ID,
      notification,
      fgsType,
    )
  }

  private fun updateNotification(
    fileName: String,
    progress: Int,
    completed: Int,
    total: Int,
    statusNote: String? = null,
  ) {
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
    }
  }

  private fun ensureWorkerRunning() {
    if (workerJob?.isActive == true) return
    workerJob =
      serviceScope.launch {
        val app = application as CompressXApplication
        val repo = app.queueRepository
        val prefs = app.preferencesRepository
        val engine = app.compressionEngine
        val guardrails = app.guardrailMonitor

        // Recover any interrupted jobs before starting loop
        repo.resetInterruptedProcessingJobs()
        repo.resumeAllPausedJobs()
        engine.cleanupOrphanedTempFiles()

        var sessionCompleted = 0
        var sessionFailed = 0
        var sessionSavedBytes = 0L

        while (!isPauseRequested) {
          val nextJob: VideoJobEntity = repo.getNextQueuedJob() ?: break
          val config = prefs.configFlow.first()

          val totalPending = repo.countPendingOrActive().coerceAtLeast(1)
          val totalBatch = sessionCompleted + totalPending

          // Hardware & resource guardrails before encoding each video
          val estimatedNeededBytes =
            maxOf(
              500L * 1024L * 1024L,
              (nextJob.originalSizeBytes * 1.1).toLong(),
            )
          val guardrail = guardrails.sample(estimatedNeededBytes)

          if (guardrail.isLowStorage) {
            repo.markFailed(
              nextJob.id,
              "Insufficient free storage (${FormatUtils.formatBytes(guardrail.availableStorageBytes)} free).",
            )
            sessionFailed++
            continue
          }

          if (config.pauseOnLowBattery && guardrail.isLowBattery) {
            isPauseRequested = true
            repo.updateStatusAndProgress(nextJob.id, JobStatus.PAUSED, 0)
            updateNotification(
              fileName = nextJob.fileName,
              progress = 0,
              completed = sessionCompleted,
              total = totalBatch,
              statusNote = "Paused: low battery (${guardrail.batteryPercent}%)",
            )
            break
          }

          if (config.thermalProtectionEnabled && guardrail.isThrottled) {
            updateNotification(
              fileName = nextJob.fileName,
              progress = 0,
              completed = sessionCompleted,
              total = totalBatch,
              statusNote = "Thermal cooldown active — waiting 15s...",
            )
            delay(15_000L)
          }

          activeJobId = nextJob.id
          repo.updateStatusAndProgress(nextJob.id, JobStatus.PROCESSING, 0)
          updateNotification(nextJob.fileName, 0, sessionCompleted, totalBatch)

          val plan =
            CompressionPlanCalculator.buildPlan(
              sourceWidth = nextJob.width,
              sourceHeight = nextJob.height,
              sourceRotation = nextJob.rotation,
              sourceBitrateBps = nextJob.sourceBitrateBps,
              preset = nextJob.preset,
              targetResolution = nextJob.targetResolution,
              targetCodec = nextJob.targetCodec,
              customBitrateKbps = nextJob.customBitrateKbps,
            )

          try {
            var lastPostedProgress = -1
            val result =
              engine.compressVideo(
                jobId = nextJob.id,
                sourceUri = Uri.parse(nextJob.sourceUri),
                originalFileName = nextJob.fileName,
                plan = plan,
                preferHardware = config.preferHardwareAcceleration,
                customOutputTreeUri = config.outputTreeUri,
                onProgress = { pct ->
                  if (pct != lastPostedProgress) {
                    lastPostedProgress = pct
                    serviceScope.launch { repo.updateProgress(nextJob.id, pct) }
                    updateNotification(nextJob.fileName, pct, sessionCompleted, totalBatch)
                  }
                },
              )

            repo.markCompleted(
              id = nextJob.id,
              outputUri = result.outputUri.toString(),
              outputFileName = result.outputFileName,
              outputSizeBytes = result.outputSizeBytes,
              usedHardwareEncoder = result.usedHardwareEncoder,
              encoderName = result.encoderName,
            )
            sessionCompleted++
            sessionSavedBytes += (nextJob.originalSizeBytes - result.outputSizeBytes).coerceAtLeast(0L)
          } catch (cancelled: CancellationException) {
            if (isPauseRequested) {
              repo.updateStatusAndProgress(nextJob.id, JobStatus.PAUSED, 0)
            } else {
              val latest = repo.getJobById(nextJob.id)
              if (latest?.status == JobStatus.PROCESSING) {
                repo.updateStatusAndProgress(nextJob.id, JobStatus.CANCELLED, 0)
              }
            }
            if (isPauseRequested) break
          } catch (t: Throwable) {
            if (isPauseRequested) {
              repo.updateStatusAndProgress(nextJob.id, JobStatus.PAUSED, 0)
              break
            } else {
              val latest = repo.getJobById(nextJob.id)
              if (latest?.status != JobStatus.CANCELLED) {
                repo.markFailed(
                  nextJob.id,
                  t.localizedMessage ?: "Compression failed on device codec",
                )
                sessionFailed++
              }
            }
          } finally {
            activeJobId = null
          }
        }

        if (!isPauseRequested && (sessionCompleted > 0 || sessionFailed > 0)) {
          NotificationHelper.postCompletionNotification(
            context = this@CompressionForegroundService,
            completedCount = sessionCompleted,
            failedCount = sessionFailed,
            savedFormatted = FormatUtils.formatBytes(sessionSavedBytes),
          )
        }

        ServiceCompat.stopForeground(
          this@CompressionForegroundService,
          ServiceCompat.STOP_FOREGROUND_REMOVE,
        )
        stopSelf()
      }
  }

  private fun handlePause() {
    isPauseRequested = true
    val app = application as CompressXApplication
    app.compressionEngine.cancelActive()
    serviceScope.launch {
      app.queueRepository.pauseActiveProcessingJobs()
      workerJob?.cancel()
      ServiceCompat.stopForeground(
        this@CompressionForegroundService,
        ServiceCompat.STOP_FOREGROUND_REMOVE,
      )
      stopSelf()
    }
  }

  private fun handleCancelActive() {
    val targetId = activeJobId
    val app = application as CompressXApplication
    serviceScope.launch {
      if (targetId != null) {
        app.queueRepository.cancelJob(targetId)
      }
      app.compressionEngine.cancelActive()
    }
  }
}
