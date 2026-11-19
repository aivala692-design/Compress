package com.example

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.CompressXDatabase
import com.example.data.PreferencesRepository
import com.example.data.VideoJobEntity
import com.example.data.VideoQueueRepository
import com.example.engine.CompressionLogger
import com.example.engine.DeviceGuardrailMonitor
import com.example.engine.MediaReplacementManager
import com.example.engine.OutputStorageWriter
import com.example.engine.ReplacementOutcome
import com.example.engine.ResolvedReplaceTarget
import com.example.engine.VideoCompressionEngine
import com.example.model.CompressionPreset
import com.example.model.JobStatus
import com.example.model.OutputMode
import com.example.model.TargetResolution
import com.example.model.VideoCodec
import com.example.service.BatchQueueRunner
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ReplaceOriginalModeRobolectricTest {

  private lateinit var context: Context
  private lateinit var db: CompressXDatabase
  private lateinit var repo: VideoQueueRepository
  private lateinit var storageWriter: OutputStorageWriter
  private lateinit var replacementManager: MediaReplacementManager

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    CompressionLogger.init(context)
    db =
      Room.inMemoryDatabaseBuilder(context, CompressXDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    repo = VideoQueueRepository(context, db.videoJobDao())
    storageWriter = OutputStorageWriter(context)
    replacementManager = MediaReplacementManager(context, storageWriter)
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `replace original mode defaults to Keep Original until user grants explicit consent`() = runTest {
    val prefs = PreferencesRepository(context)
    val initial = prefs.configFlow.first()
    assertEquals(OutputMode.KEEP_ORIGINAL, initial.outputMode)
    assertFalse(initial.replaceOriginalConsentGranted)
    assertFalse(initial.isReplaceOriginalActive)

    // Attempting to select REPLACE_ORIGINAL without consent keeps it inactive
    prefs.updateOutputMode(OutputMode.REPLACE_ORIGINAL)
    val withoutConsent = prefs.configFlow.first()
    assertFalse(withoutConsent.isReplaceOriginalActive)

    // Granting explicit consent activates REPLACE_ORIGINAL
    prefs.updateOutputMode(OutputMode.REPLACE_ORIGINAL, consentGranted = true)
    val withConsent = prefs.configFlow.first()
    assertEquals(OutputMode.REPLACE_ORIGINAL, withConsent.outputMode)
    assertTrue(withConsent.replaceOriginalConsentGranted)
    assertTrue(withConsent.isReplaceOriginalActive)
  }

  @Test
  fun `missing or corrupt staged video preserves original and returns ReplacementOutcome Failed`() {
    val missingJob =
      VideoJobEntity(
        id = "replace_missing_staged",
        sourceUri = "content://media/external/video/media/101",
        fileName = "holiday.mp4",
        durationMs = 8_000L,
        originalSizeBytes = 10_000_000L,
        width = 1920,
        height = 1080,
        rotation = 0,
        sourceBitrateBps = 8_000_000,
        sortOrder = 0,
        status = JobStatus.AWAITING_APPROVAL,
        preset = CompressionPreset.BALANCED,
        targetResolution = TargetResolution.FHD_1080P,
        targetCodec = VideoCodec.H264,
        customBitrateKbps = 3500,
        outputMode = OutputMode.REPLACE_ORIGINAL,
        stagedTempPath = File(context.filesDir, "nonexistent_staged.mp4").absolutePath,
      )

    val outcome = replacementManager.executeSingleReplacement(missingJob)
    assertTrue(outcome is ReplacementOutcome.Failed)
    val error = (outcome as ReplacementOutcome.Failed).errorMessage
    assertTrue(error.contains("original preserved", ignoreCase = true) || error.contains("verification", ignoreCase = true))

    // Also verify read-only external provider returns UnsupportedTarget instead of deleting/modifying
    val readOnlyResolved =
      replacementManager.resolveTargetUri(Uri.parse("content://com.thirdparty.readonly/video/55"))
    assertTrue(readOnlyResolved is ResolvedReplaceTarget.UnsupportedTarget)

    // Canonical MediaStore URI resolves as MediaStoreTarget
    val mediaUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "42")
    val mediaResolved = replacementManager.resolveTargetUri(mediaUri)
    assertTrue(mediaResolved is ResolvedReplaceTarget.MediaStoreTarget)
  }

  @Test
  fun `stats track awaitingApprovalCount and replacedCount separately from failed jobs`() = runTest {
    val jobs =
      listOf(
        VideoJobEntity(
          id = "j1",
          sourceUri = "content://media/external/video/media/1",
          fileName = "one.mp4",
          durationMs = 5_000L,
          originalSizeBytes = 12_000_000L,
          width = 1920,
          height = 1080,
          rotation = 0,
          sourceBitrateBps = 10_000_000,
          sortOrder = 0,
          status = JobStatus.AWAITING_APPROVAL,
          preset = CompressionPreset.BALANCED,
          targetResolution = TargetResolution.FHD_1080P,
          targetCodec = VideoCodec.H264,
          customBitrateKbps = 3500,
          outputMode = OutputMode.REPLACE_ORIGINAL,
          stagedTempPath = "/tmp/fake_one.staged.mp4",
          outputSizeBytes = 4_000_000L,
        ),
        VideoJobEntity(
          id = "j2",
          sourceUri = "content://media/external/video/media/2",
          fileName = "two.mp4",
          durationMs = 5_000L,
          originalSizeBytes = 15_000_000L,
          width = 1920,
          height = 1080,
          rotation = 0,
          sourceBitrateBps = 10_000_000,
          sortOrder = 1,
          status = JobStatus.COMPLETED,
          preset = CompressionPreset.BALANCED,
          targetResolution = TargetResolution.FHD_1080P,
          targetCodec = VideoCodec.H264,
          customBitrateKbps = 3500,
          outputMode = OutputMode.REPLACE_ORIGINAL,
          replacedOriginal = true,
          outputSizeBytes = 5_000_000L,
        ),
        VideoJobEntity(
          id = "j3",
          sourceUri = "file:///missing/three.mp4",
          fileName = "three.mp4",
          durationMs = 5_000L,
          originalSizeBytes = 8_000_000L,
          width = 1280,
          height = 720,
          rotation = 0,
          sourceBitrateBps = 5_000_000,
          sortOrder = 2,
          status = JobStatus.FAILED,
          preset = CompressionPreset.BALANCED,
          targetResolution = TargetResolution.HD_720P,
          targetCodec = VideoCodec.H264,
          customBitrateKbps = 2000,
          outputMode = OutputMode.REPLACE_ORIGINAL,
          errorMessage = "Source video file could not be opened.",
        ),
      )
    db.videoJobDao().insertJobs(jobs)

    val stats = repo.computeStats(repo.allJobs.first())
    assertEquals(1, stats.awaitingApprovalCount)
    assertEquals(1, stats.completedCount)
    assertEquals(1, stats.replacedCount)
    assertEquals(1, stats.failedCount)
    assertEquals(10_000_000L, stats.totalSavedBytes)

    // Failing or deleting must clean up staged temp file only, never source URI
    val fakeStaged = File(context.filesDir, "test_cleanup_staged.mp4").apply { writeBytes(ByteArray(2048)) }
    assertTrue(fakeStaged.exists())
    repo.markAwaitingApproval("j1", fakeStaged.absolutePath, 4_000_000L, true, "c2.test")
    repo.markFailed("j1", "Replacement cancelled")
    assertFalse(fakeStaged.exists())
  }

  @Test
  fun `room migration 1_2 preserves existing queue rows when upgrading schema`() {
    val dbName = "migration_test_v1_v2.db"
    context.deleteDatabase(dbName)

    val helperFactory = FrameworkSQLiteOpenHelperFactory()
    val v1Helper =
      helperFactory.create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
          .name(dbName)
          .callback(
            object : SupportSQLiteOpenHelper.Callback(1) {
              override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                  """
                  CREATE TABLE IF NOT EXISTS `video_jobs` (
                    `id` TEXT NOT NULL,
                    `sourceUri` TEXT NOT NULL,
                    `fileName` TEXT NOT NULL,
                    `durationMs` INTEGER NOT NULL,
                    `originalSizeBytes` INTEGER NOT NULL,
                    `width` INTEGER NOT NULL,
                    `height` INTEGER NOT NULL,
                    `rotation` INTEGER NOT NULL,
                    `sourceBitrateBps` INTEGER NOT NULL,
                    `sortOrder` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `progressPercent` INTEGER NOT NULL,
                    `preset` TEXT NOT NULL,
                    `targetResolution` TEXT NOT NULL,
                    `targetCodec` TEXT NOT NULL,
                    `customBitrateKbps` INTEGER NOT NULL,
                    `outputUri` TEXT,
                    `outputFileName` TEXT,
                    `outputSizeBytes` INTEGER NOT NULL,
                    `usedHardwareEncoder` INTEGER,
                    `encoderName` TEXT,
                    `errorMessage` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `startedAt` INTEGER,
                    `completedAt` INTEGER,
                    PRIMARY KEY(`id`)
                  )
                  """.trimIndent()
                )
                db.execSQL(
                  """
                  INSERT INTO `video_jobs` (
                    id, sourceUri, fileName, durationMs, originalSizeBytes, width, height, rotation,
                    sourceBitrateBps, sortOrder, status, progressPercent, preset, targetResolution,
                    targetCodec, customBitrateKbps, outputUri, outputFileName, outputSizeBytes,
                    usedHardwareEncoder, encoderName, errorMessage, createdAt, startedAt, completedAt
                  ) VALUES (
                    'v1_row', 'content://media/external/video/media/9', 'legacy.mp4', 10000, 5000000,
                    1280, 720, 0, 4000000, 0, 'COMPLETED', 100, 'BALANCED', 'HD_720P', 'H264', 2500,
                    'content://media/external/video/media/10', 'legacy_compressed.mp4', 2000000, 1,
                    'c2.hw', NULL, 1700000000000, 1700000002000, 1700000010000
                  )
                  """.trimIndent()
                )
              }

              override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }
          )
          .build()
      )
    v1Helper.writableDatabase.close()

    val migratedDb =
      Room.databaseBuilder(context, CompressXDatabase::class.java, dbName)
        .addMigrations(
          CompressXDatabase.MIGRATION_1_2,
          CompressXDatabase.MIGRATION_2_3,
          CompressXDatabase.MIGRATION_3_4,
        )
        .allowMainThreadQueries()
        .build()

    try {
      val row = kotlinx.coroutines.runBlocking { migratedDb.videoJobDao().getJobById("v1_row") }
      assertNotNull(row)
      assertEquals("legacy.mp4", row?.fileName)
      assertEquals(OutputMode.KEEP_ORIGINAL, row?.outputMode)
      assertFalse(row?.replacedOriginal ?: true)
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun `batch runner fails gracefully in Replace Original Mode when source file is unreadable`() = runTest {
    val prefs = PreferencesRepository(context)
    prefs.updateOutputMode(OutputMode.REPLACE_ORIGINAL, consentGranted = true)

    val engine = VideoCompressionEngine(context, storageWriter)
    val badJob =
      VideoJobEntity(
        id = "replace_bad_source",
        sourceUri = "file:///does/not/exist/video.mp4",
        fileName = "video.mp4",
        durationMs = 4_000L,
        originalSizeBytes = 1_000_000L,
        width = 1280,
        height = 720,
        rotation = 0,
        sourceBitrateBps = 2_000_000,
        sortOrder = 0,
        status = JobStatus.QUEUED,
        preset = CompressionPreset.BALANCED,
        targetResolution = TargetResolution.HD_720P,
        targetCodec = VideoCodec.H264,
        customBitrateKbps = 2000,
        outputMode = OutputMode.REPLACE_ORIGINAL,
      )
    db.videoJobDao().insertJobs(listOf(badJob))

    val runner =
      BatchQueueRunner(
        repo = repo,
        prefs = prefs,
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

    val updated = repo.getJobById("replace_bad_source")
    assertEquals(JobStatus.FAILED, updated?.status)
    assertFalse(updated?.errorMessage.isNullOrBlank())
    assertEquals(0, summary.awaitingApprovalCount)
    assertEquals(1, summary.failedCount)
  }
}
