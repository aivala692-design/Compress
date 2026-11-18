package com.example

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.CompressXDatabase
import com.example.data.VideoJobEntity
import com.example.data.VideoQueueRepository
import com.example.engine.CompressionLogger
import com.example.engine.CompressionPlanCalculator
import com.example.engine.MediaReplacementManager
import com.example.engine.OutputStorageWriter
import com.example.engine.ResolvedReplaceTarget
import com.example.model.CompressionPreset
import com.example.model.JobStatus
import com.example.model.OutputMode
import com.example.model.ReplacementPermissionStatus
import com.example.model.TargetResolution
import com.example.model.VideoCodec
import com.example.model.VideoCompressionConfig
import com.example.ui.components.FormatUtils
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class BatchFeaturesRobolectricTest {

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
  fun `read-only providers including Files by Google are detected and disabled from batch replacement`() {
    val nbuUri = Uri.parse("content://com.google.android.apps.nbu.files.provider/1/video/sample.mp4")
    val waUri = Uri.parse("content://com.whatsapp.provider.media/item/99")
    val mediaUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "501")

    assertTrue(replacementManager.isKnownReadOnlyAuthority("com.google.android.apps.nbu.files.provider"))
    assertTrue(replacementManager.isKnownReadOnlyAuthority("com.whatsapp.provider.media"))
    assertFalse(replacementManager.isKnownReadOnlyAuthority(MediaStore.AUTHORITY))

    val nbuAssessment = replacementManager.assessEligibility(nbuUri)
    assertEquals(ReplacementPermissionStatus.READ_ONLY, nbuAssessment.status)
    assertTrue(nbuAssessment.resolvedTarget is ResolvedReplaceTarget.UnsupportedTarget)
    assertTrue(nbuAssessment.readOnlyReason?.contains("Save Compressed Copy", ignoreCase = true) == true)

    val waAssessment = replacementManager.assessEligibility(waUri)
    assertEquals(ReplacementPermissionStatus.READ_ONLY, waAssessment.status)

    val mediaAssessment = replacementManager.assessEligibility(mediaUri)
    assertEquals(ReplacementPermissionStatus.ELIGIBLE, mediaAssessment.status)
    assertTrue(mediaAssessment.resolvedTarget is ResolvedReplaceTarget.MediaStoreTarget)
  }

  @Test
  fun `prepareBatchDeleteApproval isolates read-only providers and captures MediaStore metadata snapshots`() {
    val stagedPlayable =
      File(context.filesDir, "verified_staged_batch.mp4").apply {
        writeBytes(ByteArray(4096) { 1 })
      }
    ShadowMediaMetadataRetriever.addMetadata(
      stagedPlayable.absolutePath,
      MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO,
      "yes",
    )
    ShadowMediaMetadataRetriever.addMetadata(
      stagedPlayable.absolutePath,
      MediaMetadataRetriever.METADATA_KEY_DURATION,
      "12000",
    )
    ShadowMediaMetadataRetriever.addMetadata(
      stagedPlayable.absolutePath,
      MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
      "1280",
    )
    ShadowMediaMetadataRetriever.addMetadata(
      stagedPlayable.absolutePath,
      MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
      "720",
    )

    val mediaUri1 = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "701")
    val mediaUri2 = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "702")
    val readOnlyUri = Uri.parse("content://com.google.android.apps.nbu.files.provider/item/888")

    val job1 =
      createJob(
        id = "ms_1",
        uri = mediaUri1.toString(),
        fileName = "vacation_clip.mp4",
        status = JobStatus.AWAITING_APPROVAL,
        stagedTempPath = stagedPlayable.absolutePath,
        relativePath = "Movies/Camera/",
      )
    val job2 =
      createJob(
        id = "ms_2",
        uri = mediaUri2.toString(),
        fileName = "concert_clip.mp4",
        status = JobStatus.AWAITING_APPROVAL,
        stagedTempPath = stagedPlayable.absolutePath,
        relativePath = "Movies/Camera/",
      )
    val readOnlyJob =
      createJob(
        id = "ro_1",
        uri = readOnlyUri.toString(),
        fileName = "shared_readonly.mp4",
        status = JobStatus.AWAITING_APPROVAL,
        stagedTempPath = stagedPlayable.absolutePath,
        replacementStatus = ReplacementPermissionStatus.READ_ONLY,
      )

    val prep = replacementManager.prepareBatchDeleteApproval(listOf(job1, job2, readOnlyJob))

    // Read-only item is classified immediately and excluded from MediaStore deletion candidates
    val roPair = prep.readOnlyOrFailedJobs.firstOrNull { it.first.id == "ro_1" }
    assertNotNull(roPair)
    assertTrue(roPair!!.second.contains("Save Compressed Copy", ignoreCase = true))

    // Snapshots preserve original filenames & folders before delete dialog runs
    assertEquals("vacation_clip.mp4", prep.snapshots["ms_1"]?.displayName)
    assertEquals("Movies/Camera/", prep.snapshots["ms_1"]?.relativePath)
    assertEquals("concert_clip.mp4", prep.snapshots["ms_2"]?.displayName)

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      assertEquals(1, prep.readOnlyOrFailedJobs.size)
      assertEquals(2, prep.legacyMediaStoreJobs.size)
    }

    stagedPlayable.delete()
  }

  @Test
  fun `smart size estimator computes realistic range and responds to quality and codec changes`() {
    val origBytes = 100L * 1024L * 1024L // 100 MB
    val durationMs = 60_000L // 60s -> ~13.3 Mbps source

    val hqEst =
      CompressionPlanCalculator.estimateJobCompression(
        durationMs = durationMs,
        originalSizeBytes = origBytes,
        sourceWidth = 1920,
        sourceHeight = 1080,
        sourceRotation = 0,
        sourceBitrateBps = 13_300_000,
        sourceFrameRate = 30f,
        sourceAudioBitrateBps = 128_000,
        preset = CompressionPreset.HIGH_QUALITY,
        targetResolution = TargetResolution.ORIGINAL,
        targetCodec = VideoCodec.H264,
        customBitrateKbps = 6000,
        hasHardwareEncoder = true,
        preferHardware = true,
      )

    val smallHevcEst =
      CompressionPlanCalculator.estimateJobCompression(
        durationMs = durationMs,
        originalSizeBytes = origBytes,
        sourceWidth = 1920,
        sourceHeight = 1080,
        sourceRotation = 0,
        sourceBitrateBps = 13_300_000,
        sourceFrameRate = 30f,
        sourceAudioBitrateBps = 128_000,
        preset = CompressionPreset.SMALL_SIZE,
        targetResolution = TargetResolution.HD_720P,
        targetCodec = VideoCodec.H265,
        customBitrateKbps = 1500,
        hasHardwareEncoder = true,
        preferHardware = true,
      )

    assertTrue(hqEst.minOutputBytes < hqEst.maxOutputBytes)
    assertTrue(smallHevcEst.maxOutputBytes < hqEst.minOutputBytes)
    assertTrue(smallHevcEst.minReductionPercent >= 70)
    assertTrue(smallHevcEst.estimatedProcessingSeconds >= 2)
    // HEVC at 60fps should estimate more processing time than H264 at 30fps
    val fps60Est =
      CompressionPlanCalculator.estimateJobCompression(
        durationMs = durationMs,
        originalSizeBytes = origBytes,
        sourceWidth = 1920,
        sourceHeight = 1080,
        sourceRotation = 0,
        sourceBitrateBps = 13_300_000,
        sourceFrameRate = 60f,
        sourceAudioBitrateBps = 128_000,
        preset = CompressionPreset.SMALL_SIZE,
        targetResolution = TargetResolution.HD_720P,
        targetCodec = VideoCodec.H265,
        customBitrateKbps = 1500,
        hasHardwareEncoder = true,
        preferHardware = true,
      )
    assertTrue(fps60Est.estimatedProcessingSeconds > hqEst.estimatedProcessingSeconds)

    val formattedRange =
      FormatUtils.formatSizeRange(30L * 1024L * 1024L, 40L * 1024L * 1024L)
    assertEquals("30–40 MB", formattedRange)
  }

  @Test
  fun `75-video batch queue aggregates totals, eligibility counts, and recalculates on preset switch`() = runTest {
    val jobs =
      (1..75).map { idx ->
        val isReadOnly = idx % 5 == 0 // 15 read-only, 60 replace eligible
        createJob(
          id = "batch_$idx",
          uri =
            if (isReadOnly) "content://com.google.android.apps.nbu.files.provider/video/$idx"
            else "content://media/external/video/media/$idx",
          fileName = "clip_$idx.mp4",
          originalSizeBytes = 20L * 1024L * 1024L,
          durationMs = 20_000L,
          replacementStatus =
            if (isReadOnly) ReplacementPermissionStatus.READ_ONLY
            else ReplacementPermissionStatus.ELIGIBLE,
        )
      }
    db.videoJobDao().insertJobs(jobs)

    val initialJobs = repo.allJobs.first()
    val balancedStats = repo.computeStats(initialJobs, hasHardwareEncoder = true, preferHardware = true)
    assertEquals(75, balancedStats.queuedCount)
    assertEquals(60, balancedStats.replaceEligibleCount)
    assertEquals(15, balancedStats.readOnlyCount)
    assertEquals(75L * 20L * 1024L * 1024L, balancedStats.totalOriginalBytesPending)
    assertTrue(balancedStats.estMinOutputBytesPending in 1L until balancedStats.estMaxOutputBytesPending)
    assertTrue(balancedStats.estProcessingSecondsPending > 0)

    // Switch quality preset to SMALL_SIZE -> verify immediate queue config update & smaller estimates
    repo.applyConfigToAllQueuedJobs(
      VideoCompressionConfig(
        preset = CompressionPreset.SMALL_SIZE,
        resolution = TargetResolution.HD_720P,
        codec = VideoCodec.H265,
      )
    )
    val smallJobs = repo.allJobs.first()
    val smallStats = repo.computeStats(smallJobs, hasHardwareEncoder = true, preferHardware = true)
    assertTrue(smallStats.estMaxOutputBytesPending < balancedStats.estMinOutputBytesPending)
    assertTrue(smallStats.estMinReductionPercentPending > balancedStats.estMinReductionPercentPending)
  }

  @Test
  fun `room migration 2_3 preserves existing jobs with default metadata columns`() {
    val dbName = "migration_test_v2_v3.db"
    context.deleteDatabase(dbName)

    val helperFactory = FrameworkSQLiteOpenHelperFactory()
    val v2Helper =
      helperFactory.create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
          .name(dbName)
          .callback(
            object : SupportSQLiteOpenHelper.Callback(2) {
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
                    `outputMode` TEXT NOT NULL DEFAULT 'KEEP_ORIGINAL',
                    `replacedOriginal` INTEGER NOT NULL DEFAULT 0,
                    `stagedTempPath` TEXT,
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
                    targetCodec, customBitrateKbps, outputMode, replacedOriginal, stagedTempPath,
                    outputUri, outputFileName, outputSizeBytes, usedHardwareEncoder, encoderName,
                    errorMessage, createdAt, startedAt, completedAt
                  ) VALUES (
                    'v2_row', 'content://media/external/video/media/19', 'trip.mp4', 15000, 12000000,
                    1920, 1080, 0, 8000000, 0, 'QUEUED', 0, 'BALANCED', 'FHD_1080P', 'H264', 3500,
                    'REPLACE_ORIGINAL', 0, NULL, NULL, NULL, 0, NULL, NULL, NULL, 1700000000000, NULL, NULL
                  )
                  """.trimIndent()
                )
              }

              override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }
          )
          .build()
      )
    v2Helper.writableDatabase.close()

    val migratedDb =
      Room.databaseBuilder(context, CompressXDatabase::class.java, dbName)
        .addMigrations(CompressXDatabase.MIGRATION_2_3)
        .allowMainThreadQueries()
        .build()

    try {
      val row = runBlocking { migratedDb.videoJobDao().getJobById("v2_row") }
      assertNotNull(row)
      assertEquals("trip.mp4", row?.fileName)
      assertEquals(ReplacementPermissionStatus.NOT_APPLICABLE, row?.replacementStatus)
      assertEquals(0f, row?.sourceFrameRate ?: -1f, 0.01f)
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  private fun createJob(
    id: String,
    uri: String,
    fileName: String,
    status: JobStatus = JobStatus.QUEUED,
    originalSizeBytes: Long = 20L * 1024L * 1024L,
    durationMs: Long = 20_000L,
    stagedTempPath: String? = null,
    relativePath: String? = null,
    replacementStatus: ReplacementPermissionStatus = ReplacementPermissionStatus.ELIGIBLE,
  ) =
    VideoJobEntity(
      id = id,
      sourceUri = uri,
      fileName = fileName,
      durationMs = durationMs,
      originalSizeBytes = originalSizeBytes,
      width = 1920,
      height = 1080,
      rotation = 0,
      sourceBitrateBps = 8_000_000,
      sourceFrameRate = 30f,
      sourceAudioBitrateBps = 128_000,
      sortOrder = 0,
      status = status,
      preset = CompressionPreset.BALANCED,
      targetResolution = TargetResolution.FHD_1080P,
      targetCodec = VideoCodec.H264,
      customBitrateKbps = 3500,
      outputMode = OutputMode.REPLACE_ORIGINAL,
      replacementStatus = replacementStatus,
      sourceRelativePath = relativePath,
      stagedTempPath = stagedTempPath,
      outputSizeBytes = if (status == JobStatus.AWAITING_APPROVAL) 8_000_000L else 0L,
    )
}
