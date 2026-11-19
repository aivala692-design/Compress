package com.example.data

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.engine.CompressionLogger
import com.example.engine.CompressionPlanCalculator
import com.example.engine.EligibilityAssessment
import com.example.engine.FileFingerprintUtil
import com.example.engine.MediaReplacementManager
import com.example.engine.OutputStorageWriter
import com.example.model.BatchSummaryStats
import com.example.model.JobStatus
import com.example.model.OutputMode
import com.example.model.ReplacementPermissionStatus
import com.example.model.VideoCompressionConfig
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class EnqueueBatchResult(
  val addedJobs: List<VideoJobEntity>,
  val skippedDuplicateCount: Int,
  val skippedActiveCount: Int = 0,
  val skippedCompletedCount: Int = 0,
  val skippedInBatchCount: Int = 0,
)

data class RetryFailedResult(
  val retriedCount: Int,
  val needsRelinkRemainingCount: Int,
)

sealed interface RelinkResult {
  data class Success(val job: VideoJobEntity) : RelinkResult

  data class DuplicateConflict(val message: String) : RelinkResult

  data class InvalidFile(val message: String) : RelinkResult

  data object NotFound : RelinkResult
}

class VideoQueueRepository(
  private val context: Context,
  private val dao: VideoJobDao,
  val mediaReplacementManager: MediaReplacementManager =
    MediaReplacementManager(context, OutputStorageWriter(context)),
) {

  val allJobs: Flow<List<VideoJobEntity>> = dao.observeAllJobs()

  val completedHistory: Flow<List<VideoJobEntity>> = dao.observeCompletedHistory()

  suspend fun enqueueVideos(
    uris: List<Uri>,
    config: VideoCompressionConfig,
  ): Int = enqueueVideosDetailed(uris, config).addedJobs.size

  suspend fun enqueueVideosDetailed(
    uris: List<Uri>,
    config: VideoCompressionConfig,
    deduplicateActive: Boolean = true,
  ): EnqueueBatchResult =
    withContext(Dispatchers.IO) {
      if (uris.isEmpty()) return@withContext EnqueueBatchResult(emptyList(), 0)

      val existingEntries =
        if (deduplicateActive) dao.getDeduplicationEntries() else emptyList()
      val existingActiveUris = hashSetOf<String>()
      val existingCompletedUris = hashSetOf<String>()
      val existingActiveFingerprints = hashSetOf<String>()
      val existingCompletedFingerprints = hashSetOf<String>()

      for (entry in existingEntries) {
        if (entry.status == JobStatus.COMPLETED) {
          existingCompletedUris += entry.sourceUri
          entry.contentFingerprint?.let { existingCompletedFingerprints += it }
        } else {
          existingActiveUris += entry.sourceUri
          entry.contentFingerprint?.let { existingActiveFingerprints += it }
        }
      }

      val seenBatchUris = hashSetOf<String>()
      val candidateUris = mutableListOf<Uri>()
      var skippedActiveCount = 0
      var skippedCompletedCount = 0
      var skippedInBatchCount = 0

      // Fast first-pass URI deduplication before disk probing
      for (uri in uris) {
        val uriKey = uri.toString()
        if (!seenBatchUris.add(uriKey)) {
          skippedInBatchCount++
          continue
        }
        if (deduplicateActive) {
          if (uriKey in existingActiveUris) {
            skippedActiveCount++
            continue
          }
          if (uriKey in existingCompletedUris) {
            skippedCompletedCount++
            continue
          }
        }
        candidateUris += uri
      }

      if (candidateUris.isEmpty()) {
        val totalSkipped = skippedActiveCount + skippedCompletedCount + skippedInBatchCount
        return@withContext EnqueueBatchResult(
          addedJobs = emptyList(),
          skippedDuplicateCount = totalSkipped,
          skippedActiveCount = skippedActiveCount,
          skippedCompletedCount = skippedCompletedCount,
          skippedInBatchCount = skippedInBatchCount,
        )
      }

      // Bounded parallel metadata + content fingerprint probing via ContentResolver
      val semaphore = Semaphore(4)
      val probedResults =
        coroutineScope {
          candidateUris
            .map { uri ->
              async(Dispatchers.IO) {
                semaphore.withPermit {
                  val hasPersistable = tryTakePersistablePermission(uri)
                  val eligibility = mediaReplacementManager.assessEligibility(uri)
                  val probed = probeVideoMetadata(uri)
                  val fingerprint =
                    FileFingerprintUtil.computeContentFingerprint(
                      context = context,
                      uri = uri,
                      sizeBytes = probed.sizeBytes,
                      durationMs = probed.durationMs,
                      width = probed.width,
                      height = probed.height,
                    )
                  ProbedCandidate(
                    uri = uri,
                    probed = probed,
                    eligibility = eligibility,
                    fingerprint = fingerprint,
                    hasPersistablePermission = hasPersistable,
                  )
                }
              }
            }
            .awaitAll()
        }

      // Second-pass content-fingerprint deduplication catches identical videos picked via
      // different URIs (Photo Picker vs SAF vs MediaStore vs Share Sheet) AND within the batch
      val seenBatchFingerprints = hashSetOf<String>()
      val acceptedCandidates = mutableListOf<ProbedCandidate>()

      for (candidate in probedResults) {
        val fp = candidate.fingerprint
        if (fp != null) {
          if (!seenBatchFingerprints.add(fp)) {
            skippedInBatchCount++
            continue
          }
          if (deduplicateActive) {
            if (fp in existingActiveFingerprints) {
              skippedActiveCount++
              continue
            }
            if (fp in existingCompletedFingerprints) {
              skippedCompletedCount++
              continue
            }
          }
        }
        acceptedCandidates += candidate
      }

      val totalSkipped = skippedActiveCount + skippedCompletedCount + skippedInBatchCount
      if (acceptedCandidates.isEmpty()) {
        return@withContext EnqueueBatchResult(
          addedJobs = emptyList(),
          skippedDuplicateCount = totalSkipped,
          skippedActiveCount = skippedActiveCount,
          skippedCompletedCount = skippedCompletedCount,
          skippedInBatchCount = skippedInBatchCount,
        )
      }

      var nextSort = dao.getMaxSortOrder() + 1
      val effectiveMode =
        if (config.isReplaceOriginalActive) OutputMode.REPLACE_ORIGINAL else OutputMode.KEEP_ORIGINAL

      val jobs =
        acceptedCandidates.map { candidate ->
          val probed = candidate.probed
          val eligibility = candidate.eligibility
          val initialPermissionStatus =
            if (!probed.isValid) {
              ReplacementPermissionStatus.FAILED
            } else {
              eligibility.status
            }
          VideoJobEntity(
            id = UUID.randomUUID().toString(),
            sourceUri = candidate.uri.toString(),
            fileName = probed.fileName,
            durationMs = probed.durationMs,
            originalSizeBytes = probed.sizeBytes,
            width = probed.width,
            height = probed.height,
            rotation = probed.rotation,
            sourceBitrateBps = probed.bitrateBps,
            sourceFrameRate = probed.frameRate,
            sourceAudioBitrateBps = probed.audioBitrateBps,
            sortOrder = nextSort++,
            status = if (probed.isValid) JobStatus.QUEUED else JobStatus.FAILED,
            progressPercent = 0,
            preset = config.preset,
            targetResolution = config.resolution,
            targetCodec = config.codec,
            customBitrateKbps = config.customBitrateKbps,
            outputMode = effectiveMode,
            replacementStatus = initialPermissionStatus,
            sourceAuthority = eligibility.authority,
            sourceRelativePath = eligibility.mediaStoreMeta?.relativePath,
            sourceDateTakenMs = eligibility.mediaStoreMeta?.dateTakenMs,
            contentFingerprint = candidate.fingerprint,
            needsRelink = probed.isPermissionError,
            hasPersistablePermission = candidate.hasPersistablePermission,
            errorMessage = probed.errorMessage,
          )
        }

      jobs.chunked(50).forEach { chunk -> dao.insertJobs(chunk) }
      EnqueueBatchResult(
        addedJobs = jobs,
        skippedDuplicateCount = totalSkipped,
        skippedActiveCount = skippedActiveCount,
        skippedCompletedCount = skippedCompletedCount,
        skippedInBatchCount = skippedInBatchCount,
      )
    }

  suspend fun relinkJob(jobId: String, newUri: Uri): RelinkResult =
    withContext(Dispatchers.IO) {
      val existing = dao.getJobById(jobId) ?: return@withContext RelinkResult.NotFound
      val hasPersistable = tryTakePersistablePermission(newUri)
      val probed = probeVideoMetadata(newUri)
      if (!probed.isValid) {
        return@withContext RelinkResult.InvalidFile(
          probed.errorMessage ?: "Selected file is not a readable video."
        )
      }

      val fingerprint =
        FileFingerprintUtil.computeContentFingerprint(
          context = context,
          uri = newUri,
          sizeBytes = probed.sizeBytes,
          durationMs = probed.durationMs,
          width = probed.width,
          height = probed.height,
        )

      // Prevent relinking to a video that is already queued/processing/completed under another job
      val otherEntries = dao.getDeduplicationEntries().filter { it.id != jobId }
      val newUriStr = newUri.toString()
      val conflict =
        otherEntries.firstOrNull { entry ->
          entry.sourceUri == newUriStr ||
            (fingerprint != null && entry.contentFingerprint == fingerprint)
        }
      if (conflict != null) {
        val reason =
          if (conflict.status == JobStatus.COMPLETED) {
            "That video has already been compressed."
          } else {
            "That video is already in the active queue."
          }
        return@withContext RelinkResult.DuplicateConflict(reason)
      }

      val eligibility = mediaReplacementManager.assessEligibility(newUri)
      deleteTempFilesForJob(existing)

      dao.relinkJobSource(
        id = jobId,
        sourceUri = newUriStr,
        fileName = probed.fileName,
        durationMs = probed.durationMs,
        originalSizeBytes = probed.sizeBytes,
        width = probed.width,
        height = probed.height,
        rotation = probed.rotation,
        sourceBitrateBps = probed.bitrateBps,
        sourceFrameRate = probed.frameRate,
        sourceAudioBitrateBps = probed.audioBitrateBps,
        contentFingerprint = fingerprint,
        hasPersistablePermission = hasPersistable,
        replacementStatus = eligibility.status,
        sourceAuthority = eligibility.authority,
        sourceRelativePath = eligibility.mediaStoreMeta?.relativePath,
        sourceDateTakenMs = eligibility.mediaStoreMeta?.dateTakenMs,
      )

      val updated = dao.getJobById(jobId) ?: return@withContext RelinkResult.NotFound
      CompressionLogger.i("CompressXRepo", "Relinked job $jobId to ${updated.fileName}")
      RelinkResult.Success(updated)
    }

  fun isUriReadable(uri: Uri): Boolean {
    return try {
      context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true }
        ?: context.contentResolver.openInputStream(uri)?.use { true }
        ?: false
    } catch (_: Throwable) {
      false
    }
  }

  private fun tryTakePersistablePermission(uri: Uri): Boolean {
    val taken =
      runCatching {
        context.contentResolver.takePersistableUriPermission(
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
      }.getOrElse {
        runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
          true
        }.getOrDefault(false)
      }
    if (taken) return true
    return runCatching {
      context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }.getOrDefault(false)
  }

  suspend fun applyConfigToQueuedJobs(
    jobIds: List<String>,
    config: VideoCompressionConfig,
  ) =
    withContext(Dispatchers.IO) {
      if (jobIds.isEmpty()) return@withContext
      val effectiveMode =
        if (config.isReplaceOriginalActive) OutputMode.REPLACE_ORIGINAL else OutputMode.KEEP_ORIGINAL
      jobIds.chunked(200).forEach { chunk ->
        dao.updateQueuedJobsConfig(
          ids = chunk,
          preset = config.preset,
          targetResolution = config.resolution,
          targetCodec = config.codec,
          customBitrateKbps = config.customBitrateKbps,
          outputMode = effectiveMode,
        )
      }
    }

  suspend fun applyConfigToAllQueuedJobs(config: VideoCompressionConfig) =
    withContext(Dispatchers.IO) {
      val effectiveMode =
        if (config.isReplaceOriginalActive) OutputMode.REPLACE_ORIGINAL else OutputMode.KEEP_ORIGINAL
      dao.updateAllQueuedJobsConfig(
        preset = config.preset,
        targetResolution = config.resolution,
        targetCodec = config.codec,
        customBitrateKbps = config.customBitrateKbps,
        outputMode = effectiveMode,
      )
    }

  suspend fun getNextQueuedJob(): VideoJobEntity? = dao.getNextQueuedJob()

  suspend fun getAwaitingApprovalJobs(): List<VideoJobEntity> = dao.getAwaitingApprovalJobs()

  suspend fun getJobById(id: String): VideoJobEntity? = dao.getJobById(id)

  suspend fun countPendingOrActive(): Int = dao.countPendingOrActive()

  suspend fun countPendingOrPaused(): Int = dao.countPendingOrPaused()

  suspend fun countCompleted(): Int = dao.countCompleted()

  suspend fun updateStatusAndProgress(id: String, status: JobStatus, progress: Int) =
    dao.updateStatusAndProgress(id, status, progress)

  suspend fun updateProgress(id: String, progress: Int) = dao.updateProgress(id, progress)

  suspend fun updateReplacementStatus(
    id: String,
    replacementStatus: ReplacementPermissionStatus,
    errorMessage: String? = null,
  ) = dao.updateReplacementStatus(id, replacementStatus, errorMessage)

  suspend fun markAwaitingApproval(
    id: String,
    stagedTempPath: String,
    outputSizeBytes: Long,
    usedHardwareEncoder: Boolean,
    encoderName: String,
    replacementStatus: ReplacementPermissionStatus = ReplacementPermissionStatus.PENDING_BATCH_APPROVAL,
    errorMessage: String? = null,
  ) =
    dao.markAwaitingApproval(
      id = id,
      stagedTempPath = stagedTempPath,
      outputSizeBytes = outputSizeBytes,
      usedHardwareEncoder = usedHardwareEncoder,
      encoderName = encoderName,
      replacementStatus = replacementStatus,
      errorMessage = errorMessage,
    )

  suspend fun markAwaitingApprovalError(
    id: String,
    errorMessage: String,
    replacementStatus: ReplacementPermissionStatus = ReplacementPermissionStatus.FAILED,
  ) = dao.markAwaitingApprovalError(id, replacementStatus, errorMessage)

  suspend fun markCompleted(
    id: String,
    replacedOriginal: Boolean,
    outputUri: String,
    outputFileName: String,
    outputSizeBytes: Long,
    usedHardwareEncoder: Boolean,
    encoderName: String,
    replacementStatus: ReplacementPermissionStatus =
      if (replacedOriginal) ReplacementPermissionStatus.REPLACED else ReplacementPermissionStatus.SAVED_COPY,
  ) =
    dao.markCompleted(
      id = id,
      replacedOriginal = replacedOriginal,
      replacementStatus = replacementStatus,
      outputUri = outputUri,
      outputFileName = outputFileName,
      outputSizeBytes = outputSizeBytes,
      usedHardwareEncoder = usedHardwareEncoder,
      encoderName = encoderName,
      completedAt = System.currentTimeMillis(),
    )

  suspend fun markFailed(
    id: String,
    errorMessage: String,
    needsRelink: Boolean = false,
  ) =
    withContext(Dispatchers.IO) {
      val existing = dao.getJobById(id) ?: return@withContext
      deleteTempFilesForJob(existing)
      dao.markFailed(
        id = id,
        errorMessage = errorMessage,
        needsRelink = needsRelink,
        completedAt = System.currentTimeMillis(),
      )
    }

  suspend fun reorderJobs(orderedIds: List<String>) =
    withContext(Dispatchers.IO) { dao.reorderJobs(orderedIds) }

  suspend fun resetInterruptedProcessingJobs() =
    withContext(Dispatchers.IO) { dao.resetInterruptedProcessingJobs() }

  suspend fun resumeAllPausedJobs() =
    withContext(Dispatchers.IO) { dao.resumeAllPausedJobs() }

  suspend fun pauseActiveProcessingJobs() =
    withContext(Dispatchers.IO) { dao.pauseActiveProcessingJobs() }

  suspend fun pauseAllPendingAndProcessingJobs() =
    withContext(Dispatchers.IO) { dao.pauseAllPendingAndProcessingJobs() }

  /**
   * Retries ONLY FAILED jobs (never COMPLETED or CANCELLED).
   * Jobs marked needsRelink are re-tested first: if their URI is readable again, they are retried;
   * otherwise they stay clearly flagged for one-tap Relink instead of immediately failing again.
   */
  suspend fun retryAllFailedJobs(): RetryFailedResult =
    withContext(Dispatchers.IO) {
      val failedJobs = dao.getFailedJobs()
      var needsRelinkRemaining = 0
      for (job in failedJobs) {
        deleteTempFilesForJob(job)
        if (job.needsRelink) {
          val readable = runCatching { isUriReadable(Uri.parse(job.sourceUri)) }.getOrDefault(false)
          if (readable) {
            dao.clearNeedsRelink(job.id)
          } else {
            needsRelinkRemaining++
          }
        }
      }
      val retried = dao.retryAllFailedJobs()
      RetryFailedResult(
        retriedCount = retried,
        needsRelinkRemainingCount = needsRelinkRemaining,
      )
    }

  suspend fun retryJob(id: String): Boolean =
    withContext(Dispatchers.IO) {
      val existing = dao.getJobById(id) ?: return@withContext false
      if (existing.status != JobStatus.FAILED && existing.status != JobStatus.CANCELLED) {
        return@withContext false
      }
      deleteTempFilesForJob(existing)
      dao.retryJob(id) > 0
    }

  suspend fun cancelJob(id: String) =
    withContext(Dispatchers.IO) {
      val existing = dao.getJobById(id) ?: return@withContext
      deleteTempFilesForJob(existing)
      dao.updateStatusAndProgress(id, JobStatus.CANCELLED, 0)
    }

  suspend fun deleteJob(id: String) =
    withContext(Dispatchers.IO) {
      val existing = runCatching { dao.getJobById(id) }.getOrNull()
      if (existing != null) {
        deleteTempFilesForJob(existing)
      }
      runCatching { dao.deleteJob(id) }
        .onFailure { CompressionLogger.e("CompressXRepo", "Failed to delete job $id", it) }
    }

  suspend fun clearCompletedAndCancelled() =
    withContext(Dispatchers.IO) { dao.clearCompletedAndCancelled() }

  suspend fun clearAllNonProcessing() =
    withContext(Dispatchers.IO) {
      dao.getAwaitingApprovalJobs().forEach { job -> deleteTempFilesForJob(job) }
      dao.clearAllNonProcessing()
    }

  suspend fun clearAll() =
    withContext(Dispatchers.IO) {
      dao.getAwaitingApprovalJobs().forEach { job -> deleteTempFilesForJob(job) }
      dao.clearAllJobs()
    }

  private fun deleteTempFilesForJob(job: VideoJobEntity) {
    job.stagedTempPath?.let { path ->
      runCatching { File(path).delete() }
        .onFailure { CompressionLogger.w("CompressXRepo", "Cleanup of staged file failed", it) }
    }
    runCatching {
      val workTmp = File(File(context.cacheDir, "compressx_work"), "${job.id}.tmp.mp4")
      if (workTmp.exists()) workTmp.delete()
    }
  }

  fun computeStats(
    jobs: List<VideoJobEntity>,
    hasHardwareEncoder: Boolean = true,
    preferHardware: Boolean = true,
  ): BatchSummaryStats {
    var queued = 0
    var processing = 0
    var paused = 0
    var awaitingApproval = 0
    var completed = 0
    var replaced = 0
    var failed = 0
    var needsRelink = 0
    var replaceEligible = 0
    var readOnly = 0
    var originalCompleted = 0L
    var outputCompleted = 0L
    var originalPending = 0L
    var estMinOutPending = 0L
    var estMaxOutPending = 0L
    var estProcessingSecPending = 0
    var activeProgressSum = 0

    for (job in jobs) {
      if (job.needsRelink && job.status == JobStatus.FAILED) {
        needsRelink++
      }

      when (job.replacementStatus) {
        ReplacementPermissionStatus.ELIGIBLE,
        ReplacementPermissionStatus.PENDING_BATCH_APPROVAL,
        ReplacementPermissionStatus.BATCH_APPROVED,
        ReplacementPermissionStatus.DENIED -> {
          if (job.status != JobStatus.FAILED && job.status != JobStatus.CANCELLED) {
            replaceEligible++
          }
        }
        ReplacementPermissionStatus.READ_ONLY -> {
          if (job.status != JobStatus.COMPLETED && job.status != JobStatus.CANCELLED) {
            readOnly++
          }
        }
        else -> Unit
      }

      when (job.status) {
        JobStatus.QUEUED -> {
          queued++
          val est =
            CompressionPlanCalculator.estimateJobCompression(
              durationMs = job.durationMs,
              originalSizeBytes = job.originalSizeBytes,
              sourceWidth = job.width,
              sourceHeight = job.height,
              sourceRotation = job.rotation,
              sourceBitrateBps = job.sourceBitrateBps,
              sourceFrameRate = job.sourceFrameRate,
              sourceAudioBitrateBps = job.sourceAudioBitrateBps,
              preset = job.preset,
              targetResolution = job.targetResolution,
              targetCodec = job.targetCodec,
              customBitrateKbps = job.customBitrateKbps,
              hasHardwareEncoder = hasHardwareEncoder,
              preferHardware = preferHardware,
            )
          originalPending += est.originalBytes
          estMinOutPending += est.minOutputBytes
          estMaxOutPending += est.maxOutputBytes
          estProcessingSecPending += est.estimatedProcessingSeconds
        }
        JobStatus.PROCESSING -> {
          processing++
          activeProgressSum += job.progressPercent
          val est =
            CompressionPlanCalculator.estimateJobCompression(
              durationMs = job.durationMs,
              originalSizeBytes = job.originalSizeBytes,
              sourceWidth = job.width,
              sourceHeight = job.height,
              sourceRotation = job.rotation,
              sourceBitrateBps = job.sourceBitrateBps,
              sourceFrameRate = job.sourceFrameRate,
              sourceAudioBitrateBps = job.sourceAudioBitrateBps,
              preset = job.preset,
              targetResolution = job.targetResolution,
              targetCodec = job.targetCodec,
              customBitrateKbps = job.customBitrateKbps,
              hasHardwareEncoder = hasHardwareEncoder,
              preferHardware = preferHardware,
            )
          originalPending += est.originalBytes
          estMinOutPending += est.minOutputBytes
          estMaxOutPending += est.maxOutputBytes
          estProcessingSecPending +=
            ((est.estimatedProcessingSeconds * (100 - job.progressPercent)) / 100.0).toInt()
        }
        JobStatus.PAUSED -> {
          paused++
          activeProgressSum += job.progressPercent
          val est =
            CompressionPlanCalculator.estimateJobCompression(
              durationMs = job.durationMs,
              originalSizeBytes = job.originalSizeBytes,
              sourceWidth = job.width,
              sourceHeight = job.height,
              sourceRotation = job.rotation,
              sourceBitrateBps = job.sourceBitrateBps,
              sourceFrameRate = job.sourceFrameRate,
              sourceAudioBitrateBps = job.sourceAudioBitrateBps,
              preset = job.preset,
              targetResolution = job.targetResolution,
              targetCodec = job.targetCodec,
              customBitrateKbps = job.customBitrateKbps,
              hasHardwareEncoder = hasHardwareEncoder,
              preferHardware = preferHardware,
            )
          originalPending += est.originalBytes
          estMinOutPending += est.minOutputBytes
          estMaxOutPending += est.maxOutputBytes
          estProcessingSecPending += est.estimatedProcessingSeconds
        }
        JobStatus.AWAITING_APPROVAL -> {
          awaitingApproval++
          activeProgressSum += 95
        }
        JobStatus.COMPLETED -> {
          completed++
          if (job.replacedOriginal) replaced++
          originalCompleted += job.originalSizeBytes
          outputCompleted += job.outputSizeBytes
          activeProgressSum += 100
        }
        JobStatus.FAILED -> failed++
        JobStatus.CANCELLED -> {}
      }
    }

    val totalWorkItems = queued + processing + paused + awaitingApproval + completed
    val overallProgress =
      if (totalWorkItems > 0) (activeProgressSum / totalWorkItems).coerceIn(0, 100) else 0
    val minSavedPending = (originalPending - estMaxOutPending).coerceAtLeast(0L)
    val maxSavedPending = (originalPending - estMinOutPending).coerceAtLeast(0L)
    val minPctPending =
      if (originalPending > 0L)
        ((minSavedPending.toDouble() / originalPending.toDouble()) * 100.0).toInt().coerceIn(0, 99)
      else 0
    val maxPctPending =
      if (originalPending > 0L)
        ((maxSavedPending.toDouble() / originalPending.toDouble()) * 100.0)
          .toInt()
          .coerceIn(minPctPending, 99)
      else 0

    return BatchSummaryStats(
      queuedCount = queued,
      processingCount = processing,
      pausedCount = paused,
      awaitingApprovalCount = awaitingApproval,
      completedCount = completed,
      replacedCount = replaced,
      failedCount = failed,
      needsRelinkCount = needsRelink,
      replaceEligibleCount = replaceEligible,
      readOnlyCount = readOnly,
      totalOriginalBytesCompleted = originalCompleted,
      totalOutputBytesCompleted = outputCompleted,
      totalOriginalBytesPending = originalPending,
      estMinOutputBytesPending = estMinOutPending,
      estMaxOutputBytesPending = estMaxOutPending,
      estMinSavedBytesPending = minSavedPending,
      estMaxSavedBytesPending = maxSavedPending,
      estMinReductionPercentPending = minPctPending,
      estMaxReductionPercentPending = maxPctPending,
      estProcessingSecondsPending = estProcessingSecPending,
      overallProgressPercent = overallProgress,
    )
  }

  private data class ProbedCandidate(
    val uri: Uri,
    val probed: ProbedVideoInfo,
    val eligibility: EligibilityAssessment,
    val fingerprint: String?,
    val hasPersistablePermission: Boolean,
  )

  private data class ProbedVideoInfo(
    val fileName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val bitrateBps: Int,
    val frameRate: Float,
    val audioBitrateBps: Int,
    val isValid: Boolean,
    val isPermissionError: Boolean,
    val errorMessage: String?,
  )

  private fun probeVideoMetadata(uri: Uri): ProbedVideoInfo {
    var displayName =
      uri.lastPathSegment
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() && it.contains('.') }
        ?: "shared_video_${System.currentTimeMillis()}.mp4"
    var fileSize = 0L

    runCatching {
      context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
              cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
            }
            if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
              fileSize = cursor.getLong(sizeIdx)
            }
          }
        }
    }

    if (fileSize <= 0L) {
      fileSize =
        runCatching {
          context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            val declared = afd.length
            if (declared > 0L) declared else afd.parcelFileDescriptor.statSize.coerceAtLeast(0L)
          } ?: 0L
        }.getOrDefault(0L)
    }

    if (fileSize <= 0L) {
      fileSize =
        runCatching {
          context.contentResolver.openInputStream(uri)?.use { stream ->
            var count = 0L
            val buf = ByteArray(16 * 1024)
            while (true) {
              val read = stream.read(buf)
              if (read <= 0) break
              count += read
            }
            count
          } ?: 0L
        }.getOrDefault(0L)
    }

    var detectedFps = 0f
    var detectedAudioBps = 0
    val extractor = MediaExtractor()
    try {
      extractor.setDataSource(context, uri, null)
      for (idx in 0 until extractor.trackCount) {
        val format = extractor.getTrackFormat(idx)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith("video/") && detectedFps <= 0f && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
          detectedFps =
            runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
              .getOrElse {
                runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f)
              }
        } else if (mime.startsWith("audio/") && detectedAudioBps <= 0 && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
          detectedAudioBps = runCatching { format.getInteger(MediaFormat.KEY_BIT_RATE) }.getOrDefault(0)
        }
      }
    } catch (_: Throwable) {
      // Non-fatal; MediaMetadataRetriever handles fallback
    } finally {
      runCatching { extractor.release() }
    }

    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(context, uri)
      val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
      val duration =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      val width =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      val height =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      val rotation =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
          ?: 0
      val bitrate =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
      if (detectedFps <= 0f) {
        detectedFps =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            ?.toFloatOrNull() ?: 0f
      }
      if (displayName.startsWith("shared_video_")) {
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
          ?.trim()
          ?.takeIf { it.isNotEmpty() }
          ?.let { title ->
            displayName = if (title.contains('.')) title else "$title.mp4"
          }
      }

      if (hasVideo != "yes" || width <= 0 || height <= 0) {
        ProbedVideoInfo(
          fileName = displayName,
          sizeBytes = fileSize,
          durationMs = duration,
          width = width,
          height = height,
          rotation = rotation,
          bitrateBps = bitrate,
          frameRate = detectedFps,
          audioBitrateBps = detectedAudioBps,
          isValid = false,
          isPermissionError = false,
          errorMessage = "Unsupported or corrupt video stream (no valid video track)",
        )
      } else {
        ProbedVideoInfo(
          fileName = displayName,
          sizeBytes = fileSize,
          durationMs = duration,
          width = width,
          height = height,
          rotation = rotation,
          bitrateBps = bitrate,
          frameRate = detectedFps,
          audioBitrateBps = detectedAudioBps,
          isValid = true,
          isPermissionError = false,
          errorMessage = null,
        )
      }
    } catch (se: SecurityException) {
      ProbedVideoInfo(
        fileName = displayName,
        sizeBytes = fileSize,
        durationMs = 0L,
        width = 0,
        height = 0,
        rotation = 0,
        bitrateBps = 0,
        frameRate = detectedFps,
        audioBitrateBps = detectedAudioBps,
        isValid = false,
        isPermissionError = true,
        errorMessage = "Storage permission lost for this video. Tap Relink File to re-select it.",
      )
    } catch (t: Throwable) {
      ProbedVideoInfo(
        fileName = displayName,
        sizeBytes = fileSize,
        durationMs = 0L,
        width = 0,
        height = 0,
        rotation = 0,
        bitrateBps = 0,
        frameRate = detectedFps,
        audioBitrateBps = detectedAudioBps,
        isValid = false,
        isPermissionError = t is java.io.FileNotFoundException,
        errorMessage = t.message ?: "Unable to read video metadata",
      )
    } finally {
      runCatching { retriever.release() }
    }
  }
}
