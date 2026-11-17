package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.model.JobStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoJobDao {

  @Query("SELECT * FROM video_jobs ORDER BY sortOrder ASC, createdAt ASC")
  fun observeAllJobs(): Flow<List<VideoJobEntity>>

  @Query("SELECT * FROM video_jobs WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
  fun observeCompletedHistory(): Flow<List<VideoJobEntity>>

  @Query("SELECT * FROM video_jobs WHERE id = :id LIMIT 1")
  suspend fun getJobById(id: String): VideoJobEntity?

  @Query("SELECT * FROM video_jobs WHERE status = 'QUEUED' ORDER BY sortOrder ASC, createdAt ASC LIMIT 1")
  suspend fun getNextQueuedJob(): VideoJobEntity?

  @Query("SELECT COUNT(*) FROM video_jobs WHERE status IN ('QUEUED', 'PROCESSING')")
  suspend fun countPendingOrActive(): Int

  @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM video_jobs")
  suspend fun getMaxSortOrder(): Int

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertJobs(jobs: List<VideoJobEntity>)

  @Update suspend fun updateJob(job: VideoJobEntity)

  @Query("UPDATE video_jobs SET sortOrder = :sortOrder WHERE id = :id")
  suspend fun updateSortOrder(id: String, sortOrder: Int)

  @Transaction
  suspend fun reorderJobs(orderedIds: List<String>) {
    orderedIds.forEachIndexed { index, id -> updateSortOrder(id, index) }
  }

  @Query("UPDATE video_jobs SET status = :status, progressPercent = :progress WHERE id = :id")
  suspend fun updateStatusAndProgress(id: String, status: JobStatus, progress: Int)

  @Query("UPDATE video_jobs SET progressPercent = :progress WHERE id = :id")
  suspend fun updateProgress(id: String, progress: Int)

  @Query(
    """
    UPDATE video_jobs
    SET status = 'COMPLETED',
        progressPercent = 100,
        outputUri = :outputUri,
        outputFileName = :outputFileName,
        outputSizeBytes = :outputSizeBytes,
        usedHardwareEncoder = :usedHardwareEncoder,
        encoderName = :encoderName,
        errorMessage = NULL,
        completedAt = :completedAt
    WHERE id = :id
    """
  )
  suspend fun markCompleted(
    id: String,
    outputUri: String,
    outputFileName: String,
    outputSizeBytes: Long,
    usedHardwareEncoder: Boolean,
    encoderName: String,
    completedAt: Long,
  )

  @Query(
    """
    UPDATE video_jobs
    SET status = 'FAILED',
        errorMessage = :errorMessage,
        completedAt = :completedAt
    WHERE id = :id
    """
  )
  suspend fun markFailed(id: String, errorMessage: String, completedAt: Long)

  @Query("UPDATE video_jobs SET status = 'QUEUED', progressPercent = 0, errorMessage = NULL WHERE status = 'PROCESSING'")
  suspend fun resetInterruptedProcessingJobs()

  @Query("UPDATE video_jobs SET status = 'QUEUED', progressPercent = 0, errorMessage = NULL WHERE status = 'PAUSED'")
  suspend fun resumeAllPausedJobs()

  @Query("UPDATE video_jobs SET status = 'PAUSED' WHERE status = 'PROCESSING'")
  suspend fun pauseActiveProcessingJobs()

  @Query("UPDATE video_jobs SET status = 'QUEUED', progressPercent = 0, errorMessage = NULL WHERE status IN ('FAILED', 'CANCELLED')")
  suspend fun retryAllFailedJobs()

  @Query("UPDATE video_jobs SET status = 'QUEUED', progressPercent = 0, errorMessage = NULL WHERE id = :id")
  suspend fun retryJob(id: String)

  @Query("DELETE FROM video_jobs WHERE id = :id")
  suspend fun deleteJob(id: String)

  @Query("DELETE FROM video_jobs WHERE status IN ('COMPLETED', 'CANCELLED')")
  suspend fun clearCompletedAndCancelled()

  @Query("DELETE FROM video_jobs WHERE status != 'PROCESSING'")
  suspend fun clearAllNonProcessing()

  @Query("DELETE FROM video_jobs")
  suspend fun clearAllJobs()
}
