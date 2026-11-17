package com.example.engine

import android.content.ContentValues
import android.content.Context
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
      val treeUri = Uri.parse(customTreeUriString)
      val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
      if (treeDoc != null && treeDoc.canWrite()) {
        val uniqueName = resolveUniqueDocumentName(treeDoc, desiredName)
        val targetDoc =
          treeDoc.createFile("video/mp4", uniqueName)
            ?: throw IOException("Unable to create output file in selected folder")
        val outputStream =
          context.contentResolver.openOutputStream(targetDoc.uri)
            ?: throw IOException("Unable to open output stream for selected folder")
        outputStream.use { out ->
          FileInputStream(tempFile).use { input ->
            input.copyTo(out, STREAM_BUFFER_SIZE)
          }
          out.flush()
        }
        return SavedOutputResult(
          outputUri = targetDoc.uri,
          outputFileName = targetDoc.name ?: uniqueName,
          outputSizeBytes = tempFile.length(),
        )
      }
    }

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      writeToMediaStoreQ(tempFile, desiredName)
    } else {
      writeToAppExternalMoviesLegacy(tempFile, desiredName)
    }
  }

  private fun writeToMediaStoreQ(tempFile: File, desiredName: String): SavedOutputResult {
    val resolver = context.contentResolver
    val values =
      ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, desiredName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(
          MediaStore.Video.Media.RELATIVE_PATH,
          "${Environment.DIRECTORY_MOVIES}/$DEFAULT_SUBFOLDER",
        )
        put(MediaStore.Video.Media.IS_PENDING, 1)
      }

    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val itemUri =
      resolver.insert(collection, values)
        ?: throw IOException("MediaStore failed to allocate output video entry")

    try {
      val outStream =
        resolver.openOutputStream(itemUri)
          ?: throw IOException("Failed to open MediaStore output stream")
      outStream.use { out ->
        FileInputStream(tempFile).use { input ->
          input.copyTo(out, STREAM_BUFFER_SIZE)
        }
        out.flush()
      }
      val publishValues = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
      resolver.update(itemUri, publishValues, null, null)

      val actualName =
        resolver.query(itemUri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use {
          if (it.moveToFirst()) it.getString(0) else desiredName
        } ?: desiredName

      return SavedOutputResult(
        outputUri = itemUri,
        outputFileName = actualName,
        outputSizeBytes = tempFile.length(),
      )
    } catch (t: Throwable) {
      runCatching { resolver.delete(itemUri, null, null) }
      throw t
    }
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
    val contentUri =
      FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
    return SavedOutputResult(
      outputUri = contentUri,
      outputFileName = targetFile.name,
      outputSizeBytes = targetFile.length(),
    )
  }

  private fun resolveUniqueDocumentName(treeDoc: DocumentFile, desiredName: String): String {
    val base = desiredName.substringBeforeLast('.')
    val ext = desiredName.substringAfterLast('.', "mp4")
    var candidate = desiredName
    var index = 1
    while (treeDoc.findFile(candidate) != null) {
      candidate = "${base}_($index).$ext"
      index++
    }
    return candidate
  }

  private fun resolveUniqueLocalFile(dir: File, desiredName: String): File {
    val base = desiredName.substringBeforeLast('.')
    val ext = desiredName.substringAfterLast('.', "mp4")
    var candidate = File(dir, desiredName)
    var index = 1
    while (candidate.exists()) {
      candidate = File(dir, "${base}_($index).$ext")
      index++
    }
    return candidate
  }
}
