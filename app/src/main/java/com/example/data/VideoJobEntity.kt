package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.example.model.CompressionPreset
import com.example.model.JobStatus
import com.example.model.OutputMode
import com.example.model.ReplacementPermissionStatus
import com.example.model.TargetResolution
import com.example.model.VideoCodec

@Entity(tableName = "video_jobs")
data class VideoJobEntity(
  @PrimaryKey val id: String,
  val sourceUri: String,
  val fileName: String,
  val durationMs: Long,
  val originalSizeBytes: Long,
  val width: Int,
  val height: Int,
  val rotation: Int,
  val sourceBitrateBps: Int,
  val sourceFrameRate: Float = 0f,
  val sourceAudioBitrateBps: Int = 0,
  val sortOrder: Int,
  val status: JobStatus,
  val progressPercent: Int = 0,
  val preset: CompressionPreset,
  val targetResolution: TargetResolution,
  val targetCodec: VideoCodec,
  val customBitrateKbps: Int,
  val outputMode: OutputMode = OutputMode.KEEP_ORIGINAL,
  val replacementStatus: ReplacementPermissionStatus = ReplacementPermissionStatus.NOT_APPLICABLE,
  val sourceAuthority: String? = null,
  val sourceRelativePath: String? = null,
  val sourceDateTakenMs: Long? = null,
  val stagedTempPath: String? = null,
  val replacedOriginal: Boolean = false,
  val outputUri: String? = null,
  val outputFileName: String? = null,
  val outputSizeBytes: Long = 0L,
  val usedHardwareEncoder: Boolean? = null,
  val encoderName: String? = null,
  val errorMessage: String? = null,
  val contentFingerprint: String? = null,
  val needsRelink: Boolean = false,
  val hasPersistablePermission: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
  val startedAt: Long? = null,
  val completedAt: Long? = null,
)

class CompressXTypeConverters {
  @TypeConverter fun fromJobStatus(value: JobStatus): String = value.name

  @TypeConverter
  fun toJobStatus(value: String): JobStatus =
    runCatching { JobStatus.valueOf(value) }.getOrDefault(JobStatus.QUEUED)

  @TypeConverter fun fromPreset(value: CompressionPreset): String = value.name

  @TypeConverter
  fun toPreset(value: String): CompressionPreset =
    runCatching { CompressionPreset.valueOf(value) }.getOrDefault(CompressionPreset.BALANCED)

  @TypeConverter fun fromResolution(value: TargetResolution): String = value.name

  @TypeConverter
  fun toResolution(value: String): TargetResolution =
    runCatching { TargetResolution.valueOf(value) }.getOrDefault(TargetResolution.FHD_1080P)

  @TypeConverter fun fromCodec(value: VideoCodec): String = value.name

  @TypeConverter
  fun toCodec(value: String): VideoCodec =
    runCatching { VideoCodec.valueOf(value) }.getOrDefault(VideoCodec.H264)

  @TypeConverter fun fromOutputMode(value: OutputMode): String = value.name

  @TypeConverter
  fun toOutputMode(value: String): OutputMode =
    runCatching { OutputMode.valueOf(value) }.getOrDefault(OutputMode.KEEP_ORIGINAL)

  @TypeConverter
  fun fromReplacementStatus(value: ReplacementPermissionStatus): String = value.name

  @TypeConverter
  fun toReplacementStatus(value: String): ReplacementPermissionStatus =
    runCatching { ReplacementPermissionStatus.valueOf(value) }
      .getOrDefault(ReplacementPermissionStatus.NOT_APPLICABLE)
}
