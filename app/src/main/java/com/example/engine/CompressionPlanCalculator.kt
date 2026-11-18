package com.example.engine

import com.example.model.CompressionEstimate
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
    originalSizeBytes: Long = 0L,
    durationMs: Long = 0L,
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

    val derivedFileBitrateBps =
      if (sourceBitrateBps > 0) {
        sourceBitrateBps
      } else if (originalSizeBytes > 0L && durationMs > 0L) {
        ((originalSizeBytes * 8.0) / (durationMs / 1000.0)).toInt().coerceAtLeast(MIN_VIDEO_BITRATE_BPS)
      } else {
        0
      }

    val safeSourceBitrate =
      if (derivedFileBitrateBps > 0) derivedFileBitrateBps
      else {
        // Fallback estimate if neither container bitrate nor file size+duration are known
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
      if (preset != CompressionPreset.CUSTOM && derivedFileBitrateBps > MIN_VIDEO_BITRATE_BPS * 2) {
        val maxToGuaranteeSavings = (derivedFileBitrateBps * 0.85f).toInt()
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

  fun estimateJobCompression(
    durationMs: Long,
    originalSizeBytes: Long,
    sourceWidth: Int,
    sourceHeight: Int,
    sourceRotation: Int,
    sourceBitrateBps: Int,
    sourceFrameRate: Float,
    sourceAudioBitrateBps: Int,
    preset: CompressionPreset,
    targetResolution: TargetResolution,
    targetCodec: VideoCodec,
    customBitrateKbps: Int,
    hasHardwareEncoder: Boolean,
    preferHardware: Boolean,
  ): CompressionEstimate {
    val plan =
      buildPlan(
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        sourceRotation = sourceRotation,
        sourceBitrateBps = sourceBitrateBps,
        preset = preset,
        targetResolution = targetResolution,
        targetCodec = targetCodec,
        customBitrateKbps = customBitrateKbps,
        originalSizeBytes = originalSizeBytes,
        durationMs = durationMs,
      )

    if (durationMs <= 0L) {
      return CompressionEstimate(
        originalBytes = originalSizeBytes.coerceAtLeast(0L),
        minOutputBytes = 0L,
        maxOutputBytes = 0L,
        minSavedBytes = 0L,
        maxSavedBytes = 0L,
        minReductionPercent = 0,
        maxReductionPercent = 0,
        estimatedProcessingSeconds = 0,
        targetVideoBitrateBps = plan.targetVideoBitrateBps,
        targetAudioBitrateBps = plan.targetAudioBitrateBps,
      )
    }

    val effectiveAudioBps =
      if (sourceAudioBitrateBps in 32_000..plan.targetAudioBitrateBps) {
        sourceAudioBitrateBps
      } else {
        plan.targetAudioBitrateBps
      }

    val seconds = durationMs / 1000.0
    val totalTargetBitsPerSecond = (plan.targetVideoBitrateBps + effectiveAudioBps).toDouble()
    val containerBaseBytes = (totalTargetBitsPerSecond * seconds) / 8.0 * 1.02

    // Frame rate & VBR complexity spread: 50-60fps pushes VBR closer to target ceiling; 24fps stays compact
    val fpsHighMultiplier = if (sourceFrameRate >= 50f) 1.16 else 1.12
    val fpsLowMultiplier = if (sourceFrameRate in 1f..25f) 0.84 else 0.88

    val rawMinOut = (containerBaseBytes * fpsLowMultiplier).toLong().coerceAtLeast(1024L)
    val rawMaxOut = (containerBaseBytes * fpsHighMultiplier).toLong().coerceAtLeast(rawMinOut)

    val safeOrig = originalSizeBytes.coerceAtLeast(0L)
    val minSaved = if (safeOrig > 0L) (safeOrig - rawMaxOut).coerceAtLeast(0L) else 0L
    val maxSaved = if (safeOrig > 0L) (safeOrig - rawMinOut).coerceAtLeast(0L) else 0L

    val minPct =
      if (safeOrig > 0L) ((minSaved.toDouble() / safeOrig.toDouble()) * 100.0).toInt().coerceIn(0, 99)
      else 0
    val maxPct =
      if (safeOrig > 0L) ((maxSaved.toDouble() / safeOrig.toDouble()) * 100.0).toInt().coerceIn(minPct, 99)
      else 0

    // Estimate encoding duration based on pixels, frame rate, codec, and hardware availability
    val useHwSpeed = hasHardwareEncoder && preferHardware
    val pixels = plan.targetDisplayWidth.toLong() * plan.targetDisplayHeight.toLong()
    val baseSpeedFactor =
      when {
        pixels >= 3840L * 2160L -> if (useHwSpeed) 1.0 else 0.25
        pixels >= 1920L * 1080L -> if (useHwSpeed) 2.2 else 0.60
        pixels >= 1280L * 720L -> if (useHwSpeed) 3.2 else 1.10
        else -> if (useHwSpeed) 4.2 else 1.60
      }
    val codecSpeedPenalty = if (targetCodec == VideoCodec.H265) 1.55 else 1.0
    val fpsPenalty = if (sourceFrameRate >= 50f) 1.35 else 1.0
    val estimatedSeconds =
      (((seconds / baseSpeedFactor) * codecSpeedPenalty * fpsPenalty) + 2.0)
        .roundToInt()
        .coerceAtLeast(2)

    return CompressionEstimate(
      originalBytes = safeOrig,
      minOutputBytes = rawMinOut,
      maxOutputBytes = rawMaxOut,
      minSavedBytes = minSaved,
      maxSavedBytes = maxSaved,
      minReductionPercent = minPct,
      maxReductionPercent = maxPct,
      estimatedProcessingSeconds = estimatedSeconds,
      targetVideoBitrateBps = plan.targetVideoBitrateBps,
      targetAudioBitrateBps = effectiveAudioBps,
    )
  }

  fun buildCompressedFileName(originalName: String): String {
    val clean = originalName.trim().ifEmpty { "video_${System.currentTimeMillis()}.mp4" }
    val rawBase = clean.substringBeforeLast('.', clean)
    val safeBase =
      rawBase
        .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
        .replace(Regex("_+"), "_")
        .trim(' ', '.', '_')
        .ifEmpty { "video_${System.currentTimeMillis()}" }
    val finalBase =
      if (safeBase.endsWith("_compressed")) safeBase else "${safeBase}_compressed"
    return "$finalBase.mp4"
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
