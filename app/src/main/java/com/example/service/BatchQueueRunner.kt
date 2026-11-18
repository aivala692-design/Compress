package com.example.service

import android.net.Uri
import com.example.data.PreferencesRepository
import com.example.data.VideoJobEntity
import com.example.data.VideoQueueRepository
import com.example.engine.CompressionLogger
import com.example.engine.CompressionPlanCalculator
import com.example.engine.DeviceGuardrailMonitor
import com.example.engine.VideoCompressionEngine
import com.example.model.JobStatus
import com.example.model.OutputMode
import com.example.ui.components.FormatUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class BatchRunSummary(
  val completedCount: Int,
  val awaitingApprovalCount: Int,
  val failedCount: Int,
  val savedBytes: Long,
  val wasPaused: Boolean,
)

class BatchQueueRunner(
  private val repo: VideoQueueRepository,
  private val prefs: PreferencesRepository,
  private val engine: VideoCompressionEngine,
  private val guardrails: DeviceGuardrailMonitor,
) {

  companion object {
    private const val TAG = "CompressXRunner"
  }

  private val pauseRequested = AtomicBoolean(false)
  @Volatile var activeJobId: String? = null
    private set

  fun isPauseRequested(): Boolean = pauseRequested.get()

  fun resetPauseRequest() {
    pauseRequested.set(false)
  }

  fun requestPause() {
    pauseRequested.set(true)
    engine.cancelActive()
  }

  suspend fun runBatch(
    progressScope: CoroutineScope,
    onJobStarted: (fileName: String, completed: Int, total: Int) -> Unit,
    onProgressChanged: (fileName: String, progress: Int, completed: Int, total: Int) -> Unit,
    onStatusNote: (fileName: String, completed: Int, total: Int, note: String) -> Unit,
  ): BatchRunSummary {
    var sessionCompleted = 0
    var sessionAwaitingApproval = 0
    var sessionFailed = 0
    var sessionSavedBytes = 0L

    runCatching {
      repo.resetInterruptedProcessingJobs()
      repo.resumeAllPausedJobs()
      engine.cleanupOrphanedTempFiles()
    }.onFailure {
      CompressionLogger.e(TAG, "Error preparing queue state before batch execution", it)
    }

    while (!pauseRequested.get()) {
      val nextJob: VideoJobEntity =
        try {
          repo.getNextQueuedJob()
        } catch (t: Throwable) {
          CompressionLogger.e(TAG, "Failed querying next queued job", t)
          null
        } ?: break

      val config =
        try {
          prefs.configFlow.first()
        } catch (t: Throwable) {
          CompressionLogger.e(TAG, "Failed reading preferences; pausing batch safely", t)
          break
        }

      val totalPending =
        runCatching { repo.countPendingOrActive().coerceAtLeast(1) }.getOrDefault(1)
      val totalBatch = sessionCompleted + sessionAwaitingApproval + totalPending

      // Guardrail checks before encoding each video
      val estimatedNeededBytes =
        maxOf(
          500L * 1024L * 1024L,
          (nextJob.originalSizeBytes * 1.1).toLong(),
        )
      val guardrail = guardrails.sample(estimatedNeededBytes)

      if (guardrail.isLowStorage) {
        val msg =
          "Insufficient free storage (${FormatUtils.formatBytes(guardrail.availableStorageBytes)} free)."
        CompressionLogger.w(TAG, "Job ${nextJob.id} skipped: $msg")
        runCatching { repo.markFailed(nextJob.id, msg) }
          .onFailure { CompressionLogger.e(TAG, "Failed marking low-storage failure", it) }
        sessionFailed++
        continue
      }

      if (config.pauseOnLowBattery && guardrail.isLowBattery) {
        pauseRequested.set(true)
        runCatching { repo.updateStatusAndProgress(nextJob.id, JobStatus.PAUSED, 0) }
        onStatusNote(
          nextJob.fileName,
          sessionCompleted + sessionAwaitingApproval,
          totalBatch,
          "Paused: low battery (${guardrail.batteryPercent}%)",
        )
        break
      }

      if (config.thermalProtectionEnabled && guardrail.isThrottled) {
        onStatusNote(
          nextJob.fileName,
          sessionCompleted + sessionAwaitingApproval,
          totalBatch,
          "Thermal cooldown active — waiting 15s...",
        )
        delay(15_000L)
        if (pauseRequested.get()) break
      }

      activeJobId = nextJob.id
      runCatching { repo.updateStatusAndProgress(nextJob.id, JobStatus.PROCESSING, 0) }
        .onFailure { CompressionLogger.e(TAG, "Failed updating PROCESSING status for ${nextJob.id}", it) }
      onJobStarted(nextJob.fileName, sessionCompleted + sessionAwaitingApproval, totalBatch)

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
          originalSizeBytes = nextJob.originalSizeBytes,
          durationMs = nextJob.durationMs,
        )

      val effectiveOutputMode =
        if (config.isReplaceOriginalActive) {
          OutputMode.REPLACE_ORIGINAL
        } else {
          OutputMode.KEEP_ORIGINAL
        }

      try {
        var lastDbPct = -1
        var lastNotifPct = -1
        val result =
          engine.compressVideo(
            jobId = nextJob.id,
            sourceUri = Uri.parse(nextJob.sourceUri),
            originalFileName = nextJob.fileName,
            plan = plan,
            preferHardware = config.preferHardwareAcceleration,
            outputMode = effectiveOutputMode,
            customOutputTreeUri = config.outputTreeUri,
            onProgress = { pct ->
              val clamped = pct.coerceIn(0, 100)
              if (clamped == 0 || clamped == 100 || clamped - lastDbPct >= 2) {
                lastDbPct = clamped
                progressScope.launch {
                  runCatching { repo.updateProgress(nextJob.id, clamped) }
                    .onFailure {
                      CompressionLogger.w(TAG, "DB progress update failed for ${nextJob.id}", it)
                    }
                }
              }
              if (clamped != lastNotifPct) {
                lastNotifPct = clamped
                onProgressChanged(
                  nextJob.fileName,
                  clamped,
                  sessionCompleted + sessionAwaitingApproval,
                  totalBatch,
                )
              }
            },
          )

        val stagedPath = result.stagedTempPath
        if (effectiveOutputMode == OutputMode.REPLACE_ORIGINAL && !stagedPath.isNullOrBlank()) {
          // Assess whether the source URI supports batch replacement or is read-only
          val assessment = repo.mediaReplacementManager.assessEligibility(Uri.parse(nextJob.sourceUri))
          val awaitingStatus =
            if (assessment.status == com.example.model.ReplacementPermissionStatus.READ_ONLY) {
              com.example.model.ReplacementPermissionStatus.READ_ONLY
            } else {
              com.example.model.ReplacementPermissionStatus.PENDING_BATCH_APPROVAL
            }
          repo.markAwaitingApproval(
            id = nextJob.id,
            stagedTempPath = stagedPath,
            outputSizeBytes = result.outputSizeBytes,
            usedHardwareEncoder = result.usedHardwareEncoder,
            encoderName = result.encoderName,
            replacementStatus = awaitingStatus,
            errorMessage = assessment.readOnlyReason,
          )
          sessionAwaitingApproval++
        } else {
          repo.markCompleted(
            id = nextJob.id,
            replacedOriginal = false,
            outputUri = result.outputUri.toString(),
            outputFileName = result.outputFileName,
            outputSizeBytes = result.outputSizeBytes,
            usedHardwareEncoder = result.usedHardwareEncoder,
            encoderName = result.encoderName,
            replacementStatus = com.example.model.ReplacementPermissionStatus.SAVED_COPY,
          )
          sessionCompleted++
          sessionSavedBytes += (nextJob.originalSizeBytes - result.outputSizeBytes).coerceAtLeast(0L)
        }
      } catch (_: CancellationException) {
        if (pauseRequested.get()) {
          runCatching { repo.updateStatusAndProgress(nextJob.id, JobStatus.PAUSED, 0) }
        } else {
          val latest = runCatching { repo.getJobById(nextJob.id) }.getOrNull()
          if (latest?.status == JobStatus.PROCESSING) {
            runCatching { repo.updateStatusAndProgress(nextJob.id, JobStatus.CANCELLED, 0) }
          }
        }
        if (pauseRequested.get()) break
      } catch (t: Throwable) {
        CompressionLogger.e(TAG, "Compression failed for job ${nextJob.id} (${nextJob.fileName})", t)
        if (pauseRequested.get()) {
          runCatching { repo.updateStatusAndProgress(nextJob.id, JobStatus.PAUSED, 0) }
          break
        } else {
          val latest = runCatching { repo.getJobById(nextJob.id) }.getOrNull()
          if (latest?.status != JobStatus.CANCELLED) {
            val formattedError = CompressionLogger.formatUserErrorMessage(t)
            runCatching { repo.markFailed(nextJob.id, formattedError) }
              .onFailure { CompressionLogger.e(TAG, "Failed persisting error state for ${nextJob.id}", it) }
            sessionFailed++
          }
        }
      } finally {
        activeJobId = null
      }
    }

    return BatchRunSummary(
      completedCount = sessionCompleted,
      awaitingApprovalCount = sessionAwaitingApproval,
      failedCount = sessionFailed,
      savedBytes = sessionSavedBytes,
      wasPaused = pauseRequested.get(),
    )
  }
}
