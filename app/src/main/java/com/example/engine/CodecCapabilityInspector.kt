package com.example.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import com.example.model.CodecCapabilities
import com.example.model.DeviceCodecInfo

object CodecCapabilityInspector {

  private const val MIME_AVC = "video/avc"
  private const val MIME_HEVC = "video/hevc"
  private const val MIME_AV1 = "video/av01"

  fun inspect(): CodecCapabilities {
    val codecInfos =
      runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }
        .getOrDefault(emptyList())

    val detectedEncoders = mutableListOf<DeviceCodecInfo>()

    for (info in codecInfos) {
      if (!info.isEncoder) continue
      val supportedTypes = info.supportedTypes
      for (mime in supportedTypes) {
        if (mime.equals(MIME_AVC, ignoreCase = true) ||
          mime.equals(MIME_HEVC, ignoreCase = true) ||
          mime.equals(MIME_AV1, ignoreCase = true)
        ) {
          val isSoftware = isSoftwareCodec(info)
          val isHardware = isHardwareCodec(info, isSoftware)
          detectedEncoders +=
            DeviceCodecInfo(
              codecName = info.name,
              mimeType = mime.lowercase(),
              isHardwareAccelerated = isHardware,
              isSoftwareFallback = isSoftware,
            )
        }
      }
    }

    val avcEncoders = detectedEncoders.filter { it.mimeType == MIME_AVC }
    val hevcEncoders = detectedEncoders.filter { it.mimeType == MIME_HEVC }
    val av1Encoders = detectedEncoders.filter { it.mimeType == MIME_AV1 }

    val hwAvc = avcEncoders.firstOrNull { it.isHardwareAccelerated }
    val swAvc = avcEncoders.firstOrNull { it.isSoftwareFallback }
    val hwHevc = hevcEncoders.firstOrNull { it.isHardwareAccelerated }
    val swHevc = hevcEncoders.firstOrNull { it.isSoftwareFallback }

    return CodecCapabilities(
      h264HardwareSupported = hwAvc != null,
      h264SoftwareSupported = swAvc != null,
      h265HardwareSupported = hwHevc != null,
      h265SoftwareSupported = swHevc != null,
      av1Supported = av1Encoders.isNotEmpty(),
      preferredH264Encoder = hwAvc?.codecName ?: swAvc?.codecName,
      preferredH265Encoder = hwHevc?.codecName ?: swHevc?.codecName,
      encoders = detectedEncoders,
    )
  }

  fun isSoftwareCodec(info: MediaCodecInfo): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      return info.isSoftwareOnly
    }
    val name = info.name.lowercase()
    return name.startsWith("omx.google.") ||
      name.startsWith("c2.android.") ||
      name.contains("sw")
  }

  private fun isHardwareCodec(info: MediaCodecInfo, isSoftware: Boolean): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      return info.isHardwareAccelerated
    }
    return !isSoftware
  }
}
