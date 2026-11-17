package com.example.engine

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CompressionLogger {

  private const val MAX_MEMORY_ENTRIES = 120
  private const val MAX_LOG_FILE_BYTES = 512L * 1024L

  private val recentEntries = ArrayDeque<String>(MAX_MEMORY_ENTRIES)
  private val lock = Any()
  @Volatile private var logFile: File? = null

  fun init(context: Context) {
    synchronized(lock) {
      val dir = File(context.cacheDir, "compressx_logs")
      if (!dir.exists()) {
        dir.mkdirs()
      }
      logFile = File(dir, "compressx_diagnostics.log")
    }
  }

  fun i(tag: String, message: String) {
    Log.i(tag, message)
    record("INFO", tag, message, null)
  }

  fun w(tag: String, message: String, throwable: Throwable? = null) {
    if (throwable != null) {
      Log.w(tag, message, throwable)
    } else {
      Log.w(tag, message)
    }
    record("WARN", tag, message, throwable)
  }

  fun e(tag: String, message: String, throwable: Throwable? = null) {
    if (throwable != null) {
      Log.e(tag, message, throwable)
    } else {
      Log.e(tag, message)
    }
    record("ERROR", tag, message, throwable)
  }

  fun getRecentDiagnostics(limit: Int = 40): List<String> =
    synchronized(lock) { recentEntries.takeLast(limit) }

  @OptIn(UnstableApi::class)
  fun formatUserErrorMessage(throwable: Throwable): String {
    val exportEx = findCause<ExportException>(throwable)
    val codecEx = findCause<MediaCodec.CodecException>(throwable)
    val secEx = findCause<SecurityException>(throwable)
    val fnfEx = findCause<FileNotFoundException>(throwable)

    if (secEx != null) {
      return "Storage permission lost for this video. Please re-select the file."
    }
    if (fnfEx != null) {
      return "Source video file could not be opened or was moved."
    }

    if (exportEx != null) {
      val codeName = runCatching { exportEx.errorCodeName }.getOrDefault("EXPORT_ERROR_${exportEx.errorCode}")
      val reason =
        when (exportEx.errorCode) {
          ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
          ExportException.ERROR_CODE_IO_NO_PERMISSION ->
            "Source video unavailable or permission expired"
          ExportException.ERROR_CODE_DECODER_INIT_FAILED,
          ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "Device cannot decode input video format"
          ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
          ExportException.ERROR_CODE_ENCODING_FAILED,
          ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
            "Video encoder failed after hardware & software H.264 fallback"
          ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ->
            "Video frame / HDR processing failed on GPU"
          ExportException.ERROR_CODE_AUDIO_PROCESSING_FAILED ->
            "Audio track transcoding failed"
          ExportException.ERROR_CODE_MUXING_FAILED,
          ExportException.ERROR_CODE_MUXING_TIMEOUT ->
            "MP4 muxer failed while writing output"
          else -> exportEx.localizedMessage ?: "Media3 Transformer export failed"
        }
      val codecDetail = exportEx.codecInfo?.toString()?.takeIf { it.isNotBlank() }
      val nestedMsg =
        codecEx?.diagnosticInfo?.takeIf { it.isNotBlank() }
          ?: deepestCauseMessage(exportEx)?.takeIf { it != reason }

      return buildString {
        append(reason)
        append(" [")
        append(codeName)
        append("]")
        if (!codecDetail.isNullOrBlank()) {
          append(" • ")
          append(codecDetail.take(80))
        }
        if (!nestedMsg.isNullOrBlank()) {
          append(": ")
          append(nestedMsg.take(120))
        }
      }
    }

    if (codecEx != null) {
      return "MediaCodec failure: ${codecEx.diagnosticInfo.ifBlank { codecEx.localizedMessage ?: "Codec reset" }}"
    }

    val direct = throwable.localizedMessage?.takeIf { it.isNotBlank() }
    val root = deepestCauseMessage(throwable)
    return when {
      direct != null && root != null && direct != root -> "$direct ($root)"
      direct != null -> direct
      root != null -> "${throwable.javaClass.simpleName}: $root"
      else -> "${throwable.javaClass.simpleName}: Compression failed"
    }
  }

  private fun record(level: String, tag: String, message: String, throwable: Throwable?) {
    val stamp =
      synchronized(lock) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line =
          buildString {
            append(ts)
            append(" ")
            append(level)
            append("/")
            append(tag)
            append(": ")
            append(message)
            if (throwable != null) {
              append('\n')
              append(Log.getStackTraceString(throwable))
            }
          }
        if (recentEntries.size >= MAX_MEMORY_ENTRIES) {
          recentEntries.removeFirst()
        }
        recentEntries.addLast(line)
        line
      }

    val target = logFile ?: return
    try {
      synchronized(lock) {
        if (target.exists() && target.length() > MAX_LOG_FILE_BYTES) {
          val tail = target.readLines().takeLast(200).joinToString("\n")
          target.writeText("$tail\n")
        }
        target.appendText("$stamp\n")
      }
    } catch (io: Throwable) {
      Log.w("CompressXLogger", "Unable to persist diagnostic log line: ${io.message}")
    }
  }

  private inline fun <reified T : Throwable> findCause(root: Throwable): T? {
    var current: Throwable? = root
    var depth = 0
    while (current != null && depth < 12) {
      if (current is T) return current
      current = current.cause
      depth++
    }
    return null
  }

  private fun deepestCauseMessage(root: Throwable): String? {
    var current: Throwable? = root
    var lastMessage: String? = root.localizedMessage
    var depth = 0
    while (current != null && depth < 12) {
      current.localizedMessage?.takeIf { it.isNotBlank() }?.let { lastMessage = it }
      current = current.cause
      depth++
    }
    return lastMessage
  }
}
