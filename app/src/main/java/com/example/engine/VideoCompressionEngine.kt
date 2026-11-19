package com.example.engine

import android.content.Context
import android.media.MediaCodecInfo
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
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.model.CodecCapabilities
import com.example.model.CompressionPlan
import com.example.model.OutputMode
import com.example.model.VideoCodec
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
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
  val stagedTempPath: String? = null,
)

@OptIn(UnstableApi::class)
data class CompressionAttemptSpec(
  val label: String,
  val mimeType: String,
  val targetWidth: Int,
  val targetHeight: Int,
  val shouldScale: Boolean,
  val targetVideoBitrateBps: Int,
  val applyBitrateSetting: Boolean,
  val forceSoftwareEncoder: Boolean,
  val hdrMode: Int,
  val removeAudio: Boolean = false,
)

@OptIn(UnstableApi::class)
class VideoCompressionEngine(
  private val context: Context,
  private val outputStorageWriter: OutputStorageWriter,
) {

  companion object {
    private const val TAG = "CompressXEngine"
  }

  private val mainHandler = Handler(Looper.getMainLooper())

  @Volatile private var activeTransformer: Transformer? = null
  @Volatile private var activeJobId: String? = null

  fun workDirectory(): File = File(context.cacheDir, "compressx_work").apply { mkdirs() }

  fun deleteTempFileForJob(jobId: String) {
    runCatching {
      val temp = File(workDirectory(), "${jobId}.tmp.mp4")
      if (temp.exists()) temp.delete()
    }.onFailure { CompressionLogger.w(TAG, "Failed deleting temp file for job $jobId", it) }
  }

  fun cleanupOrphanedTempFiles() {
    runCatching {
      workDirectory().listFiles()?.forEach { file ->
        if (file.name.endsWith(".tmp.mp4")) {
          file.delete()
        }
      }
    }.onFailure { CompressionLogger.w(TAG, "Failed cleaning orphaned temp files", it) }
  }

  fun cancelJob(targetJobId: String) {
    mainHandler.post {
      if (activeJobId == targetJobId) {
        runCatching { activeTransformer?.cancel() }
          .onFailure { CompressionLogger.w(TAG, "Error cancelling transformer for job $targetJobId", it) }
        activeTransformer = null
        activeJobId = null
      }
    }
  }

  fun cancelActive() {
    mainHandler.post {
      runCatching { activeTransformer?.cancel() }
        .onFailure { CompressionLogger.w(TAG, "Error cancelling active transformer", it) }
      activeTransformer = null
      activeJobId = null
    }
  }

  fun stagedDirectory(): File = File(context.filesDir, "compressx_staged").apply { mkdirs() }

  suspend fun compressVideo(
    jobId: String,
    sourceUri: Uri,
    originalFileName: String,
    plan: CompressionPlan,
    preferHardware: Boolean,
    outputMode: OutputMode,
    customOutputTreeUri: String?,
    onProgress: (Int) -> Unit,
  ): CompressionExecutionResult {
    val workDir = workDirectory()
    val tempOutputFile = File(workDir, "${jobId}.tmp.mp4")
    if (tempOutputFile.exists()) tempOutputFile.delete()

    try {
      // Pre-flight source readability check so missing SAF/Picker permissions fail cleanly
      withContext(Dispatchers.IO) { verifySourceReadable(sourceUri) }

      val capabilities = CodecCapabilityInspector.inspect()
      val cascade = buildAttemptCascade(plan, preferHardware, capabilities)

      var attemptResult: ExportResult? = null
      var usedAttemptSpec: CompressionAttemptSpec = cascade.first()
      var lastError: Throwable? = null

      for ((index, spec) in cascade.withIndex()) {
        if (tempOutputFile.exists()) tempOutputFile.delete()
        if (index > 0) {
          onProgress(0)
        }
        CompressionLogger.i(
          TAG,
          "Job $jobId attempt ${index + 1}/${cascade.size} [${spec.label}]: mime=${spec.mimeType}, scale=${spec.shouldScale} (${spec.targetWidth}x${spec.targetHeight}), forceSW=${spec.forceSoftwareEncoder}, hdrMode=${spec.hdrMode}",
        )
        try {
          attemptResult =
            runTransformerAttempt(
              jobId = jobId,
              sourceUri = sourceUri,
              tempOutputFile = tempOutputFile,
              spec = spec,
              onProgress = onProgress,
            )
          usedAttemptSpec = spec
          break
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (t: Throwable) {
          lastError = t
          CompressionLogger.w(
            TAG,
            "Job $jobId attempt '${spec.label}' failed: ${CompressionLogger.formatUserErrorMessage(t)}",
            t,
          )
          if (isNonRetryableSourceError(t)) {
            throw t
          }
          // If audio processing specifically failed, insert an audio-safe software fallback attempt next
          if (
            index == cascade.lastIndex &&
              !spec.removeAudio &&
              isAudioProcessingError(t)
          ) {
            CompressionLogger.w(TAG, "Job $jobId retrying with software H.264 audio bypass fallback")
            if (tempOutputFile.exists()) tempOutputFile.delete()
            onProgress(0)
            val audioSafeSpec =
              spec.copy(
                label = "SW H.264 (Audio Fallback)",
                mimeType = MimeTypes.VIDEO_H264,
                forceSoftwareEncoder = true,
                applyBitrateSetting = false,
                removeAudio = true,
              )
            attemptResult =
              runTransformerAttempt(
                jobId = jobId,
                sourceUri = sourceUri,
                tempOutputFile = tempOutputFile,
                spec = audioSafeSpec,
                onProgress = onProgress,
              )
            usedAttemptSpec = audioSafeSpec
            break
          }
        }
      }

      val finalExport =
        attemptResult
          ?: throw (lastError ?: IOException("Compression failed across all hardware and software fallback attempts"))

      // Verify encoded temp file integrity & playability via MediaMetadataRetriever
      withContext(Dispatchers.IO) {
        verifyEncodedOutputPlayable(tempOutputFile)
      }

      val encoderName =
        finalExport.videoEncoderName
          ?: if (usedAttemptSpec.forceSoftwareEncoder) {
            capabilities.encoders
              .firstOrNull { it.mimeType == usedAttemptSpec.mimeType && it.isSoftwareFallback }
              ?.codecName ?: "Software MediaCodec"
          } else if (finalExport.videoMimeType == MimeTypes.VIDEO_H265) {
            capabilities.preferredH265Encoder ?: "MediaCodec HEVC"
          } else {
            capabilities.preferredH264Encoder ?: "MediaCodec AVC"
          }

      val isHw =
        if (usedAttemptSpec.forceSoftwareEncoder) {
          false
        } else {
          !encoderName.lowercase().startsWith("omx.google.") &&
            !encoderName.lowercase().startsWith("c2.android.") &&
            capabilities.hasAnyHardwareEncoder
        }

      if (outputMode == OutputMode.REPLACE_ORIGINAL) {
        val stagedFile =
          withContext(Dispatchers.IO) {
            val dest = File(stagedDirectory(), "${jobId}.staged.mp4")
            if (dest.exists()) dest.delete()
            if (!tempOutputFile.renameTo(dest)) {
              tempOutputFile.copyTo(dest, overwrite = true)
              tempOutputFile.delete()
            }
            verifyEncodedOutputPlayable(dest)
            dest
          }

        CompressionLogger.i(
          TAG,
          "Job $jobId verified & staged for replacement [${usedAttemptSpec.label}]: encoder=$encoderName (hw=$isHw), size=${stagedFile.length()} B",
        )

        return CompressionExecutionResult(
          outputUri = Uri.fromFile(stagedFile),
          outputFileName = originalFileName,
          outputSizeBytes = stagedFile.length(),
          usedHardwareEncoder = isHw,
          encoderName = encoderName,
          stagedTempPath = stagedFile.absolutePath,
        )
      }

      val saved =
        withContext(Dispatchers.IO) {
          outputStorageWriter.writeTempFileToDestination(
            tempFile = tempOutputFile,
            originalFileName = originalFileName,
            customTreeUriString = customOutputTreeUri,
          )
        }

      // Verify saved MediaStore/SAF destination URI is readable and non-empty
      withContext(Dispatchers.IO) { verifyOutputUriReadable(saved.outputUri) }

      CompressionLogger.i(
        TAG,
        "Job $jobId completed [${usedAttemptSpec.label}]: encoder=$encoderName (hw=$isHw), output=${saved.outputFileName} (${saved.outputSizeBytes} B)",
      )

      return CompressionExecutionResult(
        outputUri = saved.outputUri,
        outputFileName = saved.outputFileName,
        outputSizeBytes = saved.outputSizeBytes,
        usedHardwareEncoder = isHw,
        encoderName = encoderName,
        stagedTempPath = null,
      )
    } finally {
      if (tempOutputFile.exists()) {
        runCatching { tempOutputFile.delete() }
          .onFailure { CompressionLogger.w(TAG, "Failed deleting temp file ${tempOutputFile.name}", it) }
      }
    }
  }

  internal fun buildAttemptCascade(
    plan: CompressionPlan,
    preferHardware: Boolean,
    capabilities: CodecCapabilities,
  ): List<CompressionAttemptSpec> {
    val requestedCodec =
      if (plan.mimeType == MimeTypes.VIDEO_H265) VideoCodec.H265 else VideoCodec.H264
    val requestedCodecHasHw =
      when (requestedCodec) {
        VideoCodec.H264 -> capabilities.h264HardwareSupported
        VideoCodec.H265 -> capabilities.h265HardwareSupported
      }
    val primaryForceSoftware = !preferHardware || !requestedCodecHasHw
    val primaryHdrMode =
      if (requestedCodec == VideoCodec.H264) {
        Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
      } else {
        Composition.HDR_MODE_KEEP_HDR
      }

    val attempts = mutableListOf<CompressionAttemptSpec>()

    // Tier 1: User-configured codec & scale (HW preferred if enabled & available, else SW)
    attempts +=
      CompressionAttemptSpec(
        label = if (primaryForceSoftware) "SW ${requestedCodec.shortTag}" else "HW ${requestedCodec.shortTag}",
        mimeType = plan.mimeType,
        targetWidth = plan.targetDisplayWidth,
        targetHeight = plan.targetDisplayHeight,
        shouldScale = plan.shouldScale,
        targetVideoBitrateBps = plan.targetVideoBitrateBps,
        applyBitrateSetting = true,
        forceSoftwareEncoder = primaryForceSoftware,
        hdrMode = primaryHdrMode,
      )

    // Tier 2: Software H.264 fallback preserving requested scaling + OpenGL HDR->SDR tone mapping
    val swH264PrimaryAlready =
      requestedCodec == VideoCodec.H264 &&
        primaryForceSoftware &&
        primaryHdrMode == Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
    if (!swH264PrimaryAlready) {
      attempts +=
        CompressionAttemptSpec(
          label = "SW H.264 Fallback",
          mimeType = MimeTypes.VIDEO_H264,
          targetWidth = plan.targetDisplayWidth,
          targetHeight = plan.targetDisplayHeight,
          shouldScale = plan.shouldScale,
          targetVideoBitrateBps = plan.targetVideoBitrateBps,
          applyBitrateSetting = true,
          forceSoftwareEncoder = true,
          hdrMode = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
        )
    }

    // Tier 3: Safe Baseline Software H.264 (MediaCodec HDR tone-map, default bitrate, safe <=720p cap if high res)
    val shortSide = minOf(plan.targetDisplayWidth, plan.targetDisplayHeight)
    val needsSafeDownscale = shortSide > 720
    val safeScaleFactor = if (needsSafeDownscale && shortSide > 0) 720f / shortSide.toFloat() else 1f
    val safeW = ensureEven((plan.targetDisplayWidth * safeScaleFactor).toInt())
    val safeH = ensureEven((plan.targetDisplayHeight * safeScaleFactor).toInt())

    attempts +=
      CompressionAttemptSpec(
        label = "Safe Compatibility SW H.264",
        mimeType = MimeTypes.VIDEO_H264,
        targetWidth = safeW,
        targetHeight = safeH,
        shouldScale = needsSafeDownscale,
        targetVideoBitrateBps = plan.targetVideoBitrateBps.coerceAtMost(2_400_000),
        applyBitrateSetting = false,
        forceSoftwareEncoder = true,
        hdrMode = Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC,
      )

    return attempts
  }

  internal fun buildEncoderSelector(forceSoftware: Boolean): EncoderSelector {
    if (!forceSoftware) return EncoderSelector.DEFAULT
    return EncoderSelector { mimeType ->
      val defaultInfos =
        runCatching { EncoderSelector.DEFAULT.selectEncoderInfos(mimeType) }
          .onFailure { CompressionLogger.w(TAG, "EncoderSelector.DEFAULT failed for $mimeType", it) }
          .getOrNull()
          ?.toList()
          .orEmpty()

      val swFromDefault = CodecCapabilityInspector.filterSoftwareEncoders(defaultInfos)
      if (swFromDefault.isNotEmpty()) {
        return@EncoderSelector ImmutableList.copyOf(swFromDefault)
      }

      val swDirect = CodecCapabilityInspector.findSoftwareEncoderInfos(mimeType)
      if (swDirect.isNotEmpty()) {
        return@EncoderSelector ImmutableList.copyOf(swDirect)
      }

      CompressionLogger.w(
        TAG,
        "No explicit software encoder found for $mimeType; falling back to default selector",
      )
      ImmutableList.copyOf(defaultInfos)
    }
  }

  private suspend fun runTransformerAttempt(
    jobId: String,
    sourceUri: Uri,
    tempOutputFile: File,
    spec: CompressionAttemptSpec,
    onProgress: (Int) -> Unit,
  ): ExportResult =
    withContext(Dispatchers.Main) {
      suspendCancellableCoroutine { continuation: CancellableContinuation<ExportResult> ->
        val finished = AtomicBoolean(false)
        var progressRunnable: Runnable? = null

        fun failAttempt(error: Throwable) {
          if (!finished.compareAndSet(false, true)) return
          progressRunnable?.let { mainHandler.removeCallbacks(it) }
          if (activeJobId == jobId) {
            activeTransformer = null
            activeJobId = null
          }
          if (continuation.isActive) {
            continuation.resumeWithException(error)
          }
        }

        try {
          val videoEffects =
            if (spec.shouldScale && spec.targetWidth > 0 && spec.targetHeight > 0) {
              listOf(
                Presentation.createForWidthAndHeight(
                  spec.targetWidth,
                  spec.targetHeight,
                  Presentation.LAYOUT_SCALE_TO_FIT,
                )
              )
            } else {
              emptyList()
            }

          val mediaItem = MediaItem.fromUri(sourceUri)
          val editedMediaItem =
            EditedMediaItem.Builder(mediaItem)
              .setEffects(Effects(emptyList(), videoEffects))
              .setRemoveAudio(spec.removeAudio)
              .build()

          val sequence = EditedMediaItemSequence.Builder(editedMediaItem).build()
          val composition =
            Composition.Builder(sequence)
              .setHdrMode(spec.hdrMode)
              .build()

          val encoderSelector = buildEncoderSelector(spec.forceSoftwareEncoder)
          val videoEncoderSettings =
            if (spec.applyBitrateSetting) {
              VideoEncoderSettings.Builder()
                .setBitrate(spec.targetVideoBitrateBps)
                .build()
            } else {
              VideoEncoderSettings.DEFAULT
            }

          val encoderFactory =
            DefaultEncoderFactory.Builder(context)
              .setVideoEncoderSelector(encoderSelector)
              .setRequestedVideoEncoderSettings(videoEncoderSettings)
              .setEnableFallback(true)
              .build()

          val listener =
            object : Transformer.Listener {
              override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (!finished.compareAndSet(false, true)) return
                progressRunnable?.let { mainHandler.removeCallbacks(it) }
                if (activeJobId == jobId) {
                  activeTransformer = null
                  activeJobId = null
                }
                runCatching { onProgress(100) }
                if (continuation.isActive) {
                  continuation.resume(exportResult)
                }
              }

              override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
              ) {
                failAttempt(exportException)
              }
            }

          val transformer =
            Transformer.Builder(context)
              .setVideoMimeType(spec.mimeType)
              .setAudioMimeType(MimeTypes.AUDIO_AAC)
              .setEncoderFactory(encoderFactory)
              .addListener(listener)
              .build()

          activeTransformer = transformer
          activeJobId = jobId

          continuation.invokeOnCancellation {
            finished.set(true)
            progressRunnable?.let { mainHandler.removeCallbacks(it) }
            mainHandler.post {
              runCatching { transformer.cancel() }
                .onFailure { CompressionLogger.w(TAG, "Failed cancelling transformer", it) }
              if (activeTransformer === transformer) {
                activeTransformer = null
                activeJobId = null
              }
            }
          }

          val progressHolder = ProgressHolder()
          val poller =
            object : Runnable {
              override fun run() {
                if (finished.get() || !continuation.isActive) return
                try {
                  val state = transformer.getProgress(progressHolder)
                  if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(progressHolder.progress.coerceIn(0, 99))
                  }
                  if (state != Transformer.PROGRESS_STATE_NOT_STARTED && !finished.get()) {
                    mainHandler.postDelayed(this, 300L)
                  }
                } catch (t: Throwable) {
                  CompressionLogger.e(TAG, "Progress polling error during Transformer execution", t)
                  runCatching { transformer.cancel() }
                  failAttempt(t)
                }
              }
            }
          progressRunnable = poller

          transformer.start(composition, tempOutputFile.absolutePath)
          mainHandler.postDelayed(poller, 300L)
        } catch (t: Throwable) {
          CompressionLogger.e(TAG, "Synchronous Transformer setup/start error [${spec.label}]", t)
          failAttempt(t)
        }
      }
    }

  private fun verifySourceReadable(sourceUri: Uri) {
    try {
      val descriptor = context.contentResolver.openAssetFileDescriptor(sourceUri, "r")
      if (descriptor != null) {
        descriptor.close()
        return
      }
      val stream = context.contentResolver.openInputStream(sourceUri)
      if (stream != null) {
        stream.close()
        return
      }
      throw FileNotFoundException("Unable to open input stream for $sourceUri")
    } catch (t: Throwable) {
      CompressionLogger.e(TAG, "Pre-flight source URI check failed for $sourceUri", t)
      throw t
    }
  }

  internal fun verifyEncodedOutputPlayable(file: File) {
    if (!file.exists() || file.length() < 1024L) {
      throw IOException("Compressed output file is empty or corrupt (${file.length()} bytes)")
    }
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(file.absolutePath)
      val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
      val duration =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      val width =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      val height =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      if (hasVideo != "yes" || duration <= 0L || width <= 0 || height <= 0) {
        throw IOException(
          "Output MP4 verification failed: invalid video track (hasVideo=$hasVideo, duration=$duration, ${width}x$height)",
        )
      }
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun verifyOutputUriReadable(outputUri: Uri) {
    try {
      val afd = context.contentResolver.openAssetFileDescriptor(outputUri, "r")
      if (afd != null) {
        afd.use {
          if (it.length == 0L) {
            throw IOException("Saved output MP4 has 0 bytes at $outputUri")
          }
        }
        return
      }
      val stream = context.contentResolver.openInputStream(outputUri)
      if (stream != null) {
        stream.close()
        return
      }
      throw IOException("Saved output MP4 cannot be opened for playback verification")
    } catch (t: Throwable) {
      CompressionLogger.e(TAG, "Saved output readability verification failed for $outputUri", t)
      throw t
    }
  }

  private fun isNonRetryableSourceError(t: Throwable): Boolean {
    if (t is SecurityException || t is FileNotFoundException) return true
    if (t is ExportException) {
      return t.errorCode == ExportException.ERROR_CODE_IO_FILE_NOT_FOUND ||
        t.errorCode == ExportException.ERROR_CODE_IO_NO_PERMISSION
    }
    return false
  }

  private fun isAudioProcessingError(t: Throwable): Boolean {
    return t is ExportException && t.errorCode == ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED
  }

  private fun ensureEven(value: Int): Int {
    val clamped = value.coerceAtLeast(2)
    return if (clamped % 2 == 0) clamped else clamped - 1
  }
}
