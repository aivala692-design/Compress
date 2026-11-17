package com.example.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException

data class SavedOutputResult(
  val outputUri: Uri,
  val outputFileName: String,
  val outputSizeBytes: Long,
)

class OutputStorageWriter(private val context: Context) {

  companion object {
    private const val TAG = "CompressXStorage"
    private const val STREAM_BUFFER_SIZE = 64 * 1024 // 64 KB low-RAM streaming buffer
    private const val DEFAULT_SUBFOLDER = "CompressX Studio"
  }

  fun writeTempFileToDestination(
    tempFile: File,
    originalFileName: String,
    customTreeUriString: String?,
  ): SavedOutputResult {
    if (!tempFile.exists() || tempFile.length() <= 0L) {
      throw IOException("Encoded temporary output is missing or empty")
    }

    val desiredName = CompressionPlanCalculator.buildCompressedFileName(originalFileName)

    if (!customTreeUriString.isNullOrBlank()) {
      val treeDoc =
        runCatching {
          val treeUri = Uri.parse(customTreeUriString)
          DocumentFile.fromTreeUri(context, treeUri)
        }
          .onFailure {
            CompressionLogger.w(TAG, "Unable to access custom SAF output folder; falling back to Movies", it)
          }
          .getOrNull()

      val canWriteSaf = runCatching { treeDoc != null && treeDoc.canWrite() }.getOrDefault(false)
      if (treeDoc != null && canWriteSaf) {
        return writeToSafDirectory(treeDoc, tempFile, desiredName)
      } else {
        CompressionLogger.w(
          TAG,
          "Custom SAF output folder unavailable or lacks write permission; saving to Movies/$DEFAULT_SUBFOLDER",
        )
      }
    }

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      writeToMediaStoreQ(tempFile, desiredName)
    } else {
      writeToAppExternalMoviesLegacy(tempFile, desiredName)
    }
  }

  private fun writeToSafDirectory(
    treeDoc: DocumentFile,
    tempFile: File,
    desiredName: String,
  ): SavedOutputResult {
    val uniqueName = resolveUniqueDocumentName(treeDoc, desiredName)
    val targetDoc =
      treeDoc.createFile("video/mp4", uniqueName)
        ?: throw IOException("Unable to create output file '$uniqueName' in selected folder")

    try {
      val outputStream =
        context.contentResolver.openOutputStream(targetDoc.uri)
          ?: throw IOException("Unable to open output stream for selected folder")
      outputStream.use { out ->
        FileInputStream(tempFile).use { input ->
          input.copyTo(out, STREAM_BUFFER_SIZE)
        }
        out.flush()
      }
      CompressionLogger.i(TAG, "Saved compressed MP4 via SAF: ${targetDoc.name ?: uniqueName}")
      return SavedOutputResult(
        outputUri = targetDoc.uri,
        outputFileName = targetDoc.name ?: uniqueName,
        outputSizeBytes = tempFile.length(),
      )
    } catch (t: Throwable) {
      CompressionLogger.e(TAG, "Failed writing SAF output '${targetDoc.uri}'", t)
      runCatching { targetDoc.delete() }
        .onFailure { CompressionLogger.w(TAG, "Cleanup of failed SAF doc failed", it) }
      throw t
    }
  }

  private fun writeToMediaStoreQ(tempFile: File, desiredName: String): SavedOutputResult {
    val resolver = context.contentResolver
    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val candidates = buildCandidateFileNames(desiredName)

    var lastError: Throwable? = null
    for (candidateName in candidates) {
      val values =
        ContentValues().apply {
          put(MediaStore.Video.Media.DISPLAY_NAME, candidateName)
          put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
          put(
            MediaStore.Video.Media.RELATIVE_PATH,
            "${Environment.DIRECTORY_MOVIES}/$DEFAULT_SUBFOLDER",
          )
          put(MediaStore.Video.Media.IS_PENDING, 1)
        }

      val itemUri =
        try {
          resolver.insert(collection, values)
        } catch (t: Throwable) {
          lastError = t
          CompressionLogger.w(TAG, "MediaStore insert failed for candidate '$candidateName'", t)
          null
        } ?: continue

      try {
        val outStream =
          resolver.openOutputStream(itemUri)
            ?: throw IOException("Failed to open MediaStore output stream for $candidateName")
        outStream.use { out ->
          FileInputStream(tempFile).use { input ->
            input.copyTo(out, STREAM_BUFFER_SIZE)
          }
          out.flush()
        }
        val publishValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(itemUri, publishValues, null, null)

        val actualName =
          runCatching {
            resolver.query(itemUri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use {
              if (it.moveToFirst()) it.getString(0) else candidateName
            }
          }.getOrNull() ?: candidateName

        CompressionLogger.i(TAG, "Saved compressed MP4 to MediaStore: $actualName ($itemUri)")
        return SavedOutputResult(
          outputUri = itemUri,
          outputFileName = actualName,
          outputSizeBytes = tempFile.length(),
        )
      } catch (t: Throwable) {
        lastError = t
        CompressionLogger.e(TAG, "Failed streaming compressed MP4 to MediaStore '$candidateName'", t)
        runCatching { resolver.delete(itemUri, null, null) }
          .onFailure { CompressionLogger.w(TAG, "Cleanup of failed MediaStore entry failed", it) }
      }
    }

    throw IOException(
      "MediaStore failed to save output video '$desiredName': ${lastError?.localizedMessage ?: "insert returned null"}",
      lastError,
    )
  }

  private fun writeToAppExternalMoviesLegacy(tempFile: File, desiredName: String): SavedOutputResult {
    val baseDir =
      context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        ?: File(context.filesDir, "CompressedVideos")
    val targetDir = File(baseDir, DEFAULT_SUBFOLDER).apply { mkdirs() }
    val targetFile = resolveUniqueLocalFile(targetDir, desiredName)
    FileInputStream(tempFile).use { input ->
      targetFile.outputStream().use { out ->
        input.copyTo(out, STREAM_BUFFER_SIZE)
        out.flush()
      }
    }
    runCatching {
      MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf("video/mp4"), null)
    }.onFailure { CompressionLogger.w(TAG, "MediaScanner notification failed", it) }

    val contentUri =
      FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
    CompressionLogger.i(TAG, "Saved compressed MP4 legacy path: ${targetFile.absolutePath}")
    return SavedOutputResult(
      outputUri = contentUri,
      outputFileName = targetFile.name,
      outputSizeBytes = targetFile.length(),
    )
  }

  internal fun buildCandidateFileNames(desiredName: String): List<String> {
    val base = desiredName.substringBeforeLast('.', desiredName)
    val ext = desiredName.substringAfterLast('.', "mp4")
    val list = ArrayList<String>(11)
    list += "$base.$ext"
    for (index in 1..8) {
      list += "${base}_($index).$ext"
    }
    list += "${base}_${System.currentTimeMillis()}.$ext"
    return list
  }

  private fun resolveUniqueDocumentName(treeDoc: DocumentFile, desiredName: String): String {
    val base = desiredName.substringBeforeLast('.', desiredName)
    val ext = desiredName.substringAfterLast('.', "mp4")
    var candidate = "$base.$ext"
    var index = 1
    while (runCatching { treeDoc.findFile(candidate) }.getOrNull() != null && index <= 200) {
      candidate = "${base}_($index).$ext"
      index++
    }
    return candidate
  }

  private fun resolveUniqueLocalFile(dir: File, desiredName: String): File {
    val base = desiredName.substringBeforeLast('.', desiredName)
    val ext = desiredName.substringAfterLast('.', "mp4")
    var candidate = File(dir, "$base.$ext")
    var index = 1
    while (candidate.exists() && index <= 200) {
      candidate = File(dir, "${base}_($index).$ext")
      index++
    }
    return candidate
  }
}
