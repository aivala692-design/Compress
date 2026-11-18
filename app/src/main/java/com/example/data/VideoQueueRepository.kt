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
)

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

      val activeUriSet =
        if (deduplicateActive) dao.getActiveSourceUris().toHashSet() else hashSetOf()
      val seenInBatch = hashSetOf<String>()
      val uniqueUris = mutableListOf<Uri>()
      var duplicateCount = 0

      for (uri in uris) {
        val uriKey = uri.toString()
        if (!seenInBatch.add(uriKey) || (deduplicateActive && uriKey in activeUriSet)) {
          duplicateCount++
          continue
        }
        uniqueUris += uri
      }

      if (uniqueUris.isEmpty()) {
        return@withContext EnqueueBatchResult(emptyList(), duplicateCount)
      }

      // Bounded parallel metadata probing via ContentResolver so 50+ shared videos load quickly
      val semaphore = Semaphore(4)
      val probedResults =
        coroutineScope {
          uniqueUris
            .map { uri ->
              async(Dispatchers.IO) {
                semaphore.withPermit {
                  // Persistable permission works for SAF documents and providers that offer persistable grants
                  runCatching {
                    context.contentResolver.takePersistableUriPermission(
                      uri,
                      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                  }.onFailure {
                    runCatching {
                      context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                      )
                    }
                  }
                  val eligibility = mediaReplacementManager.assessEligibility(uri)
                  Triple(uri, probeVideoMetadata(uri), eligibility)
                }
              }
            }
            .awaitAll()
        }

      var nextSort = dao.getMaxSortOrder() + 1
      val effectiveMode =
        if (config.isReplaceOriginalActive) OutputMode.REPLACE_ORIGINAL else OutputMode.KEEP_ORIGINAL

      val jobs =
        probedResults.map { (uri, probed, eligibility) ->
          val initialPermissionStatus =
            if (!probed.isValid) {
              ReplacementPermissionStatus.FAILED
            } else {
              eligibility.status
            }
          VideoJobEntity(
            id = UUID.randomUUID().toString(),
            sourceUri = uri.toString(),
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
            errorMessage = probed.errorMessage,
          )
        }

      jobs.chunked(50).forEach { chunk -> dao.insertJobs(chunk) }
      EnqueueBatchResult(addedJobs = jobs, skippedDuplicateCount = duplicateCount)
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

  suspend fun markFailed(id: String, errorMessage: String) {
    val existing = dao.getJobById(id)
    existing?.stagedTempPath?.let { path ->
      runCatching { File(path).delete() }
        .onFailure { CompressionLogger.w("CompressXRepo", "Cleanup of staged file failed", it) }
    }
    dao.markFailed(id, errorMessage, System.currentTimeMillis())
  }

  suspend fun reorderJobs(orderedIds: List<String>) = dao.reorderJobs(orderedIds)

  suspend fun resetInterruptedProcessingJobs() = dao.resetInterruptedProcessingJobs()

  suspend fun resumeAllPausedJobs() = dao.resumeAllPausedJobs()

  suspend fun pauseActiveProcessingJobs() = dao.pauseActiveProcessingJobs()

  suspend fun retryAllFailedJobs() = dao.retryAllFailedJobs()

  suspend fun retryJob(id: String) = dao.retryJob(id)

  suspend fun cancelJob(id: String) {
    val existing = dao.getJobById(id)
    existing?.stagedTempPath?.let { path ->
      runCatching { File(path).delete() }
    }
    dao.updateStatusAndProgress(id, JobStatus.CANCELLED, 0)
  }

  suspend fun deleteJob(id: String) {
    val existing = dao.getJobById(id)
    existing?.stagedTempPath?.let { path ->
      runCatching { File(path).delete() }
    }
    dao.deleteJob(id)
  }

  suspend fun clearCompletedAndCancelled() = dao.clearCompletedAndCancelled()

  suspend fun clearAllNonProcessing() {
    dao.getAwaitingApprovalJobs().forEach { job ->
      job.stagedTempPath?.let { path -> runCatching { File(path).delete() } }
    }
    dao.clearAllNonProcessing()
  }

  suspend fun clearAll() {
    dao.getAwaitingApprovalJobs().forEach { job ->
      job.stagedTempPath?.let { path -> runCatching { File(path).delete() } }
    }
    dao.clearAllJobs()
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
      if (originalPending > 0L) ((minSavedPending.toDouble() / originalPending.toDouble()) * 100.0).toInt().coerceIn(0, 99)
      else 0
    val maxPctPending =
      if (originalPending > 0L) ((maxSavedPending.toDouble() / originalPending.toDouble()) * 100.0).toInt().coerceIn(minPctPending, 99)
      else 0

    return BatchSummaryStats(
      queuedCount = queued,
      processingCount = processing,
      pausedCount = paused,
      awaitingApprovalCount = awaitingApproval,
      completedCount = completed,
      replacedCount = replaced,
      failedCount = failed,
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

    // Extract track frame rate & audio bitrate cleanly when container exposes them
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
          errorMessage = null,
        )
      }
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
        errorMessage = t.message ?: "Unable to read video metadata",
      )
    } finally {
      runCatching { retriever.release() }
    }
  }
}
