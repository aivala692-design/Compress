package com.example.model

enum class CompressionPreset(
  val label: String,
  val subtitle: String,
  val bitrateFactor: Float,
  val defaultShortSide: TargetResolution,
) {
  HIGH_QUALITY(
    label = "High Quality",
    subtitle = "Visually lossless • ~40% smaller",
    bitrateFactor = 0.65f,
    defaultShortSide = TargetResolution.ORIGINAL,
  ),
  BALANCED(
    label = "Balanced",
    subtitle = "Smart 1080p • ~60% smaller",
    bitrateFactor = 0.42f,
    defaultShortSide = TargetResolution.FHD_1080P,
  ),
  SMALL_SIZE(
    label = "Small Size",
    subtitle = "Compact 720p • ~75% smaller",
    bitrateFactor = 0.25f,
    defaultShortSide = TargetResolution.HD_720P,
  ),
  CUSTOM(
    label = "Custom",
    subtitle = "Manual bitrate, scale & codec",
    bitrateFactor = 0.50f,
    defaultShortSide = TargetResolution.FHD_1080P,
  ),
}

enum class TargetResolution(val label: String, val shortSidePx: Int?) {
  ORIGINAL("Original", null),
  UHD_4K("4K", 2160),
  FHD_1080P("1080p", 1080),
  HD_720P("720p", 720),
  SD_480P("480p", 480),
}

enum class VideoCodec(val label: String, val mimeType: String, val shortTag: String) {
  H264("H.264 (AVC)", "video/avc", "AVC"),
  H265("H.265 (HEVC)", "video/hevc", "HEVC"),
}

enum class OutputMode(val label: String, val subtitle: String) {
  REPLACE_ORIGINAL(
    label = "Replace Original (Recommended)",
    subtitle = "Compresses & verifies first, asks your approval, then replaces original so only 1 file remains",
  ),
  KEEP_ORIGINAL(
    label = "Keep Original",
    subtitle = "Preserves original video and saves a separate compressed copy in your output folder",
  ),
}

enum class JobStatus {
  QUEUED,
  PROCESSING,
  PAUSED,
  AWAITING_APPROVAL,
  COMPLETED,
  FAILED,
  CANCELLED,
}

enum class ReplacementPermissionStatus(val label: String) {
  NOT_APPLICABLE("Standard"),
  ELIGIBLE("Replace Eligible"),
  READ_ONLY("Read-Only"),
  PENDING_BATCH_APPROVAL("Needs Approval"),
  BATCH_APPROVED("Approved"),
  DENIED("Denied"),
  REPLACED("Replaced"),
  SAVED_COPY("Saved Copy"),
  FAILED("Failed"),
}

data class CompressionEstimate(
  val originalBytes: Long,
  val minOutputBytes: Long,
  val maxOutputBytes: Long,
  val minSavedBytes: Long,
  val maxSavedBytes: Long,
  val minReductionPercent: Int,
  val maxReductionPercent: Int,
  val estimatedProcessingSeconds: Int,
  val targetVideoBitrateBps: Int,
  val targetAudioBitrateBps: Int,
)

data class VideoCompressionConfig(
  val preset: CompressionPreset = CompressionPreset.BALANCED,
  val resolution: TargetResolution = TargetResolution.FHD_1080P,
  val codec: VideoCodec = VideoCodec.H264,
  val customBitrateKbps: Int = 3500,
  val outputMode: OutputMode = OutputMode.KEEP_ORIGINAL,
  val replaceOriginalConsentGranted: Boolean = false,
  val outputTreeUri: String? = null,
  val outputFolderName: String = "Movies/CompressX Studio",
  val thermalProtectionEnabled: Boolean = true,
  val pauseOnLowBattery: Boolean = true,
  val autoResumeAfterRestart: Boolean = true,
  val preferHardwareAcceleration: Boolean = true,
) {
  val isReplaceOriginalActive: Boolean
    get() = outputMode == OutputMode.REPLACE_ORIGINAL && replaceOriginalConsentGranted
}

data class DeviceCodecInfo(
  val codecName: String,
  val mimeType: String,
  val isHardwareAccelerated: Boolean,
  val isSoftwareFallback: Boolean,
)

data class CodecCapabilities(
  val h264HardwareSupported: Boolean,
  val h264SoftwareSupported: Boolean,
  val h265HardwareSupported: Boolean,
  val h265SoftwareSupported: Boolean,
  val av1Supported: Boolean,
  val preferredH264Encoder: String?,
  val preferredH265Encoder: String?,
  val encoders: List<DeviceCodecInfo>,
) {
  val hasAnyHardwareEncoder: Boolean
    get() = h264HardwareSupported || h265HardwareSupported

  fun supportsCodec(codec: VideoCodec): Boolean =
    when (codec) {
      VideoCodec.H264 -> h264HardwareSupported || h264SoftwareSupported
      VideoCodec.H265 -> h265HardwareSupported || h265SoftwareSupported
    }
}

data class BatchSummaryStats(
  val queuedCount: Int = 0,
  val processingCount: Int = 0,
  val pausedCount: Int = 0,
  val awaitingApprovalCount: Int = 0,
  val completedCount: Int = 0,
  val replacedCount: Int = 0,
  val failedCount: Int = 0,
  val replaceEligibleCount: Int = 0,
  val readOnlyCount: Int = 0,
  val totalOriginalBytesCompleted: Long = 0L,
  val totalOutputBytesCompleted: Long = 0L,
  val totalOriginalBytesPending: Long = 0L,
  val estMinOutputBytesPending: Long = 0L,
  val estMaxOutputBytesPending: Long = 0L,
  val estMinSavedBytesPending: Long = 0L,
  val estMaxSavedBytesPending: Long = 0L,
  val estMinReductionPercentPending: Int = 0,
  val estMaxReductionPercentPending: Int = 0,
  val estProcessingSecondsPending: Int = 0,
  val overallProgressPercent: Int = 0,
) {
  val totalSavedBytes: Long
    get() = (totalOriginalBytesCompleted - totalOutputBytesCompleted).coerceAtLeast(0L)

  val savingsRatioPercent: Int
    get() =
      if (totalOriginalBytesCompleted > 0L) {
        ((totalSavedBytes.toDouble() / totalOriginalBytesCompleted.toDouble()) * 100.0)
          .toInt()
          .coerceIn(0, 99)
      } else 0
}

enum class ThermalLevel {
  NORMAL,
  ELEVATED,
  SEVERE,
}

data class GuardrailState(
  val availableStorageBytes: Long = 0L,
  val isLowStorage: Boolean = false,
  val batteryPercent: Int = 100,
  val isCharging: Boolean = false,
  val isLowBattery: Boolean = false,
  val thermalLevel: ThermalLevel = ThermalLevel.NORMAL,
) {
  val isThrottled: Boolean
    get() = thermalLevel == ThermalLevel.SEVERE
}

data class CompressionPlan(
  val targetVideoBitrateBps: Int,
  val targetAudioBitrateBps: Int,
  val targetDisplayWidth: Int,
  val targetDisplayHeight: Int,
  val shouldScale: Boolean,
  val mimeType: String,
)
