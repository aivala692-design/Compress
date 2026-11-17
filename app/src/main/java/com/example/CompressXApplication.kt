package com.example

import android.app.Application
import com.example.data.CompressXDatabase
import com.example.data.PreferencesRepository
import com.example.data.VideoQueueRepository
import com.example.engine.CodecCapabilityInspector
import com.example.engine.CompressionLogger
import com.example.engine.DeviceGuardrailMonitor
import com.example.engine.OutputStorageWriter
import com.example.engine.VideoCompressionEngine
import com.example.model.CodecCapabilities
import com.example.service.BatchQueueRunner
import com.example.service.NotificationHelper
import com.example.ui.components.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CompressXApplication : Application() {

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var fallbackBatchJob: Job? = null

  val database: CompressXDatabase by lazy { CompressXDatabase.getInstance(this) }
  val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(this) }
  val queueRepository: VideoQueueRepository by lazy {
    VideoQueueRepository(this, database.videoJobDao())
  }
  val outputStorageWriter: OutputStorageWriter by lazy { OutputStorageWriter(this) }
  val compressionEngine: VideoCompressionEngine by lazy {
    VideoCompressionEngine(this, outputStorageWriter)
  }
  val guardrailMonitor: DeviceGuardrailMonitor by lazy { DeviceGuardrailMonitor(this) }
  val codecCapabilities: CodecCapabilities by lazy { CodecCapabilityInspector.inspect() }
  val batchQueueRunner: BatchQueueRunner by lazy {
    BatchQueueRunner(
      repo = queueRepository,
      prefs = preferencesRepository,
      engine = compressionEngine,
      guardrails = guardrailMonitor,
    )
  }

  override fun onCreate() {
    super.onCreate()
    CompressionLogger.init(this)
    NotificationHelper.ensureChannels(this)
    appScope.launch {
      runCatching {
        compressionEngine.cleanupOrphanedTempFiles()
        queueRepository.resetInterruptedProcessingJobs()
      }.onFailure {
        CompressionLogger.e("CompressXApp", "Failed initial queue cleanup on launch", it)
      }
    }
  }

  fun startInProcessFallbackQueue() {
    if (fallbackBatchJob?.isActive == true) return
    batchQueueRunner.resetPauseRequest()
    fallbackBatchJob =
      appScope.launch {
        CompressionLogger.w(
          "CompressXApp",
          "Running queue via in-process fallback runner because foreground service was blocked",
        )
        runCatching {
          val summary =
            batchQueueRunner.runBatch(
              progressScope = appScope,
              onJobStarted = { _, _, _ -> },
              onProgressChanged = { _, _, _, _ -> },
              onStatusNote = { _, _, _, _ -> },
            )
          if (!summary.wasPaused && (summary.completedCount > 0 || summary.failedCount > 0)) {
            NotificationHelper.postCompletionNotification(
              context = this@CompressXApplication,
              completedCount = summary.completedCount,
              failedCount = summary.failedCount,
              savedFormatted = FormatUtils.formatBytes(summary.savedBytes),
            )
          }
        }.onFailure {
          CompressionLogger.e("CompressXApp", "In-process fallback batch runner failed", it)
        }
      }
  }

  fun cancelInProcessFallback() {
    fallbackBatchJob?.cancel()
    fallbackBatchJob = null
  }

  fun cancelJobDirect(jobId: String) {
    appScope.launch {
      runCatching { queueRepository.cancelJob(jobId) }
        .onFailure { CompressionLogger.e("CompressXApp", "Direct cancel failed for $jobId", it) }
      compressionEngine.cancelActive()
    }
  }
}
