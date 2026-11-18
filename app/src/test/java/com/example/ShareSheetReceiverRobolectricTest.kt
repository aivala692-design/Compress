package com.example

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.CompressXDatabase
import com.example.data.VideoQueueRepository
import com.example.engine.CompressionLogger
import com.example.engine.ShareIntentParser
import com.example.model.CompressionPreset
import com.example.model.JobStatus
import com.example.model.TargetResolution
import com.example.model.VideoCompressionConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class ShareSheetReceiverRobolectricTest {

  private lateinit var context: Context
  private lateinit var db: CompressXDatabase
  private lateinit var repo: VideoQueueRepository

  @Before
  fun setup() {
    context = ApplicationProvider.getApplicationContext()
    CompressionLogger.init(context)
    db =
      Room.inMemoryDatabaseBuilder(context, CompressXDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    repo = VideoQueueRepository(context, db.videoJobDao())
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `manifest declares exported MainActivity for both SEND and SEND_MULTIPLE video intents`() {
    val pm = context.packageManager
    val singleSend =
      Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
      }
    val multiSend =
      Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "video/*"
      }

    // Also verify merged manifest contains MainActivity with SEND & SEND_MULTIPLE filters
    val singleMatches = pm.queryIntentActivities(singleSend, 0)
    val multiMatches = pm.queryIntentActivities(multiSend, 0)
    assertTrue(
      "MainActivity must resolve for ACTION_SEND video/mp4",
      singleMatches.any { it.activityInfo.name == MainActivity::class.java.name && it.activityInfo.exported },
    )
    assertTrue(
      "MainActivity must resolve for ACTION_SEND_MULTIPLE video/*",
      multiMatches.any { it.activityInfo.name == MainActivity::class.java.name && it.activityInfo.exported },
    )
  }

  @Test
  fun `parser handles single EXTRA_STREAM, EXTRA_TEXT caption, ClipData, and URI flags`() {
    val videoUri = Uri.parse("content://com.google.android.apps.photos.contentprovider/0/1/mediakey/clip.mp4")
    val intent =
      Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, videoUri)
        putExtra(Intent.EXTRA_TEXT, "Vacation highlight reel")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }

    val payload = ShareIntentParser.parse(context, intent)
    assertEquals(Intent.ACTION_SEND, payload.action)
    assertEquals(listOf(videoUri), payload.uris)
    assertEquals("Vacation highlight reel", payload.captionText)
    assertTrue(payload.hasReadPermissionFlag)

    // ClipData-only delivery (when provider omits EXTRA_STREAM)
    val clipOnlyUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMovies%2Fdemo.mp4")
    val clipOnlyIntent =
      Intent(Intent.ACTION_SEND).apply {
        type = "video/*"
        clipData = ClipData.newRawUri("video", clipOnlyUri)
      }
    val clipPayload = ShareIntentParser.parse(context, clipOnlyIntent)
    assertEquals(listOf(clipOnlyUri), clipPayload.uris)
  }

  @Test
  fun `parser handles SEND_MULTIPLE for 55 videos and deduplicates overlapping ClipData URIs`() {
    val uris =
      (1..55).map { idx ->
        Uri.parse("content://media/external/video/media/$idx")
      }
    val intent =
      Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "video/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        clipData =
          ClipData.newRawUri("videos", uris.first()).apply {
            addItem(ClipData.Item(uris.last()))
          }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }

    val payload = ShareIntentParser.parse(context, intent)
    assertEquals(55, payload.uris.size)
    assertEquals(uris.first(), payload.uris.first())
    assertEquals(uris.last(), payload.uris.last())
  }

  @Test
  fun `repository prevents duplicate URIs when received twice and applies updated preset before user confirms`() =
    runTest {
      val uriA = Uri.parse("${ContentResolver.SCHEME_CONTENT}://com.whatsapp.provider.media/item/101.mp4")
      val uriB = Uri.parse("${ContentResolver.SCHEME_CONTENT}://com.whatsapp.provider.media/item/102.mp4")

      val initialConfig = VideoCompressionConfig(preset = CompressionPreset.BALANCED)
      val firstBatch = repo.enqueueVideosDetailed(listOf(uriA, uriB), initialConfig, deduplicateActive = true)
      assertEquals(2, firstBatch.addedJobs.size)
      assertEquals(0, firstBatch.skippedDuplicateCount)

      // Mark queued explicitly so deduplication test covers pending queue regardless of fake test URI decoder
      firstBatch.addedJobs.forEach { job ->
        repo.updateStatusAndProgress(job.id, JobStatus.QUEUED, 0)
      }

      // Receiving uriA again + a new uriC must skip uriA and only add uriC
      val uriC = Uri.parse("${ContentResolver.SCHEME_CONTENT}://com.whatsapp.provider.media/item/103.mp4")
      val secondBatch = repo.enqueueVideosDetailed(listOf(uriA, uriC), initialConfig, deduplicateActive = true)
      assertEquals(1, secondBatch.addedJobs.size)
      assertEquals(1, secondBatch.skippedDuplicateCount)
      assertEquals(uriC.toString(), secondBatch.addedJobs.single().sourceUri)

      // Ensure user quality/bitrate adjustment updates shared queued jobs before confirmation
      secondBatch.addedJobs.forEach { repo.updateStatusAndProgress(it.id, JobStatus.QUEUED, 0) }
      val customConfig =
        initialConfig.copy(
          preset = CompressionPreset.SMALL_SIZE,
          resolution = TargetResolution.HD_720P,
          customBitrateKbps = 1800,
        )
      repo.applyConfigToQueuedJobs(secondBatch.addedJobs.map { it.id }, customConfig)

      val updatedC = repo.getJobById(secondBatch.addedJobs.single().id)
      assertEquals(CompressionPreset.SMALL_SIZE, updatedC?.preset)
      assertEquals(TargetResolution.HD_720P, updatedC?.targetResolution)
      assertEquals(1800, updatedC?.customBitrateKbps)
    }

  @Test
  fun `MainActivity ingests ACTION_SEND and onNewIntent without auto-starting compression`() {
    val sharedUri = Uri.parse("content://media/external/video/media/777")
    val sendIntent =
      Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_SEND
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, sharedUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }

    val controller = Robolectric.buildActivity(MainActivity::class.java, sendIntent).setup()
    val activity = controller.get()

    val nextUri = Uri.parse("content://media/external/video/media/888")
    val warmShare =
      Intent(context, MainActivity::class.java).apply {
        action = Intent.ACTION_SEND_MULTIPLE
        type = "video/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(sharedUri, nextUri))
      }
    controller.newIntent(warmShare)
    Shadows.shadowOf(activity.mainLooper).idle()

    // Ensure no CompressionForegroundService auto-started without explicit user confirmation
    val nextStartedService = Shadows.shadowOf(activity.application).peekNextStartedService()
    assertTrue(
      "Compression service must not auto-start before user confirms shared batch",
      nextStartedService == null ||
        nextStartedService.component?.className != com.example.service.CompressionForegroundService::class.java.name,
    )
    assertFalse(activity.isFinishing)
    controller.pause().stop().destroy()
  }
}
