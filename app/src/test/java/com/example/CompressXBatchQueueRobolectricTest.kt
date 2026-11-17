package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.CompressXDatabase
import com.example.data.VideoJobEntity
import com.example.data.VideoQueueRepository
import com.example.engine.CodecCapabilityInspector
import com.example.engine.CompressionPlanCalculator
import com.example.model.CompressionPreset
import com.example.model.JobStatus
import com.example.model.TargetResolution
import com.example.model.VideoCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class CompressXBatchQueueRobolectricTest {

  private lateinit var context: Context
  private lateinit var db: CompressXDatabase
  private lateinit var repo: VideoQueueRepository

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, CompressXDatabase::class.java).allowMainThreadQueries().build()
    repo = VideoQueueRepository(context, db.videoJobDao())
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `batch of 60 videos persists, reorders, pauses, resumes, retries, and recovers after process death`() =
    runTest {
      val batch =
        (1..60).map { idx ->
          VideoJobEntity(
            id = "job_$idx",
            sourceUri = "content://media/external/video/$idx",
            fileName = "clip_$idx.mp4",
            durationMs = 15_000L,
            originalSizeBytes = 25_000_000L,
            width = 1920,
            height = 1080,
            rotation = 0,
            sourceBitrateBps = 12_000_000,
            sortOrder = idx - 1,
            status = JobStatus.QUEUED,
            preset = CompressionPreset.BALANCED,
            targetResolution = TargetResolution.FHD_1080P,
            targetCodec = VideoCodec.H264,
            customBitrateKbps = 3500,
          )
        }

      db.videoJobDao().insertJobs(batch)
      val loaded = repo.allJobs.first()
      assertEquals(60, loaded.size)

      // Simulate active encoding -> process death -> recovery
      repo.updateStatusAndProgress("job_1", JobStatus.PROCESSING, 45)
      repo.resetInterruptedProcessingJobs()
      val recovered = repo.getJobById("job_1")
      assertNotNull(recovered)
      assertEquals(JobStatus.QUEUED, recovered?.status)
      assertEquals(0, recovered?.progressPercent)

      // Simulate pause and resume
      repo.updateStatusAndProgress("job_1", JobStatus.PROCESSING, 30)
      repo.pauseActiveProcessingJobs()
      assertEquals(JobStatus.PAUSED, repo.getJobById("job_1")?.status)
      repo.resumeAllPausedJobs()
      assertEquals(JobStatus.QUEUED, repo.getJobById("job_1")?.status)

      // Simulate failure and retry
      repo.markFailed("job_2", "Hardware codec reset")
      assertEquals(JobStatus.FAILED, repo.getJobById("job_2")?.status)
      repo.retryAllFailedJobs()
      assertEquals(JobStatus.QUEUED, repo.getJobById("job_2")?.status)

      // Simulate drag-and-drop reorder moving job_60 to index 0
      val reorderedIds = listOf("job_60") + (1..59).map { "job_$it" }
      repo.reorderJobs(reorderedIds)
      val nextUp = repo.getNextQueuedJob()
      assertEquals("job_60", nextUp?.id)

      // Verify savings stats calculation when job completes smaller
      val estimatedSmallerSize =
        CompressionPlanCalculator.estimateOutputSizeBytes(15_000L, 3_500_000, 128_000)
      assertTrue(estimatedSmallerSize < 25_000_000L)

      repo.markCompleted(
        id = "job_60",
        outputUri = "content://media/external/video/compressed_60",
        outputFileName = "clip_60_compressed.mp4",
        outputSizeBytes = estimatedSmallerSize,
        usedHardwareEncoder = false,
        encoderName = "c2.android.avc.encoder",
      )
      val stats = repo.computeStats(repo.allJobs.first())
      assertEquals(1, stats.completedCount)
      assertEquals(59, stats.queuedCount)
      assertTrue(stats.totalSavedBytes > 0L)
    }

  @Test
  fun `runtime codec inspector runs cleanly across API levels`() {
    val capabilities = CodecCapabilityInspector.inspect()
    assertNotNull(capabilities.encoders)
  }
}
