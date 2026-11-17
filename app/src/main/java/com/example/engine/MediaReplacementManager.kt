package com.example.engine

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.example.data.VideoJobEntity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

sealed class ResolvedReplaceTarget {
  data class MediaStoreTarget(val mediaUri: Uri, val originalUri: Uri) : ResolvedReplaceTarget()

  data class SafDocumentTarget(val documentUri: Uri) : ResolvedReplaceTarget()

  data class UnsupportedTarget(val reason: String) : ResolvedReplaceTarget()
}

data class BatchApprovalPreparation(
  val mediaStoreIntentSender: IntentSender?,
  val mediaStoreJobIds: Set<String>,
  val directSafJobs: List<VideoJobEntity>,
  val unsupportedJobs: List<Pair<VideoJobEntity, String>>,
)

sealed class ReplacementOutcome {
  data class Replaced(
    val outputUri: Uri,
    val outputFileName: String,
    val outputSizeBytes: Long,
  ) : ReplacementOutcome()

  data class NeedsSystemIntentSender(
    val intentSender: IntentSender,
    val targetMediaUri: Uri,
  ) : ReplacementOutcome()

  data class Failed(val errorMessage: String) : ReplacementOutcome()
}

class MediaReplacementManager(
  private val context: Context,
  private val outputStorageWriter: OutputStorageWriter,
) {

  companion object {
    private const val TAG = "CompressXReplace"
    private const val STREAM_BUFFER_SIZE = 64 * 1024
  }

  fun resolveTargetUri(sourceUri: Uri): ResolvedReplaceTarget {
    val authority = sourceUri.authority.orEmpty()
    val segments = sourceUri.pathSegments

    // 1. Canonical MediaStore video URI (content://media/external.../video/media/<id>)
    if (authority == MediaStore.AUTHORITY && !segments.contains("picker") && !segments.contains("picker_get_content")) {
      return ResolvedReplaceTarget.MediaStoreTarget(mediaUri = sourceUri, originalUri = sourceUri)
    }

    // 2. Android Photo Picker URI -> try mapping local photopicker MediaStore ID
    if (authority == MediaStore.AUTHORITY && (segments.contains("picker") || segments.contains("picker_get_content"))) {
      val mediaId = sourceUri.lastPathSegment?.toLongOrNull()
      val isLocalMediaProvider = segments.any { it.contains("photopicker") || it == "media" }
      if (mediaId != null && isLocalMediaProvider) {
        val candidate = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaId)
        CompressionLogger.i(TAG, "Mapped Photo Picker URI $sourceUri -> MediaStore URI $candidate")
        return ResolvedReplaceTarget.MediaStoreTarget(mediaUri = candidate, originalUri = sourceUri)
      }
      return ResolvedReplaceTarget.UnsupportedTarget(
        "Photo Picker returned a cloud/virtual URI that cannot replace the original file in-place. Use 'Browse Device Folders (SAF)' to replace originals, or tap 'Save Copy'.",
      )
    }

    // 3. SAF / DocumentsProvider URI: on API 29+, check MediaStore.getMediaUri first for MediaStore-backed providers
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val resolvedMedia = runCatching { MediaStore.getMediaUri(context, sourceUri) }.getOrNull()
      if (resolvedMedia != null && resolvedMedia.authority == MediaStore.AUTHORITY) {
        return ResolvedReplaceTarget.MediaStoreTarget(mediaUri = resolvedMedia, originalUri = sourceUri)
      }
    }

    // 4. Direct SAF Document URI with write support (e.g., externalstorage.documents / SD card / OTG)
    val doc = runCatching { DocumentFile.fromSingleUri(context, sourceUri) }.getOrNull()
    if (doc != null && doc.canWrite()) {
      return ResolvedReplaceTarget.SafDocumentTarget(sourceUri)
    }

    return ResolvedReplaceTarget.UnsupportedTarget(
      "Source provider ($authority) is read-only and does not permit replacing the original video.",
    )
  }

  fun prepareBatchApproval(jobs: List<VideoJobEntity>): BatchApprovalPreparation {
    val mediaStoreCandidates = mutableListOf<Pair<VideoJobEntity, Uri>>()
    val directSafJobs = mutableListOf<VideoJobEntity>()
    val unsupported = mutableListOf<Pair<VideoJobEntity, String>>()

    for (job in jobs) {
      val stagedPath = job.stagedTempPath
      val stagedFile = stagedPath?.let(::File)
      if (stagedFile == null || !stagedFile.exists() || stagedFile.length() < 1024L) {
        unsupported += job to "Staged compressed video file is missing. Please re-compress this video."
        continue
      }

      when (val resolved = resolveTargetUri(Uri.parse(job.sourceUri))) {
        is ResolvedReplaceTarget.MediaStoreTarget -> mediaStoreCandidates += job to resolved.mediaUri
        is ResolvedReplaceTarget.SafDocumentTarget -> directSafJobs += job
        is ResolvedReplaceTarget.UnsupportedTarget -> unsupported += job to resolved.reason
      }
    }

    if (mediaStoreCandidates.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      // On API 26-29, MediaStore items either trigger RecoverableSecurityException per item or run via SAF/legacy
      return BatchApprovalPreparation(
        mediaStoreIntentSender = null,
        mediaStoreJobIds = mediaStoreCandidates.map { it.first.id }.toSet(),
        directSafJobs = directSafJobs,
        unsupportedJobs = unsupported,
      )
    }

    // API 30+ (Android 11-15): Build official MediaStore batch write-request confirmation dialog
    val resolver = context.contentResolver
    val validPairs = mutableListOf<Pair<VideoJobEntity, Uri>>()
    val uris = mediaStoreCandidates.map { it.second }
    val batchPending =
      try {
        validPairs.addAll(mediaStoreCandidates)
        MediaStore.createWriteRequest(resolver, uris)
      } catch (batchErr: Throwable) {
        CompressionLogger.w(
          TAG,
          "Batch createWriteRequest failed (${batchErr.message}); probing URIs individually",
          batchErr,
        )
        validPairs.clear()
        for ((job, candidateUri) in mediaStoreCandidates) {
          val ok = runCatching { MediaStore.createWriteRequest(resolver, listOf(candidateUri)) }.isSuccess
          if (ok) {
            validPairs += job to candidateUri
          } else {
            unsupported +=
              job to
                "MediaStore cannot modify this item directly (possibly cloud-only Photo Picker media). Tap 'Save Copy' or pick via Device Folders (SAF)."
          }
        }
        if (validPairs.isNotEmpty()) {
          runCatching {
            MediaStore.createWriteRequest(resolver, validPairs.map { it.second })
          }.getOrNull()
        } else {
          null
        }
      }

    return BatchApprovalPreparation(
      mediaStoreIntentSender = batchPending?.intentSender,
      mediaStoreJobIds = validPairs.map { it.first.id }.toSet(),
      directSafJobs = directSafJobs,
      unsupportedJobs = unsupported,
    )
  }

  fun executeSingleReplacement(job: VideoJobEntity): ReplacementOutcome {
    val stagedPath = job.stagedTempPath
      ?: return ReplacementOutcome.Failed("Missing staged compressed file path; original preserved.")
    val stagedFile = File(stagedPath)
    if (!verifyStagedPlayable(stagedFile)) {
      return ReplacementOutcome.Failed(
        "Staged compressed video failed playability verification; original preserved.",
      )
    }

    val sourceUri = Uri.parse(job.sourceUri)
    return when (val target = resolveTargetUri(sourceUri)) {
      is ResolvedReplaceTarget.UnsupportedTarget -> ReplacementOutcome.Failed(target.reason)
      is ResolvedReplaceTarget.MediaStoreTarget ->
        replaceMediaStoreItem(job, stagedFile, target.mediaUri)
      is ResolvedReplaceTarget.SafDocumentTarget ->
        replaceSafDocumentItem(job, stagedFile, target.documentUri)
    }
  }

  fun saveStagedAsCopy(
    job: VideoJobEntity,
    customOutputTreeUri: String?,
  ): SavedOutputResult {
    val stagedPath = job.stagedTempPath
      ?: throw IOException("No staged compressed file available for ${job.fileName}")
    val stagedFile = File(stagedPath)
    if (!verifyStagedPlayable(stagedFile)) {
      throw IOException("Staged compressed video is corrupt or missing; original preserved.")
    }
    val saved =
      outputStorageWriter.writeTempFileToDestination(
        tempFile = stagedFile,
        originalFileName = job.fileName,
        customTreeUriString = customOutputTreeUri,
      )
    runCatching { stagedFile.delete() }
    return saved
  }

  private fun replaceMediaStoreItem(
    job: VideoJobEntity,
    stagedFile: File,
    mediaUri: Uri,
  ): ReplacementOutcome {
    val resolver = context.contentResolver

    // Snapshot original metadata before replacing bytes
    val originalMeta = queryMediaStoreMetadata(mediaUri)
    val preservedName = originalMeta.displayName ?: job.fileName
    val stagedLength = stagedFile.length()

    // 1. Primary path: In-place truncate & stream overwrite -> preserves same file, folder, ID, and metadata
    try {
      overwriteUriBytes(mediaUri, stagedFile)
      updateMediaStoreMetadataAfterOverwrite(mediaUri, preservedName, originalMeta, stagedLength)
      verifyReplacedUriPlayable(mediaUri)
      runCatching { stagedFile.delete() }
      CompressionLogger.i(TAG, "Successfully replaced MediaStore original in-place: $preservedName ($mediaUri)")
      return ReplacementOutcome.Replaced(
        outputUri = mediaUri,
        outputFileName = preservedName,
        outputSizeBytes = stagedLength,
      )
    } catch (sec: SecurityException) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sec is RecoverableSecurityException) {
        CompressionLogger.i(TAG, "RecoverableSecurityException for $mediaUri; requesting user confirmation")
        return ReplacementOutcome.NeedsSystemIntentSender(
          intentSender = sec.userAction.actionIntent.intentSender,
          targetMediaUri = mediaUri,
        )
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val pending = runCatching { MediaStore.createWriteRequest(resolver, listOf(mediaUri)) }.getOrNull()
        if (pending != null) {
          return ReplacementOutcome.NeedsSystemIntentSender(
            intentSender = pending.intentSender,
            targetMediaUri = mediaUri,
          )
        }
      }
      CompressionLogger.e(TAG, "SecurityException replacing $mediaUri; original preserved", sec)
      return ReplacementOutcome.Failed(
        "Permission denied while replacing original: ${sec.localizedMessage ?: "user consent required"}",
      )
    } catch (inPlaceErr: Throwable) {
      CompressionLogger.w(
        TAG,
        "Direct in-place overwrite failed for $mediaUri (${inPlaceErr.message}); checking pending-swap fallback",
        inPlaceErr,
      )
      // 2. Fallback ONLY on API 29+ if in-place stream unsupported on OEM, using IS_PENDING so 0 duplicates ever appear
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        return swapViaPendingMediaStoreEntry(
          mediaUri = mediaUri,
          stagedFile = stagedFile,
          originalName = preservedName,
          originalMeta = originalMeta,
          stagedLength = stagedLength,
          initialError = inPlaceErr,
        )
      }
      return ReplacementOutcome.Failed(
        "Replacement failed (original preserved): ${inPlaceErr.localizedMessage ?: inPlaceErr.javaClass.simpleName}",
      )
    }
  }

  private fun swapViaPendingMediaStoreEntry(
    mediaUri: Uri,
    stagedFile: File,
    originalName: String,
    originalMeta: MediaStoreMeta,
    stagedLength: Long,
    initialError: Throwable,
  ): ReplacementOutcome {
    val resolver = context.contentResolver
    val targetRelativePath =
      originalMeta.relativePath ?: "${Environment.DIRECTORY_MOVIES}/CompressX Studio"
    val finalDisplayName = ensureMp4ExtensionKeepingBase(originalName)
    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    val insertValues =
      ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, finalDisplayName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, targetRelativePath)
        put(MediaStore.Video.Media.IS_PENDING, 1)
        if (originalMeta.dateTakenMs != null && originalMeta.dateTakenMs > 0L) {
          put(MediaStore.Video.Media.DATE_TAKEN, originalMeta.dateTakenMs)
        }
      }

    val pendingUri =
      try {
        resolver.insert(collection, insertValues)
      } catch (t: Throwable) {
        CompressionLogger.e(TAG, "Pending swap insert failed", t)
        null
      }
        ?: return ReplacementOutcome.Failed(
          "Could not replace original in-place (${initialError.localizedMessage}); original preserved.",
        )

    try {
      resolver.openOutputStream(pendingUri)?.use { out ->
        FileInputStream(stagedFile).use { input -> input.copyTo(out, STREAM_BUFFER_SIZE) }
        out.flush()
      } ?: throw IOException("Failed opening stream for replacement MediaStore entry")

      // Delete original BEFORE clearing IS_PENDING so Gallery never shows duplicate files
      val deleted =
        try {
          resolver.delete(mediaUri, null, null) > 0
        } catch (sec: SecurityException) {
          runCatching { resolver.delete(pendingUri, null, null) }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val delPending =
              runCatching { MediaStore.createDeleteRequest(resolver, listOf(mediaUri)) }.getOrNull()
            if (delPending != null) {
              return ReplacementOutcome.NeedsSystemIntentSender(delPending.intentSender, mediaUri)
            }
          }
          throw sec
        }

      if (!deleted) {
        throw IOException("Original MediaStore item could not be removed; aborting swap to prevent duplicates")
      }

      val publish = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
      resolver.update(pendingUri, publish, null, null)
      verifyReplacedUriPlayable(pendingUri)
      runCatching { stagedFile.delete() }
      CompressionLogger.i(TAG, "Replaced MediaStore original via IS_PENDING swap: $finalDisplayName")
      return ReplacementOutcome.Replaced(
        outputUri = pendingUri,
        outputFileName = finalDisplayName,
        outputSizeBytes = stagedLength,
      )
    } catch (t: Throwable) {
      CompressionLogger.e(TAG, "Pending swap replacement failed; cleaning up pending entry, original preserved", t)
      runCatching { resolver.delete(pendingUri, null, null) }
      return ReplacementOutcome.Failed(
        "Replacement failed (original preserved): ${CompressionLogger.formatUserErrorMessage(t)}",
      )
    }
  }

  private fun replaceSafDocumentItem(
    job: VideoJobEntity,
    stagedFile: File,
    documentUri: Uri,
  ): ReplacementOutcome {
    val stagedLength = stagedFile.length()
    return try {
      overwriteUriBytes(documentUri, stagedFile)

      // Rename non-mp4 SAF document to .mp4 only if provider supports rename and name didn't end with .mp4
      var finalUri = documentUri
      var finalName = job.fileName
      if (!finalName.lowercase().endsWith(".mp4")) {
        val renamedName = "${finalName.substringBeforeLast('.', finalName)}.mp4"
        val doc = DocumentFile.fromSingleUri(context, documentUri)
        val canRename =
          doc != null &&
            runCatching {
              DocumentsContract.renameDocument(context.contentResolver, documentUri, renamedName)
            }.onSuccess { newUri ->
              if (newUri != null) {
                finalUri = newUri
                finalName = renamedName
              }
            }.isSuccess
        if (!canRename) {
          CompressionLogger.i(TAG, "SAF provider kept original filename $finalName after MP4 overwrite")
        }
      }

      verifyReplacedUriPlayable(finalUri)
      runCatching { stagedFile.delete() }
      CompressionLogger.i(TAG, "Successfully replaced SAF original: $finalName ($finalUri)")
      ReplacementOutcome.Replaced(
        outputUri = finalUri,
        outputFileName = finalName,
        outputSizeBytes = stagedLength,
      )
    } catch (t: Throwable) {
      CompressionLogger.e(TAG, "SAF original replacement failed for ${job.fileName}; original preserved", t)
      ReplacementOutcome.Failed(
        "SAF replacement failed (original preserved): ${CompressionLogger.formatUserErrorMessage(t)}",
      )
    }
  }

  private fun overwriteUriBytes(targetUri: Uri, stagedFile: File) {
    val resolver = context.contentResolver
    val streamOpened =
      try {
        resolver.openOutputStream(targetUri, "wt")?.use { out ->
          FileInputStream(stagedFile).use { input -> input.copyTo(out, STREAM_BUFFER_SIZE) }
          out.flush()
          true
        } ?: false
      } catch (wtUnsupported: IllegalArgumentException) {
        CompressionLogger.w(TAG, "Mode 'wt' rejected by provider; trying truncated rw descriptor", wtUnsupported)
        false
      }

    if (streamOpened) return

    val pfd =
      resolver.openFileDescriptor(targetUri, "rw")
        ?: throw IOException("Unable to open target video for replacement write: $targetUri")
    pfd.use { descriptor ->
      FileOutputStream(descriptor.fileDescriptor).use { out ->
        out.channel.truncate(0L)
        out.channel.position(0L)
        FileInputStream(stagedFile).use { input -> input.copyTo(out, STREAM_BUFFER_SIZE) }
        out.flush()
        runCatching { descriptor.fileDescriptor.sync() }
      }
    }
  }

  private fun updateMediaStoreMetadataAfterOverwrite(
    mediaUri: Uri,
    displayName: String,
    originalMeta: MediaStoreMeta,
    newSizeBytes: Long,
  ) {
    val values =
      ContentValues().apply {
        put(MediaStore.Video.Media.SIZE, newSizeBytes)
        put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
        if (originalMeta.dateTakenMs != null && originalMeta.dateTakenMs > 0L) {
          put(MediaStore.Video.Media.DATE_TAKEN, originalMeta.dateTakenMs)
        }
        if (displayName.lowercase().endsWith(".mp4")) {
          put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
      }
    runCatching { context.contentResolver.update(mediaUri, values, null, null) }
      .onFailure { CompressionLogger.w(TAG, "Non-fatal MediaStore metadata update warning", it) }

    originalMeta.dataPath?.let { path ->
      runCatching {
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("video/mp4"), null)
      }
    }
  }

  private fun verifyStagedPlayable(file: File): Boolean {
    if (!file.exists() || file.length() < 1024L) return false
    val retriever = MediaMetadataRetriever()
    return try {
      retriever.setDataSource(file.absolutePath)
      val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
      val duration =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      val width =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
      val height =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
      hasVideo == "yes" && duration > 0L && width > 0 && height > 0
    } catch (t: Throwable) {
      CompressionLogger.e(TAG, "Staged file playability verification failed: ${file.name}", t)
      false
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun verifyReplacedUriPlayable(uri: Uri) {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(context, uri)
      val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
      val duration =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      if (hasVideo != "yes" || duration <= 0L) {
        throw IOException("Replaced output MP4 verification failed (hasVideo=$hasVideo, duration=$duration)")
      }
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun ensureMp4ExtensionKeepingBase(originalName: String): String {
    val clean = originalName.trim().ifEmpty { "video_${System.currentTimeMillis()}.mp4" }
    val base = clean.substringBeforeLast('.', clean)
    return "$base.mp4"
  }

  private data class MediaStoreMeta(
    val displayName: String? = null,
    val relativePath: String? = null,
    val dateTakenMs: Long? = null,
    val dataPath: String? = null,
  )

  private fun queryMediaStoreMetadata(uri: Uri): MediaStoreMeta {
    val cols =
      buildList {
        add(MediaStore.Video.Media.DISPLAY_NAME)
        add(MediaStore.Video.Media.DATE_TAKEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          add(MediaStore.Video.Media.RELATIVE_PATH)
        }
        add(MediaStore.Video.Media.DATA)
      }.toTypedArray()

    return runCatching {
      context.contentResolver.query(uri, cols, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use MediaStoreMeta()
        val nameIdx = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
        val dateIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
        val relIdx =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
          } else -1
        val dataIdx = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
        MediaStoreMeta(
          displayName = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null,
          dateTakenMs = if (dateIdx >= 0 && !cursor.isNull(dateIdx)) cursor.getLong(dateIdx) else null,
          relativePath = if (relIdx >= 0 && !cursor.isNull(relIdx)) cursor.getString(relIdx) else null,
          dataPath = if (dataIdx >= 0 && !cursor.isNull(dataIdx)) cursor.getString(dataIdx) else null,
        )
      } ?: MediaStoreMeta()
    }.getOrDefault(MediaStoreMeta())
  }
}
