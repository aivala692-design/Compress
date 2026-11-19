package com.example.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Generates a reliable, fast content-based identity fingerprint for a video URI so duplicate
 * videos are detected even when imported through different pickers (Photo Picker, SAF, Share
 * Sheet, MediaStore) that assign different content:// URI strings to the exact same file.
 *
 * Never relies on filename alone: two different videos with identical display names produce
 * different fingerprints, while the same file under different URIs produces the same fingerprint.
 */
object FileFingerprintUtil {
  private const val SAMPLE_CHUNK_BYTES = 64 * 1024

  fun computeContentFingerprint(
    context: Context,
    uri: Uri,
    sizeBytes: Long,
    durationMs: Long,
    width: Int,
    height: Int,
  ): String? {
    val contentHash = computeThreePointSampleHash(context, uri, sizeBytes)
    if (contentHash != null) {
      return "v1:$sizeBytes:$durationMs:${width}x${height}:$contentHash"
    }

    // Fallback when random-access/stream reading is temporarily unavailable but metadata exists
    if (sizeBytes > 0L && durationMs > 0L && width > 0 && height > 0) {
      return "meta:$sizeBytes:$durationMs:${width}x${height}"
    }
    return null
  }

  private fun computeThreePointSampleHash(
    context: Context,
    uri: Uri,
    knownSizeBytes: Long,
  ): String? {
    // 1. Prefer seekable ParcelFileDescriptor so large 2GB+ videos hash in <5ms
    val pfdHash =
      runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
          hashParcelFileDescriptor(pfd, knownSizeBytes)
        }
      }.getOrNull()
    if (pfdHash != null) return pfdHash

    // 2. Fallback to streaming InputStream (first 64KB + total byte count) for pipe-backed URIs
    return runCatching {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(SAMPLE_CHUNK_BYTES)
        var totalRead = 0
        while (totalRead < SAMPLE_CHUNK_BYTES) {
          val n = stream.read(buffer, totalRead, SAMPLE_CHUNK_BYTES - totalRead)
          if (n <= 0) break
          totalRead += n
        }
        if (totalRead <= 0) return@use null
        digest.update(buffer, 0, totalRead)
        digest.update("size:$knownSizeBytes".toByteArray())
        digest.digest().toHexString()
      }
    }.getOrNull()
  }

  private fun hashParcelFileDescriptor(
    pfd: ParcelFileDescriptor,
    knownSizeBytes: Long,
  ): String? {
    FileInputStream(pfd.fileDescriptor).use { fis ->
      val channel = fis.channel
      val fileSize =
        runCatching { channel.size() }.getOrDefault(-1L).let { sz ->
          if (sz > 0L) sz else knownSizeBytes
        }
      val digest = MessageDigest.getInstance("SHA-256")
      val buf = java.nio.ByteBuffer.allocate(SAMPLE_CHUNK_BYTES)

      var anyRead = false
      val offsets: List<Long> =
        if (fileSize <= SAMPLE_CHUNK_BYTES * 3L || fileSize <= 0L) {
          listOf(0L)
        } else {
          val middle = ((fileSize / 2L) - (SAMPLE_CHUNK_BYTES / 2L)).coerceAtLeast(0L)
          val tail = (fileSize - SAMPLE_CHUNK_BYTES).coerceAtLeast(0L)
          listOf(0L, middle, tail)
        }

      if (offsets.size == 1 && fileSize > 0L && fileSize <= SAMPLE_CHUNK_BYTES * 3L) {
        // Small file: hash the entire content
        channel.position(0L)
        while (true) {
          buf.clear()
          val read = channel.read(buf)
          if (read <= 0) break
          anyRead = true
          buf.flip()
          digest.update(buf)
        }
      } else {
        for (offset in offsets) {
          runCatching { channel.position(offset) }
          buf.clear()
          val read = channel.read(buf)
          if (read > 0) {
            anyRead = true
            buf.flip()
            digest.update(buf)
          }
        }
      }

      if (!anyRead) return null
      digest.update("sz:$fileSize".toByteArray())
      return digest.digest().toHexString()
    }
  }

  private fun ByteArray.toHexString(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
      sb.append(String.format("%02x", b))
    }
    return sb.toString()
  }
}
