package com.example.service

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import com.example.data.PreferencesRepository
import com.example.data.VideoJobEntity
import com.example.data.VideoQueueRepository
import com.example.engine.CompressionLogger
import com.example.engine.CompressionPlanCalculator
import com.example.engine.DeviceGuardrailMonitor
import com.example.engine.VideoCompressionEngine
import com.example.model.JobStatus
import com.example.model.OutputMode
import com.example.model.ReplacementPermissionStatus
import com.example.ui.components.FormatUtils
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BatchRunSummary(
  val completedCount: Int,
  val awaitingApprovalCount: Int,
  val failedCount: Int,
  val savedBytes: Long,
  val wasPaused: Boolean,
  val pauseReason: String? = null,
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

  private val runMutex = Mutex()
  private val pauseRequested = AtomicBoolean(false)
  private val cancelledJobIds = ConcurrentHashMap.newKeySet<String>()

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

  /**
   * Cancels or removes ONLY the specified job without interrupting or failing the rest of the queue.
   */
  fun cancelSpecificJob(jobId: String) {
    cancelledJobIds.add(jobId)
    if (activeJobId == jobId) {
      engine.cancelJob(jobId)
    }
  }

  suspend fun runBatch(
    progressScope: CoroutineScope,
    onJobStarted: (fileName: String, completed: Int, total: Int) -> Unit,
    onProgressChanged: (fileName: String, progress: Int, completed: Int, total: Int) -> Unit,
    onStatusNote: (fileName: String, completed: Int, total: Int, note: String) -> Unit,
  ): BatchRunSummary =
    runMutex.withLock {
      var sessionCompleted = 0
      var sessionAwaitingApproval = 0
      var sessionFailed = 0
      var sessionSavedBytes = 0L
      var pauseReason: String? = null

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

        // Skip cleanly if this job was deleted/cancelled just before dequeue
        if (cancelledJobIds.remove(nextJob.id)) {
          continue
        }

        val config =
          try {
            prefs.configFlow.first()
          } catch (t: Throwable) {
            CompressionLogger.e(TAG, "Failed reading preferences; pausing batch safely", t)
            pauseRequested.set(true)
            pauseReason = "Unable to read compression preferences"
            break
          }

        val totalPending =
          runCatching { repo.countPendingOrActive().coerceAtLeast(1) }.getOrDefault(1)
        val totalBatch = sessionCompleted + sessionAwaitingApproval + totalPending

        // Guardrail checks before encoding each video
        val estimatedNeededBytes =
          maxOf(
            DeviceGuardrailMonitor.LOW_STORAGE_THRESHOLD_BYTES,
            (nextJob.originalSizeBytes * 1.1).toLong(),
          )
        val guardrail = guardrails.sample(estimatedNeededBytes)

        // CRITICAL FIX: Never cascade-fail all remaining queued jobs when free space drops below
        // the global 500 MB threshold. Pause the batch cleanly so remaining jobs stay QUEUED/PAUSED!
        if (
          guardrail.isLowStorage &&
            guardrail.availableStorageBytes < DeviceGuardrailMonitor.LOW_STORAGE_THRESHOLD_BYTES
        ) {
          val msg =
            "Paused: low device storage (${FormatUtils.formatBytes(guardrail.availableStorageBytes)} free). Free space and tap Resume."
          CompressionLogger.w(TAG, "Batch paused due to global low storage: $msg")
          pauseRequested.set(true)
          pauseReason = msg
          runCatching { repo.updateStatusAndProgress(nextJob.id, JobStatus.PAUSED, 0) }
          onStatusNote(
            nextJob.fileName,
            sessionCompleted + sessionAwaitingApproval,
            totalBatch,
            msg,
          )
          break
        }

        // Device has >= 500 MB free, but this single unusually large file needs more space than available:
        // isolate failure to ONLY this file and continue processing smaller queued files!
        if (guardrail.isLowStorage) {
          val msg =
            "Not enough free space for this video (${FormatUtils.formatBytes(guardrail.availableStorageBytes)} free, needs ~${FormatUtils.formatBytes(estimatedNeededBytes)})."
          CompressionLogger.w(TAG, "Isolated job ${nextJob.id} skipped: $msg")
          runCatching { repo.markFailed(nextJob.id, msg, needsRelink = false) }
            .onFailure { CompressionLogger.e(TAG, "Failed marking single-file storage failure", it) }
          sessionFailed++
          continue
        }

        if (config.pauseOnLowBattery && guardrail.isLowBattery) {
          pauseRequested.set(true)
          pauseReason = "Paused: low battery (${guardrail.batteryPercent}%)"
          runCatching { repo.updateStatusAndProgress(nextJob.id, JobStatus.PAUSED, 0) }
          onStatusNote(
            nextJob.fileName,
            sessionCompleted + sessionAwaitingApproval,
            totalBatch,
            pauseReason!!,
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

        // Verify the row still exists right before claiming PROCESSING
        val stillPresent = runCatching { repo.getJobById(nextJob.id) }.getOrNull()
        if (stillPresent == null || stillPresent.status != JobStatus.QUEUED) {
          continue
        }

        activeJobId = nextJob.id
        runCatching { repo.updateStatusAndProgress(nextJob.id, JobStatus.PROCESSING, 0) }
          .onFailure {
            CompressionLogger.e(TAG, "Failed updating PROCESSING status for ${nextJob.id}", it)
          }
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
                if (cancelledJobIds.contains(nextJob.id) || pauseRequested.get()) return@compressVideo
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

          // Re-check DB right before persisting completion: if user deleted or cancelled this job
          // while encoding was finishing, discard temp output cleanly and move on to the next job!
          val currentRow = runCatching { repo.getJobById(nextJob.id) }.getOrNull()
          val wasCancelledOrDeleted =
            cancelledJobIds.remove(nextJob.id) ||
              currentRow == null ||
              currentRow.status == JobStatus.CANCELLED
          if (wasCancelledOrDeleted) {
            result.stagedTempPath?.let { path -> runCatching { File(path).delete() } }
            CompressionLogger.i(TAG, "Discarded output for removed/cancelled job ${nextJob.id}")
            continue
          }

          val stagedPath = result.stagedTempPath
          if (effectiveOutputMode == OutputMode.REPLACE_ORIGINAL && !stagedPath.isNullOrBlank()) {
            val assessment = repo.mediaReplacementManager.assessEligibility(Uri.parse(nextJob.sourceUri))
            val awaitingStatus =
              if (assessment.status == ReplacementPermissionStatus.READ_ONLY) {
                ReplacementPermissionStatus.READ_ONLY
              } else {
                ReplacementPermissionStatus.PENDING_BATCH_APPROVAL
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
              replacementStatus = ReplacementPermissionStatus.SAVED_COPY,
            )
            sessionCompleted++
            sessionSavedBytes += (nextJob.originalSizeBytes - result.outputSizeBytes).coerceAtLeast(0L)
          }
        } catch (_: CancellationException) {
          engine.deleteTempFileForJob(nextJob.id)
          val wasTargetedCancel = cancelledJobIds.remove(nextJob.id)
          if (pauseRequested.get()) {
            val latest = runCatching { repo.getJobById(nextJob.id) }.getOrNull()
            if (latest != null && latest.status == JobStatus.PROCESSING) {
              runCatching { repo.updateStatusAndProgress(nextJob.id, JobStatus.PAUSED, 0) }
            }
            break
          } else if (!wasTargetedCancel) {
            val latest = runCatching { repo.getJobById(nextJob.id) }.getOrNull()
            if (latest?.status == JobStatus.PROCESSING) {
              runCatching { repo.updateStatusAndProgress(nextJob.id, JobStatus.CANCELLED, 0) }
            }
          }
          // Continue the batch loop so remaining queued jobs keep running!
        } catch (t: Throwable) {
          engine.deleteTempFileForJob(nextJob.id)
          val wasTargetedCancel = cancelledJobIds.remove(nextJob.id)
          if (pauseRequested.get()) {
            val latest = runCatching { repo.getJobById(nextJob.id) }.getOrNull()
            if (latest != null && latest.status == JobStatus.PROCESSING) {
              runCatching { repo.updateStatusAndProgress(nextJob.id, JobStatus.PAUSED, 0) }
            }
            break
          } else if (!wasTargetedCancel) {
            // Strict per-job isolation: inspect DB row first so deleted/cancelled jobs are never resurrected
            val latest = runCatching { repo.getJobById(nextJob.id) }.getOrNull()
            if (latest != null && latest.status != JobStatus.CANCELLED) {
              val needsRelink = isPermissionOrMissingSourceError(t)
              val formattedError =
                if (needsRelink) {
                  "Storage permission lost for this video. Tap Relink File to re-select it."
                } else {
                  CompressionLogger.formatUserErrorMessage(t)
                }
              CompressionLogger.e(
                TAG,
                "Isolated failure for job ${nextJob.id} (${nextJob.fileName}) [needsRelink=$needsRelink]",
                t,
              )
              runCatching {
                repo.markFailed(
                  id = nextJob.id,
                  errorMessage = formattedError,
                  needsRelink = needsRelink,
                )
              }.onFailure {
                CompressionLogger.e(TAG, "Failed persisting error state for ${nextJob.id}", it)
              }
              sessionFailed++
            }
          }
          // Always continue to the next queued job — one broken file NEVER fails the rest of the batch!
        } finally {
          if (activeJobId == nextJob.id) {
            activeJobId = null
          }
        }
      }

      BatchRunSummary(
        completedCount = sessionCompleted,
        awaitingApprovalCount = sessionAwaitingApproval,
        failedCount = sessionFailed,
        savedBytes = sessionSavedBytes,
        wasPaused = pauseRequested.get(),
        pauseReason = pauseReason,
      )
    }

  @OptIn(UnstableApi::class)
  internal fun isPermissionOrMissingSourceError(t: Throwable): Boolean {
    var cursor: Throwable? = t
    var depth = 0
    while (cursor != null && depth < 6) {
      if (cursor is SecurityException || cursor is FileNotFoundException) return true
      if (cursor is ExportException) {
        if (
          cursor.errorCode == ExportException.ERROR_CODE_IO_NO_PERMISSION ||
            cursor.errorCode == ExportException.ERROR_CODE_IO_FILE_NOT_FOUND
        ) {
          return true
        }
      }
      val msg = cursor.message?.lowercase().orEmpty()
      if (msg.contains("permission") && (msg.contains("denial") || msg.contains("lost") || msg.contains("revoked"))) {
        return true
      }
      cursor = cursor.cause
      depth++
    }
    return false
  }
}
