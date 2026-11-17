package com.example

import android.app.Application
import com.example.data.CompressXDatabase
import com.example.data.PreferencesRepository
import com.example.data.VideoQueueRepository
import com.example.engine.CodecCapabilityInspector
import com.example.engine.DeviceGuardrailMonitor
import com.example.engine.OutputStorageWriter
import com.example.engine.VideoCompressionEngine
import com.example.model.CodecCapabilities
import com.example.service.CompressionForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CompressXApplication : Application() {

  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

  override fun onCreate() {
    super.onCreate()
    appScope.launch {
      compressionEngine.cleanupOrphanedTempFiles()
      queueRepository.resetInterruptedProcessingJobs()
      val config = preferencesRepository.configFlow.first()
      val pending = queueRepository.countPendingOrActive()
      if (config.autoResumeAfterRestart && pending > 0) {
        runCatching { CompressionForegroundService.startQueue(this@CompressXApplication) }
      }
    }
  }
}
