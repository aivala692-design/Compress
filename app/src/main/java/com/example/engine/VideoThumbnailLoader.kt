package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object VideoThumbnailLoader {

  private const val MAX_CACHE_BYTES = 16 * 1024 * 1024 // 16 MB bounded memory footprint

  private val cache =
    object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
      override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

  suspend fun loadThumbnail(context: Context, uriString: String): Bitmap? {
    cache.get(uriString)?.let { return it }
    return withContext(Dispatchers.IO) {
      cache.get(uriString)?.let { return@withContext it }
      val retriever = MediaMetadataRetriever()
      try {
        retriever.setDataSource(context, Uri.parse(uriString))
        val durationMs =
          retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val frameTimeUs = if (durationMs > 2000L) 1_000_000L else (durationMs * 250L)
        val raw =
          retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.frameAtTime
        if (raw != null) {
          val scaled = downscaleBitmap(raw, maxWidth = 240)
          if (scaled !== raw) raw.recycle()
          cache.put(uriString, scaled)
          scaled
        } else {
          null
        }
      } catch (_: Throwable) {
        null
      } finally {
        runCatching { retriever.release() }
      }
    }
  }

  private fun downscaleBitmap(source: Bitmap, maxWidth: Int): Bitmap {
    if (source.width <= maxWidth) return source
    val ratio = maxWidth.toFloat() / source.width.toFloat()
    val targetHeight = (source.height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(source, maxWidth, targetHeight, true)
  }
}
