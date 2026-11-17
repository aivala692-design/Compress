package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.example.model.CompressionPreset
import com.example.model.JobStatus
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
  val sortOrder: Int,
  val status: JobStatus,
  val progressPercent: Int = 0,
  val preset: CompressionPreset,
  val targetResolution: TargetResolution,
  val targetCodec: VideoCodec,
  val customBitrateKbps: Int,
  val outputUri: String? = null,
  val outputFileName: String? = null,
  val outputSizeBytes: Long = 0L,
  val usedHardwareEncoder: Boolean? = null,
  val encoderName: String? = null,
  val errorMessage: String? = null,
  val createdAt: Long = System.currentTimeMillis(),
  val startedAt: Long? = null,
  val completedAt: Long? = null,
)

class CompressXTypeConverters {
  @TypeConverter fun fromJobStatus(value: JobStatus): String = value.name

  @TypeConverter fun toJobStatus(value: String): JobStatus = JobStatus.valueOf(value)

  @TypeConverter fun fromPreset(value: CompressionPreset): String = value.name

  @TypeConverter fun toPreset(value: String): CompressionPreset = CompressionPreset.valueOf(value)

  @TypeConverter fun fromResolution(value: TargetResolution): String = value.name

  @TypeConverter fun toResolution(value: String): TargetResolution = TargetResolution.valueOf(value)

  @TypeConverter fun fromCodec(value: VideoCodec): String = value.name

  @TypeConverter fun toCodec(value: String): VideoCodec = VideoCodec.valueOf(value)
}
