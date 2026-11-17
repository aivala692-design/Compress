package com.example.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.example.model.BatchSummaryStats
import com.example.model.JobStatus
import com.example.model.VideoCompressionConfig
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class VideoQueueRepository(
  private val context: Context,
  private val dao: VideoJobDao,
) {

  val allJobs: Flow<List<VideoJobEntity>> = dao.observeAllJobs()

  val completedHistory: Flow<List<VideoJobEntity>> = dao.observeCompletedHistory()

  suspend fun enqueueVideos(
    uris: List<Uri>,
    config: VideoCompressionConfig,
  ): Int =
    withContext(Dispatchers.IO) {
      if (uris.isEmpty()) return@withContext 0
      var nextSort = dao.getMaxSortOrder() + 1
      val jobs = mutableListOf<VideoJobEntity>()

      for (uri in uris) {
        runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        }
        val probed = probeVideoMetadata(uri)
        jobs +=
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
            sortOrder = nextSort++,
            status = if (probed.isValid) JobStatus.QUEUED else JobStatus.FAILED,
            progressPercent = 0,
            preset = config.preset,
            targetResolution = config.resolution,
            targetCodec = config.codec,
            customBitrateKbps = config.customBitrateKbps,
            errorMessage = probed.errorMessage,
          )
      }

      // Chunk inserts cleanly so even 200+ item batches stay fast and transactional
      jobs.chunked(50).forEach { chunk -> dao.insertJobs(chunk) }
      jobs.size
    }

  suspend fun getNextQueuedJob(): VideoJobEntity? = dao.getNextQueuedJob()

  suspend fun getJobById(id: String): VideoJobEntity? = dao.getJobById(id)

  suspend fun countPendingOrActive(): Int = dao.countPendingOrActive()

  suspend fun updateStatusAndProgress(id: String, status: JobStatus, progress: Int) =
    dao.updateStatusAndProgress(id, status, progress)

  suspend fun updateProgress(id: String, progress: Int) = dao.updateProgress(id, progress)

  suspend fun markCompleted(
    id: String,
    outputUri: String,
    outputFileName: String,
    outputSizeBytes: Long,
    usedHardwareEncoder: Boolean,
    encoderName: String,
  ) =
    dao.markCompleted(
      id = id,
      outputUri = outputUri,
      outputFileName = outputFileName,
      outputSizeBytes = outputSizeBytes,
      usedHardwareEncoder = usedHardwareEncoder,
      encoderName = encoderName,
      completedAt = System.currentTimeMillis(),
    )

  suspend fun markFailed(id: String, errorMessage: String) =
    dao.markFailed(id, errorMessage, System.currentTimeMillis())

  suspend fun reorderJobs(orderedIds: List<String>) = dao.reorderJobs(orderedIds)

  suspend fun resetInterruptedProcessingJobs() = dao.resetInterruptedProcessingJobs()

  suspend fun resumeAllPausedJobs() = dao.resumeAllPausedJobs()

  suspend fun pauseActiveProcessingJobs() = dao.pauseActiveProcessingJobs()

  suspend fun retryAllFailedJobs() = dao.retryAllFailedJobs()

  suspend fun retryJob(id: String) = dao.retryJob(id)

  suspend fun cancelJob(id: String) =
    dao.updateStatusAndProgress(id, JobStatus.CANCELLED, 0)

  suspend fun deleteJob(id: String) = dao.deleteJob(id)

  suspend fun clearCompletedAndCancelled() = dao.clearCompletedAndCancelled()

  suspend fun clearAllNonProcessing() = dao.clearAllNonProcessing()

  suspend fun clearAll() = dao.clearAllJobs()

  fun computeStats(jobs: List<VideoJobEntity>): BatchSummaryStats {
    var queued = 0
    var processing = 0
    var paused = 0
    var completed = 0
    var failed = 0
    var originalCompleted = 0L
    var outputCompleted = 0L
    var activeProgressSum = 0

    for (job in jobs) {
      when (job.status) {
        JobStatus.QUEUED -> queued++
        JobStatus.PROCESSING -> {
          processing++
          activeProgressSum += job.progressPercent
        }
        JobStatus.PAUSED -> {
          paused++
          activeProgressSum += job.progressPercent
        }
        JobStatus.COMPLETED -> {
          completed++
          originalCompleted += job.originalSizeBytes
          outputCompleted += job.outputSizeBytes
          activeProgressSum += 100
        }
        JobStatus.FAILED -> failed++
        JobStatus.CANCELLED -> {}
      }
    }

    val totalWorkItems = queued + processing + paused + completed
    val overallProgress =
      if (totalWorkItems > 0) (activeProgressSum / totalWorkItems).coerceIn(0, 100) else 0

    return BatchSummaryStats(
      queuedCount = queued,
      processingCount = processing,
      pausedCount = paused,
      completedCount = completed,
      failedCount = failed,
      totalOriginalBytesCompleted = originalCompleted,
      totalOutputBytesCompleted = outputCompleted,
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
    val isValid: Boolean,
    val errorMessage: String?,
  )

  private fun probeVideoMetadata(uri: Uri): ProbedVideoInfo {
    var displayName = "video_${System.currentTimeMillis()}.mp4"
    var fileSize = 0L

    runCatching {
      context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
              displayName = cursor.getString(nameIdx)
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
          context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
        }.getOrDefault(0L)
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

      if (hasVideo != "yes" || width <= 0 || height <= 0) {
        ProbedVideoInfo(
          fileName = displayName,
          sizeBytes = fileSize,
          durationMs = duration,
          width = width,
          height = height,
          rotation = rotation,
          bitrateBps = bitrate,
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
        isValid = false,
        errorMessage = t.message ?: "Unable to read video metadata",
      )
    } finally {
      runCatching { retriever.release() }
    }
  }
}
