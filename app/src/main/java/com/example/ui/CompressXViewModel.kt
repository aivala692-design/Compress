package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.CompressXApplication
import com.example.data.VideoJobEntity
import com.example.engine.CompressionLogger
import com.example.model.BatchSummaryStats
import com.example.model.CodecCapabilities
import com.example.model.CompressionPreset
import com.example.model.GuardrailState
import com.example.model.JobStatus
import com.example.model.TargetResolution
import com.example.model.VideoCodec
import com.example.model.VideoCompressionConfig
import com.example.service.CompressionForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class QueueFilter(val label: String) {
  ALL("All"),
  QUEUED("Queued"),
  PROCESSING("Active"),
  COMPLETED("Done"),
  FAILED("Failed"),
}

data class StudioUiState(
  val jobs: List<VideoJobEntity> = emptyList(),
  val history: List<VideoJobEntity> = emptyList(),
  val stats: BatchSummaryStats = BatchSummaryStats(),
  val config: VideoCompressionConfig = VideoCompressionConfig(),
  val capabilities: CodecCapabilities? = null,
  val guardrails: GuardrailState = GuardrailState(),
  val selectedFilter: QueueFilter = QueueFilter.ALL,
  val isImportingVideos: Boolean = false,
  val statusBannerMessage: String? = null,
) {
  val isBatchRunning: Boolean
    get() = jobs.any { it.status == JobStatus.PROCESSING }

  val hasPendingOrPaused: Boolean
    get() = jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.PAUSED }
}

class CompressXViewModel(application: Application) : AndroidViewModel(application) {

  private val app = application as CompressXApplication
  private val queueRepo = app.queueRepository
  private val prefsRepo = app.preferencesRepository
  private val guardrailsMonitor = app.guardrailMonitor

  private val selectedFilter = MutableStateFlow(QueueFilter.ALL)
  private val isImporting = MutableStateFlow(false)
  private val bannerMessage = MutableStateFlow<String?>(null)
  private val guardrailsFlow = MutableStateFlow(guardrailsMonitor.sample())

