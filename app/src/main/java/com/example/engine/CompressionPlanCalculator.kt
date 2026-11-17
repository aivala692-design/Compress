package com.example.engine

import com.example.model.CompressionPlan
import com.example.model.CompressionPreset
import com.example.model.TargetResolution
import com.example.model.VideoCodec
import kotlin.math.min
import kotlin.math.roundToInt

object CompressionPlanCalculator {

  private const val MIN_VIDEO_BITRATE_BPS = 250_000
  private const val MAX_VIDEO_BITRATE_BPS = 50_000_000
  private const val DEFAULT_AUDIO_BITRATE_BPS = 128_000
  private const val LOW_AUDIO_BITRATE_BPS = 96_000

  fun buildPlan(
    sourceWidth: Int,
    sourceHeight: Int,
    sourceRotation: Int,
    sourceBitrateBps: Int,
    preset: CompressionPreset,
    targetResolution: TargetResolution,
    targetCodec: VideoCodec,
    customBitrateKbps: Int,
  ): CompressionPlan {
    val isRotated = sourceRotation == 90 || sourceRotation == 270
    val displayW = if (isRotated) sourceHeight else sourceWidth
    val displayH = if (isRotated) sourceWidth else sourceHeight

    val safeW = displayW.coerceAtLeast(2)
    val safeH = displayH.coerceAtLeast(2)
    val sourceShortSide = min(safeW, safeH)

    val desiredShortSide = targetResolution.shortSidePx
    val shouldScale = desiredShortSide != null && sourceShortSide > desiredShortSide

    val (outW, outH) =
      if (shouldScale && desiredShortSide != null) {
        val scale = desiredShortSide.toFloat() / sourceShortSide.toFloat()
        val w = ensureEven((safeW * scale).roundToInt())
        val h = ensureEven((safeH * scale).roundToInt())
        w to h
      } else {
        ensureEven(safeW) to ensureEven(safeH)
      }

    val effectiveShortSide = min(outW, outH)
    val resolutionCapBps =
      when {
        effectiveShortSide >= 2160 -> 14_000_000
        effectiveShortSide >= 1080 -> 4_500_000
        effectiveShortSide >= 720 -> 2_400_000
        else -> 1_200_000
      }

    val safeSourceBitrate =
      if (sourceBitrateBps > 0) sourceBitrateBps
      else {
        // Fallback estimate if container lacked bitrate metadata
        when {
          sourceShortSide >= 2160 -> 28_000_000
          sourceShortSide >= 1080 -> 10_000_000
          sourceShortSide >= 720 -> 5_000_000
          else -> 2_500_000
        }
      }

    val codecEfficiencyFactor = if (targetCodec == VideoCodec.H265) 0.78f else 1.0f

    val rawTargetBps =
      when (preset) {
        CompressionPreset.CUSTOM -> (customBitrateKbps.coerceIn(250, 50_000) * 1000)
        CompressionPreset.HIGH_QUALITY -> {
          val cap = (resolutionCapBps * 1.6f).toInt()
          val scaledSource = (safeSourceBitrate * preset.bitrateFactor * codecEfficiencyFactor).toInt()
          min(cap, scaledSource)
        }
        CompressionPreset.BALANCED -> {
          val cap = resolutionCapBps
          val scaledSource = (safeSourceBitrate * preset.bitrateFactor * codecEfficiencyFactor).toInt()
          min(cap, scaledSource)
        }
        CompressionPreset.SMALL_SIZE -> {
          val cap = (resolutionCapBps * 0.55f).toInt()
          val scaledSource = (safeSourceBitrate * preset.bitrateFactor * codecEfficiencyFactor).toInt()
          min(cap, scaledSource)
        }
      }

    // Ensure non-custom presets stay genuinely below source bitrate when source bitrate is known
    val finalVideoBitrateBps =
      if (preset != CompressionPreset.CUSTOM && sourceBitrateBps > MIN_VIDEO_BITRATE_BPS * 2) {
        val maxToGuaranteeSavings = (sourceBitrateBps * 0.85f).toInt()
        rawTargetBps.coerceAtMost(maxToGuaranteeSavings).coerceIn(MIN_VIDEO_BITRATE_BPS, MAX_VIDEO_BITRATE_BPS)
      } else {
        rawTargetBps.coerceIn(MIN_VIDEO_BITRATE_BPS, MAX_VIDEO_BITRATE_BPS)
      }

    val audioBitrate =
      if (preset == CompressionPreset.SMALL_SIZE || finalVideoBitrateBps < 900_000) {
        LOW_AUDIO_BITRATE_BPS
      } else {
        DEFAULT_AUDIO_BITRATE_BPS
      }

    return CompressionPlan(
      targetVideoBitrateBps = finalVideoBitrateBps,
      targetAudioBitrateBps = audioBitrate,
      targetDisplayWidth = outW,
      targetDisplayHeight = outH,
      shouldScale = shouldScale,
      mimeType = targetCodec.mimeType,
    )
  }

  fun buildCompressedFileName(originalName: String): String {
    val clean = originalName.trim().ifEmpty { "video_${System.currentTimeMillis()}.mp4" }
    val base = clean.substringBeforeLast('.', clean)
    val sanitizedBase =
      if (base.endsWith("_compressed")) base else "${base}_compressed"
    return "$sanitizedBase.mp4"
  }

  fun estimateOutputSizeBytes(durationMs: Long, videoBitrateBps: Int, audioBitrateBps: Int): Long {
    if (durationMs <= 0L) return 0L
    val seconds = durationMs / 1000.0
    val totalBitsPerSecond = (videoBitrateBps + audioBitrateBps).toDouble()
    return ((totalBitsPerSecond * seconds) / 8.0 * 1.02).toLong().coerceAtLeast(1024L)
  }

  private fun ensureEven(value: Int): Int {
    val clamped = value.coerceAtLeast(2)
    return if (clamped % 2 == 0) clamped else clamped - 1
  }
}
