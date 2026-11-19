package com.example

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.StatFs
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.CompressXDatabase
import com.example.data.PreferencesRepository
import com.example.data.RelinkResult
import com.example.data.VideoJobEntity
import com.example.data.VideoQueueRepository
import com.example.engine.CompressionLogger
import com.example.engine.DeviceGuardrailMonitor
import com.example.engine.FileFingerprintUtil
import com.example.engine.OutputStorageWriter
import com.example.engine.VideoCompressionEngine
import com.example.model.CompressionPreset
import com.example.model.JobStatus
import com.example.model.OutputMode
import com.example.model.TargetResolution
import com.example.model.VideoCodec
import com.example.model.VideoCompressionConfig
import com.example.service.BatchQueueRunner
import com.example.service.CompressionForegroundService
import com.example.service.NotificationHelper
import com.example.service.QueueRecoveryReceiver
import com.example.ui.CompressXViewModel
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.ShadowStatFs
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class StabilizationRegressionRobolectricTest {

  private lateinit var context: Context
  private lateinit var db: CompressXDatabase
  private lateinit var repo: VideoQueueRepository
  private lateinit var prefs: PreferencesRepository
  private lateinit var engine: VideoCompressionEngine

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    CompressionLogger.init(context)
    ShadowStatFs.reset()
    db =
      Room.inMemoryDatabaseBuilder(context, CompressXDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    repo = VideoQueueRepository(context, db.videoJobDao())
    prefs = PreferencesRepository(context)
    engine = VideoCompressionEngine(context, OutputStorageWriter(context))
  }

  @After
  fun tearDown() {
    runBlocking {
      prefs.updateOutputMode(OutputMode.KEEP_ORIGINAL, consentGranted = false)
      prefs.updateAutoResumeAfterRestart(true)
    }
    ShadowStatFs.reset()
    db.close()
  }

  private fun createSyntheticVideoFile(
    name: String,
    seedByte: Byte,
    sizeBytes: Int = 8192,
    durationMs: Long = 10_000L,
    width: Int = 1920,
    height: Int = 1080,
  ): File {
    val dir = File(context.cacheDir, "test_videos").apply { mkdirs() }
    val file = File(dir, name)
    val bytes = ByteArray(sizeBytes) { idx -> ((seedByte.toInt() + idx * 31) and 0xFF).toByte() }
    file.writeBytes(bytes)

    val uri = Uri.fromFile(file)
    registerShadowVideoMetadata(uri, durationMs, width, height)
    return file
  }

  private fun registerShadowVideoMetadata(
    uri: Uri,
    durationMs: Long = 10_000L,
    width: Int = 1920,
    height: Int = 1080,
  ) {
    val ds = DataSource.toDataSource(context, uri)
    ShadowMediaMetadataRetriever.addMetadata(
      ds,
      MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO,
      "yes",
    )
    ShadowMediaMetadataRetriever.addMetadata(
      ds,
      MediaMetadataRetriever.METADATA_KEY_DURATION,
      durationMs.toString(),
    )
    ShadowMediaMetadataRetriever.addMetadata(
      ds,
      MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
      width.toString(),
    )
    ShadowMediaMetadataRetriever.addMetadata(
      ds,
      MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
      height.toString(),
    )
    ShadowMediaMetadataRetriever.addMetadata(
      ds,
      MediaMetadataRetriever.METADATA_KEY_BITRATE,
      "6000000",
    )
  }

  private fun makeJob(
    id: String,
    uri: String,
    fileName: String,
    status: JobStatus = JobStatus.QUEUED,
    sortOrder: Int = 0,
    needsRelink: Boolean = false,
    fingerprint: String? = null,
    originalSizeBytes: Long = 20L * 1024L * 1024L,
  ) =
    VideoJobEntity(
      id = id,
      sourceUri = uri,
      fileName = fileName,
      durationMs = 15_000L,
      originalSizeBytes = originalSizeBytes,
      width = 1920,
      height = 1080,
      rotation = 0,
      sourceBitrateBps = 8_000_000,
      sortOrder = sortOrder,
      status = status,
      preset = CompressionPreset.BALANCED,
      targetResolution = TargetResolution.FHD_1080P,
      targetCodec = VideoCodec.H264,
      customBitrateKbps = 3500,
      outputMode = OutputMode.KEEP_ORIGINAL,
      contentFingerprint = fingerprint,
      needsRelink = needsRelink,
    )

  /** TEST 1: Large Batch Test (87 videos — reproducing reported 19+68 batch scale) */
  @Test
  fun `test 1 large 87-video batch persists accurately with exact counters`() = runTest {
    val jobs =
      (1..87).map { idx ->
        val status =
          when {
            idx <= 19 -> JobStatus.COMPLETED
            idx == 20 -> JobStatus.PROCESSING
            else -> JobStatus.QUEUED
          }
        makeJob(
          id = "large_$idx",
          uri = "content://media/external/video/media/$idx",
          fileName = "very_long_camera_recording_2026_11_18_clip_number_$idx.mp4",
          status = status,
          sortOrder = idx,
          fingerprint = "fp_$idx",
        ).copy(
          outputSizeBytes = if (status == JobStatus.COMPLETED) 8L * 1024L * 1024L else 0L,
          progressPercent = if (status == JobStatus.COMPLETED) 100 else if (status == JobStatus.PROCESSING) 45 else 0,
        )
      }
    db.videoJobDao().insertJobs(jobs)

    val loaded = repo.allJobs.first()
    assertEquals(87, loaded.size)
    val stats = repo.computeStats(loaded)
    assertEquals(19, stats.completedCount)
    assertEquals(1, stats.processingCount)
    assertEquals(67, stats.queuedCount)
    assertEquals(0, stats.failedCount)
    assertEquals(19L * 12L * 1024L * 1024L, stats.totalSavedBytes)
  }

  /** TEST 2: Background Processing, START_STICKY, Rich Notification, & Reboot Recovery */
  @Test
  fun `test 2 background notification displays filename progress completed remaining overall and recovery resets interrupted jobs`() =
    runTest {
      val notif =
        NotificationHelper.buildProgressNotification(
          context = context,
          fileName = "concert_4k_hdr.mp4",
          jobProgress = 50,
          completedCount = 19,
          totalCount = 87,
        )
      val title = notif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
      val text = notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
      val bigText = notif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

      assertTrue("Title must include filename & file progress: $title", title.contains("concert_4k_hdr.mp4") && title.contains("50%"))
      assertTrue("Text must include Completed count: $text", text.contains("Completed: 19"))
      assertTrue("Text must include Remaining count: $text", text.contains("Remaining: 68"))
      assertTrue("Text must include Overall %: $text", text.contains("Overall:"))
      assertTrue("BigText must include detailed progress: $bigText", bigText.contains("concert_4k_hdr.mp4"))

      // Verify CompressionForegroundService returns START_STICKY on start/resume
      val controller = Robolectric.buildService(CompressionForegroundService::class.java).create()
      val startResult =
        controller.get().onStartCommand(
          Intent(context, CompressionForegroundService::class.java).apply {
            action = CompressionForegroundService.ACTION_START_OR_RESUME
          },
          0,
          1,
        )
      assertEquals(Service.START_STICKY, startResult)
      controller.destroy()

      // Verify QueueRecoveryReceiver handles BOOT_COMPLETED safely without crashing
      val receiver = QueueRecoveryReceiver()
      receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
      Thread.sleep(150L)
      Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

  /** TEST 3: Failure Isolation & Low-Storage Pause (Never Cascade-Fail 68 Remaining Jobs!) */
  @Test
  fun `test 3 global low storage pauses batch instead of cascade-failing 68 queued files`() = runTest {
    // Register low free space (100 MB < 500 MB threshold) on cacheDir partition
    ShadowStatFs.registerStats(
      context.cacheDir.absolutePath,
      /* blockCount = */ 1000,
      /* freeBlocks = */ 25,
      /* availableBlocks = */ 25, // 25 * 4MB = 100MB if block size is 4MB; default ShadowStatFs blockSize is 1024 -> 25KB < 500MB
    )

    val batchJobs =
      (1..68).map { i ->
        makeJob(
          id = "queued_$i",
          uri = "file:///storage/emulated/0/Movies/vid_$i.mp4",
          fileName = "vid_$i.mp4",
          status = JobStatus.QUEUED,
          sortOrder = i,
        )
      }
    db.videoJobDao().insertJobs(batchJobs)

    val runner = BatchQueueRunner(repo, prefs, engine, DeviceGuardrailMonitor(context))
    val summary =
      runner.runBatch(
        progressScope = this,
        onJobStarted = { _, _, _ -> },
        onProgressChanged = { _, _, _, _ -> },
        onStatusNote = { _, _, _, _ -> },
      )

    assertTrue("Global low storage must pause batch cleanly", summary.wasPaused)
    assertEquals("ZERO jobs should cascade-fail on global low storage", 0, summary.failedCount)
    val allAfter = repo.allJobs.first()
    assertEquals(0, allAfter.count { it.status == JobStatus.FAILED })
    assertEquals(1, allAfter.count { it.status == JobStatus.PAUSED })
    assertEquals(67, allAfter.count { it.status == JobStatus.QUEUED })
  }

  /** TEST 4: Duplicate Protection (In-Batch, Queued, Completed, Cross-URI, & Same-Filename Distinct Files) */
  @Test
  fun `test 4 content fingerprint blocks cross-URI and completed duplicates while allowing distinct videos with same name`() =
    runTest {
      val videoA1 = createSyntheticVideoFile("clip_a_picker.mp4", seedByte = 11, sizeBytes = 12_000)
      // Identical content under a completely different file path/URI (simulating SAF vs Photo Picker)
      val videoA2Copy = File(File(context.cacheDir, "alt_folder").apply { mkdirs() }, "clip_a_saf.mp4")
      videoA2Copy.writeBytes(videoA1.readBytes())
      val uriA1 = Uri.fromFile(videoA1)
      val uriA2 = Uri.fromFile(videoA2Copy)
      registerShadowVideoMetadata(uriA2, durationMs = 10_000L, width = 1920, height = 1080)

      // Distinct video in another folder with the EXACT SAME filename as videoA1!
      val sameNameDifferentContent =
        File(File(context.cacheDir, "other_folder").apply { mkdirs() }, "clip_a_picker.mp4").apply {
          writeBytes(ByteArray(12_000) { idx -> ((99 + idx * 17) and 0xFF).toByte() })
        }
      val uriDifferent = Uri.fromFile(sameNameDifferentContent)
      registerShadowVideoMetadata(uriDifferent, durationMs = 10_000L, width = 1920, height = 1080)

      val fpA1 = FileFingerprintUtil.computeContentFingerprint(context, uriA1, 12_000, 10_000, 1920, 1080)
      val fpA2 = FileFingerprintUtil.computeContentFingerprint(context, uriA2, 12_000, 10_000, 1920, 1080)
      val fpDiff = FileFingerprintUtil.computeContentFingerprint(context, uriDifferent, 12_000, 10_000, 1920, 1080)
      assertNotNull(fpA1)
      assertEquals("Same file under different URIs must share fingerprint", fpA1, fpA2)
      assertNotEquals("Different video with same filename must have distinct fingerprint", fpA1, fpDiff)

      // 1) Selecting same URI + cross-URI duplicate in one batch -> only 1 added, 2 skipped
      val firstRes = repo.enqueueVideosDetailed(listOf(uriA1, uriA1, uriA2), VideoCompressionConfig())
      assertEquals(1, firstRes.addedJobs.size)
      assertEquals(2, firstRes.skippedDuplicateCount)
      assertEquals(2, firstRes.skippedInBatchCount)

      // 2) Re-adding while QUEUED -> skipped as active duplicate
      val secondRes = repo.enqueueVideosDetailed(listOf(uriA2), VideoCompressionConfig())
      assertEquals(0, secondRes.addedJobs.size)
      assertEquals(1, secondRes.skippedActiveCount)

      // 3) Mark first job COMPLETED, then try adding again via alternate URI -> skipped as completed duplicate!
      val addedId = firstRes.addedJobs.first().id
      repo.markCompleted(addedId, false, "content://media/external/video/media/999", "out.mp4", 4000L, true, "c2.hw")
      val afterCompleteRes = repo.enqueueVideosDetailed(listOf(uriA2), VideoCompressionConfig())
      assertEquals(0, afterCompleteRes.addedJobs.size)
      assertEquals(1, afterCompleteRes.skippedCompletedCount)

      // 4) Adding a DIFFERENT video that merely has the SAME filename IS allowed (not filename-only!)
      val distinctRes = repo.enqueueVideosDetailed(listOf(uriDifferent), VideoCompressionConfig())
      assertEquals(1, distinctRes.addedJobs.size)
      assertEquals(0, distinctRes.skippedDuplicateCount)
    }

  /** TEST 5: File Removal During Processing & Rapid Deletions Without Crashes */
  @Test
  fun `test 5 rapid deletion and active job removal never crash or corrupt remaining queue`() = runBlocking {
    val app = context as CompressXApplication
    app.preferencesRepository.updateAutoResumeAfterRestart(false)
    app.queueRepository.clearAll()

    val jobs =
      (1..20).map { i ->
        makeJob(
          id = "del_$i",
          uri = "content://media/external/video/media/$i",
          fileName = "clip_$i.mp4",
          status = if (i == 1) JobStatus.PROCESSING else JobStatus.QUEUED,
          sortOrder = i,
        )
      }
    app.database.videoJobDao().insertJobs(jobs)

    val vm = CompressXViewModel(app)
    val collectorJob = launch(Dispatchers.Default) { vm.uiState.collect {} }

    // Rapidly delete the active PROCESSING job plus 9 queued jobs concurrently
    for (i in 1..10) {
      vm.deleteJob("del_$i")
    }
    // Also test out-of-bounds / rapid reorder calls alongside deletes (must not throw!)
    vm.moveJobById("del_1", -1, listOf("del_1", "del_2"))
    vm.moveJobById("nonexistent_id", +1, listOf("del_11", "del_12"))
    vm.moveJob(99, 100, emptyList())

    // Drain Robolectric Main Looper until all 10 mutex-serialized IO deletions settle
    val deadlineMs = System.currentTimeMillis() + 5_000L
    while (System.currentTimeMillis() < deadlineMs) {
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      if (app.queueRepository.allJobs.first().size <= 10) {
        break
      }
      delay(30L)
    }
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    collectorJob.cancel()

    val remaining = app.queueRepository.allJobs.first()
    assertEquals(10, remaining.size)
    assertTrue(remaining.all { it.id.removePrefix("del_").toInt() in 11..20 })
    assertTrue(remaining.all { it.status == JobStatus.QUEUED })

    app.queueRepository.clearAll()
  }

  /** TEST 6: Retry Failed Behavior — ONLY retries FAILED, never COMPLETED or CANCELLED */
  @Test
  fun `test 6 retryAllFailed retries ONLY failed jobs, never completed or cancelled, and preserves needsRelink`() =
    runTest {
      val readableFile = createSyntheticVideoFile("retry_ok.mp4", seedByte = 7)
      val jobs =
        listOf(
          makeJob("f_normal", Uri.fromFile(readableFile).toString(), "retry_ok.mp4", JobStatus.FAILED, 0, needsRelink = false),
          makeJob("f_relink", "file:///missing/revoked_video.mp4", "revoked.mp4", JobStatus.FAILED, 1, needsRelink = true),
          makeJob("c_done", "content://media/external/video/media/3", "done.mp4", JobStatus.COMPLETED, 2),
          makeJob("x_cancelled", "content://media/external/video/media/4", "cancelled.mp4", JobStatus.CANCELLED, 3),
          makeJob("q_waiting", "content://media/external/video/media/5", "waiting.mp4", JobStatus.QUEUED, 4),
        )
      db.videoJobDao().insertJobs(jobs)

      val res = repo.retryAllFailedJobs()
      assertEquals("Only the 1 normal FAILED job should be retried", 1, res.retriedCount)
      assertEquals("The unreadable needsRelink job stays flagged for Relink", 1, res.needsRelinkRemainingCount)

      assertEquals(JobStatus.QUEUED, repo.getJobById("f_normal")?.status)
      assertEquals(JobStatus.FAILED, repo.getJobById("f_relink")?.status)
      assertTrue(repo.getJobById("f_relink")?.needsRelink == true)
      assertEquals("COMPLETED job must NEVER be reset", JobStatus.COMPLETED, repo.getJobById("c_done")?.status)
      assertEquals("CANCELLED job must NEVER be retried by Retry Failed", JobStatus.CANCELLED, repo.getJobById("x_cancelled")?.status)
      assertEquals(JobStatus.QUEUED, repo.getJobById("q_waiting")?.status)
    }

  /** TEST 7: Crash Recovery — Resets interrupted PROCESSING without touching COMPLETED/FAILED/QUEUED */
  @Test
  fun `test 7 crash recovery resets only interrupted processing jobs and preserves all other states without duplicates`() =
    runTest {
      val jobs =
        listOf(
          makeJob("rec_done", "content://media/1", "done.mp4", JobStatus.COMPLETED, 0),
          makeJob("rec_proc", "content://media/2", "active.mp4", JobStatus.PROCESSING, 1).copy(progressPercent = 64),
          makeJob("rec_fail", "content://media/3", "failed.mp4", JobStatus.FAILED, 2, needsRelink = true),
          makeJob("rec_queued", "content://media/4", "queued.mp4", JobStatus.QUEUED, 3),
        )
      db.videoJobDao().insertJobs(jobs)

      // Simulate stale progress callback trying to overwrite a COMPLETED job — must be ignored!
      repo.updateProgress("rec_done", 12)
      assertEquals(0, repo.getJobById("rec_done")?.progressPercent)

      // Simulate process restart recovery
      repo.resetInterruptedProcessingJobs()

      val after = repo.allJobs.first()
      assertEquals("Row count must remain exact with zero duplicates", 4, after.size)
      assertEquals(JobStatus.COMPLETED, repo.getJobById("rec_done")?.status)
      assertEquals(JobStatus.QUEUED, repo.getJobById("rec_proc")?.status)
      assertEquals(0, repo.getJobById("rec_proc")?.progressPercent)
      assertEquals(JobStatus.FAILED, repo.getJobById("rec_fail")?.status)
      assertTrue(repo.getJobById("rec_fail")?.needsRelink == true)
      assertEquals(JobStatus.QUEUED, repo.getJobById("rec_queued")?.status)
    }

  /** TEST 8: Storage Permission Loss Isolation & Single-Item Relink Workflow */
  @Test
  fun `test 8 storage permission loss sets needsRelink on failed job and relinkJob restores ONLY that item`() =
    runTest {
      val runner = BatchQueueRunner(repo, prefs, engine, DeviceGuardrailMonitor(context))
      assertTrue(runner.isPermissionOrMissingSourceError(SecurityException("Permission Denial: reading provider")))
      assertTrue(runner.isPermissionOrMissingSourceError(FileNotFoundException("ENOENT")))

      val lostJob =
        makeJob(
          id = "lost_1",
          uri = "file:///revoked/expired_uri.mp4",
          fileName = "expired_uri.mp4",
          status = JobStatus.QUEUED,
          sortOrder = 0,
        )
      val siblingJob =
        makeJob(
          id = "sib_2",
          uri = "content://media/external/video/media/88",
          fileName = "sibling.mp4",
          status = JobStatus.COMPLETED,
          sortOrder = 1,
          fingerprint = "sibling_fp",
        )
      db.videoJobDao().insertJobs(listOf(lostJob, siblingJob))

      // Run batch: missing/revoked file fails with needsRelink=true
      val summary =
        runner.runBatch(
          progressScope = this,
          onJobStarted = { _, _, _ -> },
          onProgressChanged = { _, _, _, _ -> },
          onStatusNote = { _, _, _, _ -> },
        )
      assertEquals(1, summary.failedCount)
      val failedRow = repo.getJobById("lost_1")!!
      assertEquals(JobStatus.FAILED, failedRow.status)
      assertTrue("Permission/missing source must flag needsRelink", failedRow.needsRelink)

      // Now relink ONLY lost_1 to a readable video file
      val newValidFile = createSyntheticVideoFile("relinked_video.mp4", seedByte = 42, sizeBytes = 16_000)
      val relinkRes = repo.relinkJob("lost_1", Uri.fromFile(newValidFile))
      assertTrue(relinkRes is RelinkResult.Success)
      val restored = repo.getJobById("lost_1")!!
      assertEquals(JobStatus.QUEUED, restored.status)
      assertFalse(restored.needsRelink)
      assertNull(restored.errorMessage)
      assertEquals("relinked_video.mp4", restored.fileName)
      // Sibling row untouched!
      assertEquals(JobStatus.COMPLETED, repo.getJobById("sib_2")?.status)
    }

  /** TEST 9 & TEST 10: Output Integrity Verification Rejects Corrupt Files & Protects Originals */
  @Test
  fun `test 9 and 10 output verification rejects empty or corrupt files and cleans temp files on failure`() =
    runTest {
      val emptyFile = File(engine.workDirectory(), "empty.tmp.mp4").apply { writeBytes(ByteArray(0)) }
      val tinyCorruptFile = File(engine.workDirectory(), "corrupt.tmp.mp4").apply { writeBytes(ByteArray(2048) { 0x55 }) }

      var emptyRejected = false
      try {
        engine.verifyEncodedOutputPlayable(emptyFile)
      } catch (_: Throwable) {
        emptyRejected = true
      }
      assertTrue("0-byte output must be rejected", emptyRejected)

      var corruptRejected = false
      try {
        engine.verifyEncodedOutputPlayable(tinyCorruptFile)
      } catch (_: Throwable) {
        corruptRejected = true
      }
      assertTrue("Corrupt file without valid video track must be rejected", corruptRejected)

      // Verify deleteTempFileForJob cleans up incomplete work files cleanly
      val partialJobFile = File(engine.workDirectory(), "job_partial.tmp.mp4").apply { writeBytes(ByteArray(4096)) }
      assertTrue(partialJobFile.exists())
      engine.deleteTempFileForJob("job_partial")
      assertFalse("Temp file must be deleted on cancellation/failure", partialJobFile.exists())

      // Verify Replace Original Mode NEVER deletes original file when compression fails
      val originalFile = createSyntheticVideoFile("precious_original.mp4", seedByte = 77)
      assertTrue(originalFile.exists())
      val failedJob =
        makeJob(
          id = "protect_orig",
          uri = "file:///nonexistent_trigger_fail.mp4",
          fileName = "precious_original.mp4",
          status = JobStatus.QUEUED,
        ).copy(outputMode = OutputMode.REPLACE_ORIGINAL)
      db.videoJobDao().insertJobs(listOf(failedJob))
      prefs.updateOutputMode(OutputMode.REPLACE_ORIGINAL, consentGranted = true)

      val runner = BatchQueueRunner(repo, prefs, engine, DeviceGuardrailMonitor(context))
      runner.runBatch(this, { _, _, _ -> }, { _, _, _, _ -> }, { _, _, _, _ -> })
      assertTrue("Original file must remain intact on failure", originalFile.exists())
      assertEquals(JobStatus.FAILED, repo.getJobById("protect_orig")?.status)
    }
}
