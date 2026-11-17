package com.example

import android.content.Context
import android.content.Intent
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.CompressXDatabase
import com.example.data.PreferencesRepository
import com.example.data.VideoJobEntity
import com.example.data.VideoQueueRepository
import com.example.engine.CompressionLogger
import com.example.engine.CompressionPlanCalculator
import com.example.engine.DeviceGuardrailMonitor
import com.example.engine.OutputStorageWriter
import com.example.engine.VideoCompressionEngine
import com.example.model.CodecCapabilities
import com.example.model.CompressionPlan
import com.example.model.CompressionPreset
import com.example.model.JobStatus
import com.example.model.TargetResolution
import com.example.model.VideoCodec
import com.example.service.BatchQueueRunner
import com.example.service.CompressionForegroundService
import java.io.FileNotFoundException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class CompressionReliabilityRobolectricTest {

  private lateinit var context: Context
  private lateinit var db: CompressXDatabase
  private lateinit var repo: VideoQueueRepository
  private lateinit var storageWriter: OutputStorageWriter
  private lateinit var engine: VideoCompressionEngine

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    CompressionLogger.init(context)
    db = Room.inMemoryDatabaseBuilder(context, CompressXDatabase::class.java).allowMainThreadQueries().build()
    repo = VideoQueueRepository(context, db.videoJobDao())
    storageWriter = OutputStorageWriter(context)
    engine = VideoCompressionEngine(context, storageWriter)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `attempt cascade falls back from hardware H265 or H264 to software H264`() {
    val hwCaps =
      CodecCapabilities(
        h264HardwareSupported = true,
        h264SoftwareSupported = true,
        h265HardwareSupported = true,
        h265SoftwareSupported = true,
        av1Supported = false,
        preferredH264Encoder = "c2.qti.avc.encoder",
        preferredH265Encoder = "c2.qti.hevc.encoder",
        encoders = emptyList(),
      )

    val hevcPlan =
      CompressionPlan(
        targetVideoBitrateBps = 4_000_000,
        targetAudioBitrateBps = 128_000,
        targetDisplayWidth = 1080,
        targetDisplayHeight = 1920,
        shouldScale = true,
        mimeType = MimeTypes.VIDEO_H265,
      )

    val cascadeHevc = engine.buildAttemptCascade(hevcPlan, preferHardware = true, capabilities = hwCaps)
    assertTrue(cascadeHevc.size >= 3)
    assertEquals(MimeTypes.VIDEO_H265, cascadeHevc[0].mimeType)
    assertFalse(cascadeHevc[0].forceSoftwareEncoder)
    assertEquals(MimeTypes.VIDEO_H264, cascadeHevc[1].mimeType)
    assertTrue(cascadeHevc[1].forceSoftwareEncoder)
    assertEquals(MimeTypes.VIDEO_H264, cascadeHevc[2].mimeType)
    assertTrue(cascadeHevc[2].forceSoftwareEncoder)
    assertFalse(cascadeHevc[2].applyBitrateSetting)

    val h264Plan = hevcPlan.copy(mimeType = MimeTypes.VIDEO_H264)
    val cascadeH264Hw = engine.buildAttemptCascade(h264Plan, preferHardware = true, capabilities = hwCaps)
    assertFalse(cascadeH264Hw[0].forceSoftwareEncoder)
    assertTrue(cascadeH264Hw.any { it.mimeType == MimeTypes.VIDEO_H264 && it.forceSoftwareEncoder })

    val cascadeSwOnly = engine.buildAttemptCascade(h264Plan, preferHardware = false, capabilities = hwCaps)
    assertTrue(cascadeSwOnly[0].forceSoftwareEncoder)
  }

  @Test
  fun `logger formats actionable error messages for ExportException and missing files`() {
    val exportEx =
      ExportException.createForUnexpected(IllegalStateException("MediaCodec configure rejected 1080p"))
    val msg = CompressionLogger.formatUserErrorMessage(exportEx)
    assertTrue(msg.contains("ERROR_CODE"))
    assertTrue(msg.contains("MediaCodec configure rejected"))

    val secMsg = CompressionLogger.formatUserErrorMessage(SecurityException("Permission denial"))
    assertTrue(secMsg.contains("permission", ignoreCase = true))

    val fnfMsg = CompressionLogger.formatUserErrorMessage(FileNotFoundException("missing.mp4"))
    assertTrue(fnfMsg.contains("could not be opened", ignoreCase = true))
  }

  @Test
  fun `filename sanitization strips illegal characters and preserves original file naming`() {
    assertEquals(
      "my_clip_2026_compressed.mp4",
      CompressionPlanCalculator.buildCompressedFileName("my:clip/2026?.mov"),
    )
    assertEquals(
      "vacation_compressed.mp4",
      CompressionPlanCalculator.buildCompressedFileName("vacation_compressed.mp4"),
    )

    val candidates = storageWriter.buildCandidateFileNames("clip_compressed.mp4")
    assertEquals("clip_compressed.mp4", candidates.first())
    assertTrue(candidates.contains("clip_compressed_(1).mp4"))
    assertTrue(candidates.size >= 10)
  }

  @Test
  fun `runner isolates failing video job without crashing and continues queue`() = runTest {
    val badJob =
      VideoJobEntity(
        id = "bad_job",
        sourceUri = "file:///nonexistent/missing_input.mp4",
        fileName = "missing_input.mp4",
        durationMs = 5_000L,
        originalSizeBytes = 2_000_000L,
        width = 1280,
        height = 720,
        rotation = 0,
        sourceBitrateBps = 3_000_000,
        sortOrder = 0,
        status = JobStatus.QUEUED,
        preset = CompressionPreset.BALANCED,
        targetResolution = TargetResolution.HD_720P,
        targetCodec = VideoCodec.H264,
        customBitrateKbps = 2400,
      )
    db.videoJobDao().insertJobs(listOf(badJob))

    val runner =
      BatchQueueRunner(
        repo = repo,
        prefs = PreferencesRepository(context),
        engine = engine,
        guardrails = DeviceGuardrailMonitor(context),
      )

    val summary =
      runner.runBatch(
        progressScope = this,
        onJobStarted = { _, _, _ -> },
        onProgressChanged = { _, _, _, _ -> },
        onStatusNote = { _, _, _, _ -> },
      )

    val updated = repo.getJobById("bad_job")
    assertNotNull(updated)
    // Either low storage on CI host or missing source file must mark FAILED cleanly with real error message
    assertEquals(JobStatus.FAILED, updated?.status)
    assertFalse(updated?.errorMessage.isNullOrBlank())
    assertEquals(1, summary.failedCount)
  }

  @Test
  fun `foreground service handles start, pause, and Android 15 onTimeout safely`() {
    val controller = Robolectric.buildService(CompressionForegroundService::class.java).create()
    val service = controller.get()

    val startIntent =
      Intent(context, CompressionForegroundService::class.java).apply {
        action = CompressionForegroundService.ACTION_START_OR_RESUME
      }
    service.onStartCommand(startIntent, 0, 1)

    val pauseIntent =
      Intent(context, CompressionForegroundService::class.java).apply {
        action = CompressionForegroundService.ACTION_PAUSE
      }
    service.onStartCommand(pauseIntent, 0, 2)

    service.onTimeout(2, 0)
    controller.destroy()
  }
}
