package com.example.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.model.CompressionPlan
import com.example.model.VideoCodec
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CompressionExecutionResult(
  val outputUri: Uri,
  val outputFileName: String,
  val outputSizeBytes: Long,
  val usedHardwareEncoder: Boolean,
  val encoderName: String,
)

@OptIn(UnstableApi::class)
class VideoCompressionEngine(
  private val context: Context,
  private val outputStorageWriter: OutputStorageWriter,
) {

  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var activeTransformer: Transformer? = null

  fun workDirectory(): File = File(context.cacheDir, "compressx_work").apply { mkdirs() }

  fun cleanupOrphanedTempFiles() {
    runCatching {
      workDirectory().listFiles()?.forEach { file ->
        if (file.name.endsWith(".tmp.mp4")) {
          file.delete()
        }
      }
    }
  }

  fun cancelActive() {
    mainHandler.post {
      runCatching { activeTransformer?.cancel() }
      activeTransformer = null
    }
  }

  suspend fun compressVideo(
    jobId: String,
    sourceUri: Uri,
    originalFileName: String,
    plan: CompressionPlan,
    preferHardware: Boolean,
    customOutputTreeUri: String?,
    onProgress: (Int) -> Unit,
  ): CompressionExecutionResult {
    val workDir = workDirectory()
    val tempOutputFile = File(workDir, "${jobId}.tmp.mp4")
    if (tempOutputFile.exists()) tempOutputFile.delete()

    try {
      val capabilities = CodecCapabilityInspector.inspect()
      val requestedCodec =
        if (plan.mimeType == MimeTypes.VIDEO_H265) VideoCodec.H265 else VideoCodec.H264

      val attemptResult =
        try {
          runTransformerAttempt(
            sourceUri = sourceUri,
            tempOutputFile = tempOutputFile,
            plan = plan,
            mimeType = plan.mimeType,
            onProgress = onProgress,
          )
        } catch (primaryError: ExportException) {
          // Automatic fallback: if H.265 or hardware pipeline failed, retry cleanly with H.264 AVC + fallback enabled
          if (tempOutputFile.exists()) tempOutputFile.delete()
          if (requestedCodec == VideoCodec.H265 && capabilities.supportsCodec(VideoCodec.H264)) {
            onProgress(0)
            runTransformerAttempt(
              sourceUri = sourceUri,
              tempOutputFile = tempOutputFile,
              plan = plan.copy(mimeType = MimeTypes.VIDEO_H264),
              mimeType = MimeTypes.VIDEO_H264,
              onProgress = onProgress,
            )
          } else {
            throw primaryError
          }
        }

      // Verify encoded temp file integrity & playability via MediaMetadataRetriever
      withContext(Dispatchers.IO) {
        verifyEncodedOutputPlayable(tempOutputFile)
      }

      val saved =
        withContext(Dispatchers.IO) {
          outputStorageWriter.writeTempFileToDestination(
            tempFile = tempOutputFile,
            originalFileName = originalFileName,
            customTreeUriString = customOutputTreeUri,
          )
        }

      val encoderName =
        attemptResult.videoEncoderName
          ?: if (attemptResult.videoMimeType == MimeTypes.VIDEO_H265) {
            capabilities.preferredH265Encoder ?: "MediaCodec HEVC"
          } else {
            capabilities.preferredH264Encoder ?: "MediaCodec AVC"
          }

      val isHw =
        if (!preferHardware) {
          false
        } else {
          !encoderName.lowercase().startsWith("omx.google.") &&
            !encoderName.lowercase().startsWith("c2.android.") &&
            capabilities.hasAnyHardwareEncoder
        }

      return CompressionExecutionResult(
        outputUri = saved.outputUri,
        outputFileName = saved.outputFileName,
        outputSizeBytes = saved.outputSizeBytes,
        usedHardwareEncoder = isHw,
        encoderName = encoderName,
      )
    } finally {
      if (tempOutputFile.exists()) {
        runCatching { tempOutputFile.delete() }
      }
    }
  }

  private suspend fun runTransformerAttempt(
    sourceUri: Uri,
    tempOutputFile: File,
    plan: CompressionPlan,
    mimeType: String,
    onProgress: (Int) -> Unit,
  ): ExportResult =
    withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { continuation: CancellableContinuation<ExportResult> ->
        val videoEffects =
          if (plan.shouldScale) {
            listOf(Presentation.createForHeight(plan.targetDisplayHeight))
          } else {
            emptyList()
          }

        val mediaItem = MediaItem.fromUri(sourceUri)
        val editedMediaItem =
          EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val videoEncoderSettings =
          VideoEncoderSettings.Builder()
            .setBitrate(plan.targetVideoBitrateBps)
            .build()

        val encoderFactory =
          DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(videoEncoderSettings)
            .setEnableFallback(true)
            .build()

        var finished = false
        var progressRunnable: Runnable? = null

        val listener =
          object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
              if (finished) return
              finished = true
              progressRunnable?.let { mainHandler.removeCallbacks(it) }
              activeTransformer = null
              onProgress(100)
              if (continuation.isActive) {
                continuation.resume(exportResult)
              }
            }

            override fun onError(
              composition: Composition,
              exportResult: ExportResult,
              exportException: ExportException,
            ) {
              if (finished) return
              finished = true
              progressRunnable?.let { mainHandler.removeCallbacks(it) }
              activeTransformer = null
              if (continuation.isActive) {
                continuation.resumeWithException(exportException)
              }
            }
          }

        val transformer =
          Transformer.Builder(context)
            .setVideoMimeType(mimeType)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(listener)
            .build()

        activeTransformer = transformer

        continuation.invokeOnCancellation {
          finished = true
          progressRunnable?.let { mainHandler.removeCallbacks(it) }
          mainHandler.post {
            runCatching { transformer.cancel() }
            if (activeTransformer === transformer) activeTransformer = null
          }
        }

        val progressHolder = ProgressHolder()
        val poller =
          object : Runnable {
            override fun run() {
              if (finished || !continuation.isActive) return
              val state = transformer.getProgress(progressHolder)
              if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                onProgress(progressHolder.progress.coerceIn(0, 99))
              }
              if (state != Transformer.PROGRESS_STATE_NOT_STARTED && !finished) {
                mainHandler.postDelayed(this, 300L)
              }
            }
          }
        progressRunnable = poller

        transformer.start(editedMediaItem, tempOutputFile.absolutePath)
        mainHandler.postDelayed(poller, 300L)
      }
    }

  private fun verifyEncodedOutputPlayable(file: File) {
    if (!file.exists() || file.length() < 1024L) {
      throw IOException("Compressed output file is empty or corrupt")
    }
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(file.absolutePath)
      val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
      val duration =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      if (hasVideo != "yes" || duration <= 0L) {
        throw IOException("Output MP4 verification failed: missing valid video track")
      }
    } finally {
      runCatching { retriever.release() }
    }
  }
}