  val uiState: StateFlow<StudioUiState> =
    combine(
      queueRepo.allJobs,
      queueRepo.completedHistory,
      prefsRepo.configFlow,
      selectedFilter,
      isImporting,
      bannerMessage,
      guardrailsFlow,
    ) { values ->
      @Suppress("UNCHECKED_CAST")
      val jobs = values[0] as List<VideoJobEntity>
      @Suppress("UNCHECKED_CAST")
      val history = values[1] as List<VideoJobEntity>
      val config = values[2] as VideoCompressionConfig
      val filter = values[3] as QueueFilter
      val importing = values[4] as Boolean
      val banner = values[5] as String?
      val guardrails = values[6] as GuardrailState

      StudioUiState(
        jobs = jobs,
        history = history,
        stats = queueRepo.computeStats(jobs),
        config = config,
        capabilities = app.codecCapabilities,
        guardrails = guardrails,
        selectedFilter = filter,
        isImportingVideos = importing,
        statusBannerMessage = banner,
      )
    }
      .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StudioUiState(capabilities = app.codecCapabilities),
      )

  init {
    viewModelScope.launch {
      while (isActive) {
        guardrailsFlow.value = guardrailsMonitor.sample()
        delay(5000L)
      }
    }

    // Ensure pending jobs recover smoothly when user opens app after process death
    viewModelScope.launch {
      runCatching {
        val config = prefsRepo.configFlow.first()
        val pending = queueRepo.countPendingOrActive()
        if (config.autoResumeAfterRestart && pending > 0) {
          startQueueOrFallback(isAutoResume = true)
        }
      }.onFailure {
        CompressionLogger.w("CompressXVM", "Auto-resume check failed safely", it)
      }
    }
  }

  private fun startQueueOrFallback(isAutoResume: Boolean = false) {
    val startedFgs = CompressionForegroundService.startQueue(app)
    if (!startedFgs) {
      CompressionLogger.w(
        "CompressXVM",
        "Foreground service start unavailable (autoResume=$isAutoResume); running in-app fallback",
      )
      app.startInProcessFallbackQueue()
      if (!isAutoResume) {
        bannerMessage.value =
          "Compressing while app stays open (background service restricted by system)."
      }
    }
  }

  fun onVideosSelected(uris: List<Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      isImporting.value = true
      try {
        val currentConfig = prefsRepo.configFlow.first()
        val addedCount = queueRepo.enqueueVideos(uris, currentConfig)
        bannerMessage.value = "Added $addedCount video${if (addedCount == 1) "" else "s"} to batch queue"
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXVM", "Failed to import selected videos", t)
        bannerMessage.value = "Failed to import selected videos: ${CompressionLogger.formatUserErrorMessage(t)}"
      } finally {
        isImporting.value = false
      }
    }
  }

  fun onOutputFolderSelected(treeUri: Uri?) {
    if (treeUri == null) return
    viewModelScope.launch {
      runCatching {
        app.contentResolver.takePersistableUriPermission(
          treeUri,
          android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
      }.onFailure {
        CompressionLogger.w("CompressXVM", "Persistable SAF folder permission warning", it)
      }
      val folderName =
        runCatching { DocumentFile.fromTreeUri(app, treeUri)?.name }.getOrNull()
          ?: "Selected SAF Folder"
      prefsRepo.updateOutputFolder(treeUri.toString(), folderName)
      bannerMessage.value = "Output folder set to $folderName"
    }
  }

  fun resetOutputFolderToDefault() {
    viewModelScope.launch {
      prefsRepo.updateOutputFolder(null, "Movies/CompressX Studio")
      bannerMessage.value = "Output folder reset to Movies/CompressX Studio"
    }
  }

  fun startOrResumeBatch() {
    viewModelScope.launch {
      val sample = guardrailsMonitor.sample()
      guardrailsFlow.value = sample
      if (sample.isLowStorage) {
        bannerMessage.value = "Low storage warning: free up space before starting batch compression."
        return@launch
      }
      queueRepo.resumeAllPausedJobs()
      startQueueOrFallback(isAutoResume = false)
    }
  }

  fun pauseBatch() {
    CompressionForegroundService.pauseQueue(app)
  }

  fun cancelActiveJob() {
    CompressionForegroundService.cancelActiveJob(app)
  }

  fun cancelJob(jobId: String) {
    viewModelScope.launch {
      val job = queueRepo.getJobById(jobId)
      if (job?.status == JobStatus.PROCESSING) {
        CompressionForegroundService.cancelActiveJob(app)
      } else {
        queueRepo.cancelJob(jobId)
      }
    }
  }

  fun retryJob(jobId: String) {
    viewModelScope.launch {
      queueRepo.retryJob(jobId)
      startQueueOrFallback(isAutoResume = false)
    }
  }

  fun retryAllFailed() {
    viewModelScope.launch {
      queueRepo.retryAllFailedJobs()
      startQueueOrFallback(isAutoResume = false)
    }
  }

  fun deleteJob(jobId: String) {
    viewModelScope.launch { queueRepo.deleteJob(jobId) }
  }

  fun clearCompleted() {
    viewModelScope.launch { queueRepo.clearCompletedAndCancelled() }
  }

  fun clearAllQueue() {
    viewModelScope.launch {
      CompressionForegroundService.pauseQueue(app)
      delay(150L)
      queueRepo.clearAll()
    }
  }

  fun moveJob(fromIndex: Int, toIndex: Int, visibleJobs: List<VideoJobEntity>) {
    if (fromIndex == toIndex) return
    if (fromIndex !in visibleJobs.indices || toIndex !in visibleJobs.indices) return
    val mutable = visibleJobs.toMutableList()
    val moved = mutable.removeAt(fromIndex)
    mutable.add(toIndex, moved)
    viewModelScope.launch {
      val allOrdered = uiState.value.jobs.toMutableList()
      // Replace visible subset positions while preserving overall queue stability
      val visibleIds = visibleJobs.map { it.id }.toSet()
      val slots = allOrdered.mapIndexedNotNull { idx, item -> if (item.id in visibleIds) idx else null }
      slots.forEachIndexed { slotIdx, globalIdx ->
        if (slotIdx < mutable.size) {
          allOrdered[globalIdx] = mutable[slotIdx]
        }
      }
      queueRepo.reorderJobs(allOrdered.map { it.id })
    }
  }

  fun selectFilter(filter: QueueFilter) {
    selectedFilter.value = filter
  }

  fun selectPreset(preset: CompressionPreset) {
    viewModelScope.launch { prefsRepo.updatePreset(preset) }
  }

  fun selectResolution(resolution: TargetResolution) {
    viewModelScope.launch {
      prefsRepo.updateResolution(resolution)
      if (uiState.value.config.preset != CompressionPreset.CUSTOM) {
        prefsRepo.updatePreset(CompressionPreset.CUSTOM)
      }
    }
  }

  fun selectCodec(codec: VideoCodec) {
    viewModelScope.launch { prefsRepo.updateCodec(codec) }
  }

  fun updateCustomBitrateKbps(kbps: Int) {
    viewModelScope.launch {
      prefsRepo.updateCustomBitrateKbps(kbps)
      if (uiState.value.config.preset != CompressionPreset.CUSTOM) {
        prefsRepo.updatePreset(CompressionPreset.CUSTOM)
      }
    }
  }

  fun setThermalProtection(enabled: Boolean) {
    viewModelScope.launch { prefsRepo.updateThermalProtection(enabled) }
  }

  fun setPauseOnLowBattery(enabled: Boolean) {
    viewModelScope.launch { prefsRepo.updatePauseOnLowBattery(enabled) }
  }

  fun setAutoResumeAfterRestart(enabled: Boolean) {
    viewModelScope.launch { prefsRepo.updateAutoResumeAfterRestart(enabled) }
  }

  fun setPreferHardware(enabled: Boolean) {
    viewModelScope.launch { prefsRepo.updatePreferHardware(enabled) }
  }

  fun reportMessage(message: String) {
    bannerMessage.value = message
  }

  fun dismissBanner() {
    bannerMessage.value = null
  }
}
