package com.example.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.CompressXApplication
import com.example.data.VideoJobEntity
import com.example.engine.CompressionLogger
import com.example.engine.ReplacementOutcome
import com.example.model.BatchSummaryStats
import com.example.model.CodecCapabilities
import com.example.model.CompressionPreset
import com.example.model.GuardrailState
import com.example.model.JobStatus
import com.example.model.OutputMode
import com.example.model.TargetResolution
import com.example.model.VideoCodec
import com.example.model.VideoCompressionConfig
import com.example.service.CompressionForegroundService
import com.example.ui.components.FormatUtils
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.example.engine.ParsedSharePayload

enum class QueueFilter(val label: String) {
  ALL("All"),
  QUEUED("Queued"),
  PROCESSING("Active"),
  AWAITING("Approve"),
  COMPLETED("Done"),
  FAILED("Failed"),
}

data class PendingSystemApprovalRequest(
  val intentSender: IntentSender,
  val approvedJobIds: Set<String>,
)

data class IncomingShareReviewState(
  val jobIds: List<String>,
  val captionText: String? = null,
  val skippedDuplicateCount: Int = 0,
)

data class StudioUiState(
  val jobs: List<VideoJobEntity> = emptyList(),
  val history: List<VideoJobEntity> = emptyList(),
  val stats: BatchSummaryStats = BatchSummaryStats(),
  val config: VideoCompressionConfig = VideoCompressionConfig(),
  val capabilities: CodecCapabilities? = null,
  val guardrails: GuardrailState = GuardrailState(),
  val selectedFilter: QueueFilter = QueueFilter.ALL,
  val isImportingVideos: Boolean = false,
  val isApplyingReplacements: Boolean = false,
  val showReplaceConsentDialog: Boolean = false,
  val showBatchReplaceDialog: Boolean = false,
  val shareReviewState: IncomingShareReviewState? = null,
  val pendingSystemApproval: PendingSystemApprovalRequest? = null,
  val statusBannerMessage: String? = null,
) {
  val isBatchRunning: Boolean
    get() = jobs.any { it.status == JobStatus.PROCESSING }

  val hasPendingOrPaused: Boolean
    get() = jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.PAUSED }

  val awaitingApprovalJobs: List<VideoJobEntity>
    get() = jobs.filter { it.status == JobStatus.AWAITING_APPROVAL }

  val sharedReviewJobs: List<VideoJobEntity>
    get() {
      val ids = shareReviewState?.jobIds ?: return emptyList()
      val byId = jobs.associateBy { it.id }
      return ids.mapNotNull { byId[it] }
    }
}

class CompressXViewModel(application: Application) : AndroidViewModel(application) {

  private val app = application as CompressXApplication
  private val queueRepo = app.queueRepository
  private val prefsRepo = app.preferencesRepository
  private val guardrailsMonitor = app.guardrailMonitor
  private val replacementManager = app.mediaReplacementManager

  private val selectedFilter = MutableStateFlow(QueueFilter.ALL)
  private val isImporting = MutableStateFlow(false)
  private val isApplyingReplacements = MutableStateFlow(false)
  private val showReplaceConsentDialog = MutableStateFlow(false)
  private val showBatchReplaceDialog = MutableStateFlow(false)
  private val shareReviewState = MutableStateFlow<IncomingShareReviewState?>(null)
  private val pendingSystemApproval = MutableStateFlow<PendingSystemApprovalRequest?>(null)
  private val bannerMessage = MutableStateFlow<String?>(null)
  private val guardrailsFlow = MutableStateFlow(guardrailsMonitor.sample())

  private var prevProcessingCount = 0

  val uiState: StateFlow<StudioUiState> =
    combine(
      queueRepo.allJobs,
      queueRepo.completedHistory,
      prefsRepo.configFlow,
      selectedFilter,
      isImporting,
      isApplyingReplacements,
      showReplaceConsentDialog,
      showBatchReplaceDialog,
      shareReviewState,
      pendingSystemApproval,
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
      val replacing = values[5] as Boolean
      val showConsent = values[6] as Boolean
      val showBatchConfirm = values[7] as Boolean
      val shareReview = values[8] as IncomingShareReviewState?
      val sysApproval = values[9] as PendingSystemApprovalRequest?
      val banner = values[10] as String?
      val guardrails = values[11] as GuardrailState

      val stats = queueRepo.computeStats(jobs)
      if (prevProcessingCount > 0 && stats.processingCount == 0 && stats.awaitingApprovalCount > 0) {
        showBatchReplaceDialog.value = true
      }
      prevProcessingCount = stats.processingCount

      StudioUiState(
        jobs = jobs,
        history = history,
        stats = stats,
        config = config,
        capabilities = app.codecCapabilities,
        guardrails = guardrails,
        selectedFilter = filter,
        isImportingVideos = importing,
        isApplyingReplacements = replacing,
        showReplaceConsentDialog = showConsent,
        showBatchReplaceDialog = showBatchConfirm,
        shareReviewState = shareReview,
        pendingSystemApproval = sysApproval,
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

  fun onSelectOutputMode(mode: OutputMode) {
    viewModelScope.launch {
      if (mode == OutputMode.REPLACE_ORIGINAL) {
        val current = prefsRepo.configFlow.first()
        if (current.replaceOriginalConsentGranted) {
          prefsRepo.updateOutputMode(OutputMode.REPLACE_ORIGINAL)
          syncShareReviewJobsWithLatestConfig()
          bannerMessage.value = "Replace Original Mode active — originals replaced after verification & approval."
        } else {
          showReplaceConsentDialog.value = true
        }
      } else {
        prefsRepo.updateOutputMode(OutputMode.KEEP_ORIGINAL)
        syncShareReviewJobsWithLatestConfig()
        bannerMessage.value = "Keep Original Mode active — compressed copies saved separately."
      }
    }
  }

  fun confirmReplaceOriginalConsent() {
    viewModelScope.launch {
      prefsRepo.updateOutputMode(OutputMode.REPLACE_ORIGINAL, consentGranted = true)
      showReplaceConsentDialog.value = false
      syncShareReviewJobsWithLatestConfig()
      bannerMessage.value = "Replace Original Mode enabled. Originals are only replaced after verification & approval."
    }
  }

  fun dismissReplaceOriginalConsent() {
    showReplaceConsentDialog.value = false
  }

  fun openBatchReplaceDialog() {
    if (uiState.value.awaitingApprovalJobs.isNotEmpty()) {
      showBatchReplaceDialog.value = true
    }
  }

  fun dismissBatchReplaceDialog() {
    showBatchReplaceDialog.value = false
  }

  fun approveAndReplaceJobs(targetJobIds: Set<String>? = null) {
    if (isApplyingReplacements.value) return
    showBatchReplaceDialog.value = false

    viewModelScope.launch {
      isApplyingReplacements.value = true
      try {
        val allAwaiting = withContext(Dispatchers.IO) { queueRepo.getAwaitingApprovalJobs() }
        val candidates =
          if (targetJobIds == null) allAwaiting
          else allAwaiting.filter { it.id in targetJobIds }

        if (candidates.isEmpty()) {
          bannerMessage.value = "No verified videos awaiting replacement."
          return@launch
        }

        val prep = withContext(Dispatchers.IO) { replacementManager.prepareBatchApproval(candidates) }

        // Surface explicit errors for any read-only/unsupported URIs while keeping originals untouched
        for ((badJob, reason) in prep.unsupportedJobs) {
          queueRepo.markAwaitingApprovalError(badJob.id, reason)
        }

        if (prep.mediaStoreIntentSender != null) {
          // Official Android MediaStore batch approval dialog (Android 11-15)
          val approvedSet = prep.mediaStoreJobIds + prep.directSafJobs.map { it.id }.toSet()
          pendingSystemApproval.value =
            PendingSystemApprovalRequest(
              intentSender = prep.mediaStoreIntentSender,
              approvedJobIds = approvedSet,
            )
          return@launch
        }

        val actionable = candidates.filter { it.id !in prep.unsupportedJobs.map { p -> p.first.id }.toSet() }
        if (actionable.isEmpty()) {
          bannerMessage.value =
            prep.unsupportedJobs.firstOrNull()?.second
              ?: "Original files cannot be modified in-place; tap Save Copy on each card."
          return@launch
        }

        executeApprovedReplacements(actionable)
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXVM", "Failed preparing original replacement", t)
        bannerMessage.value = "Replacement failed (originals preserved): ${CompressionLogger.formatUserErrorMessage(t)}"
      } finally {
        if (pendingSystemApproval.value == null) {
          isApplyingReplacements.value = false
        }
      }
    }
  }

  fun onSystemReplacementApprovalResult(granted: Boolean, jobIds: Set<String>) {
    pendingSystemApproval.value = null
    if (!granted) {
      isApplyingReplacements.value = false
      bannerMessage.value = "Replacement cancelled — all original videos were preserved."
      return
    }
    viewModelScope.launch {
      isApplyingReplacements.value = true
      try {
        val awaiting = withContext(Dispatchers.IO) { queueRepo.getAwaitingApprovalJobs() }
        val approvedJobs = awaiting.filter { it.id in jobIds }
        executeApprovedReplacements(approvedJobs)
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXVM", "Error executing approved replacements", t)
        bannerMessage.value = "Replacement failed (originals preserved): ${CompressionLogger.formatUserErrorMessage(t)}"
      } finally {
        if (pendingSystemApproval.value == null) {
          isApplyingReplacements.value = false
        }
      }
    }
  }

  private suspend fun executeApprovedReplacements(jobs: List<VideoJobEntity>) {
    var replacedCount = 0
    var failedCount = 0
    var savedBytes = 0L

    for ((idx, job) in jobs.withIndex()) {
      val outcome = withContext(Dispatchers.IO) { replacementManager.executeSingleReplacement(job) }
      when (outcome) {
        is ReplacementOutcome.Replaced -> {
          queueRepo.markCompleted(
            id = job.id,
            replacedOriginal = true,
            outputUri = outcome.outputUri.toString(),
            outputFileName = outcome.outputFileName,
            outputSizeBytes = outcome.outputSizeBytes,
            usedHardwareEncoder = job.usedHardwareEncoder ?: false,
            encoderName = job.encoderName ?: "MediaCodec",
          )
          replacedCount++
          savedBytes += (job.originalSizeBytes - outcome.outputSizeBytes).coerceAtLeast(0L)
        }
        is ReplacementOutcome.NeedsSystemIntentSender -> {
          // Android 10 RecoverableSecurityException or individual write request -> prompt for remaining
          val remainingIds = jobs.drop(idx).map { it.id }.toSet()
          pendingSystemApproval.value =
            PendingSystemApprovalRequest(
              intentSender = outcome.intentSender,
              approvedJobIds = remainingIds,
            )
          return
        }
        is ReplacementOutcome.Failed -> {
          queueRepo.markAwaitingApprovalError(job.id, outcome.errorMessage)
          failedCount++
        }
      }
    }

    bannerMessage.value =
      when {
        replacedCount > 0 && failedCount == 0 ->
          "Replaced $replacedCount original video${if (replacedCount == 1) "" else "s"} • Freed ${FormatUtils.formatBytes(savedBytes)}"
        replacedCount > 0 && failedCount > 0 ->
          "Replaced $replacedCount original(s); $failedCount preserved due to storage errors"
        failedCount > 0 ->
          "Replacement failed ($failedCount preserved intact). See card details or Save Copy."
        else -> null
      }
  }

  fun saveStagedJobAsCopy(jobId: String) {
    viewModelScope.launch {
      isApplyingReplacements.value = true
      try {
        val job = withContext(Dispatchers.IO) { queueRepo.getJobById(jobId) } ?: return@launch
        val config = prefsRepo.configFlow.first()
        val saved =
          withContext(Dispatchers.IO) {
            replacementManager.saveStagedAsCopy(job, config.outputTreeUri)
          }
        queueRepo.markCompleted(
          id = job.id,
          replacedOriginal = false,
          outputUri = saved.outputUri.toString(),
          outputFileName = saved.outputFileName,
          outputSizeBytes = saved.outputSizeBytes,
          usedHardwareEncoder = job.usedHardwareEncoder ?: false,
          encoderName = job.encoderName ?: "MediaCodec",
        )
        bannerMessage.value = "Saved copy as ${saved.outputFileName} (original preserved)"
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXVM", "Failed saving staged copy for $jobId", t)
        queueRepo.markAwaitingApprovalError(jobId, CompressionLogger.formatUserErrorMessage(t))
        bannerMessage.value = "Failed saving copy: ${CompressionLogger.formatUserErrorMessage(t)}"
      } finally {
        isApplyingReplacements.value = false
      }
    }
  }

  fun saveAllStagedAsCopies() {
    viewModelScope.launch {
      showBatchReplaceDialog.value = false
      isApplyingReplacements.value = true
      try {
        val config = prefsRepo.configFlow.first()
        val awaiting = withContext(Dispatchers.IO) { queueRepo.getAwaitingApprovalJobs() }
        var savedCount = 0
        for (job in awaiting) {
          runCatching {
            val saved =
              withContext(Dispatchers.IO) {
                replacementManager.saveStagedAsCopy(job, config.outputTreeUri)
              }
            queueRepo.markCompleted(
              id = job.id,
              replacedOriginal = false,
              outputUri = saved.outputUri.toString(),
              outputFileName = saved.outputFileName,
              outputSizeBytes = saved.outputSizeBytes,
              usedHardwareEncoder = job.usedHardwareEncoder ?: false,
              encoderName = job.encoderName ?: "MediaCodec",
            )
            savedCount++
          }.onFailure {
            queueRepo.markAwaitingApprovalError(job.id, CompressionLogger.formatUserErrorMessage(it))
          }
        }
        if (savedCount > 0) {
          bannerMessage.value = "Saved $savedCount compressed copies (originals preserved)"
        }
      } finally {
        isApplyingReplacements.value = false
      }
    }
  }

  fun onSharedPayloadReceived(payload: ParsedSharePayload) {
    viewModelScope.launch {
      if (payload.isEmpty) {
        bannerMessage.value =
          if (!payload.captionText.isNullOrBlank()) {
            "No readable video URIs found in shared content."
          } else {
            "Unable to open shared video content."
          }
        return@launch
      }

      isImporting.value = true
      try {
        val currentConfig = prefsRepo.configFlow.first()
        val result =
          queueRepo.enqueueVideosDetailed(
            uris = payload.uris,
            config = currentConfig,
            deduplicateActive = true,
          )
        val addedCount = result.addedJobs.size
        val skippedCount = result.skippedDuplicateCount

        if (addedCount > 0) {
          selectedFilter.value = QueueFilter.ALL
          val existingReview = shareReviewState.value
          val mergedIds =
            ((existingReview?.jobIds ?: emptyList()) + result.addedJobs.map { it.id }).distinct()
          shareReviewState.value =
            IncomingShareReviewState(
              jobIds = mergedIds,
              captionText = payload.captionText ?: existingReview?.captionText,
              skippedDuplicateCount = (existingReview?.skippedDuplicateCount ?: 0) + skippedCount,
            )
          bannerMessage.value =
            buildString {
              append("Received $addedCount shared video${if (addedCount == 1) "" else "s"}")
              if (skippedCount > 0) {
                append(" ($skippedCount duplicate${if (skippedCount == 1) "" else "s"} skipped)")
              }
            }
        } else if (skippedCount > 0) {
          selectedFilter.value = QueueFilter.QUEUED
          bannerMessage.value =
            "Shared video${if (skippedCount == 1) " is" else "s are"} already in the compression queue"
        }
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXVM", "Failed receiving shared videos", t)
        bannerMessage.value = "Failed receiving shared videos: ${CompressionLogger.formatUserErrorMessage(t)}"
      } finally {
        isImporting.value = false
      }
    }
  }

  fun dismissShareReview() {
    shareReviewState.value = null
  }

  fun confirmShareReviewAndPrepareStart(onReadyToStart: () -> Unit) {
    viewModelScope.launch {
      syncShareReviewJobsWithLatestConfig()
      shareReviewState.value = null
      onReadyToStart()
    }
  }

  private suspend fun syncShareReviewJobsWithLatestConfig() {
    val activeShare = shareReviewState.value ?: return
    if (activeShare.jobIds.isEmpty()) return
    val updatedConfig = prefsRepo.configFlow.first()
    queueRepo.applyConfigToQueuedJobs(activeShare.jobIds, updatedConfig)
  }

  fun onVideosSelected(uris: List<Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      isImporting.value = true
      try {
        val currentConfig = prefsRepo.configFlow.first()
        val result =
          queueRepo.enqueueVideosDetailed(
            uris = uris,
            config = currentConfig,
            deduplicateActive = true,
          )
        val addedCount = result.addedJobs.size
        val skipped = result.skippedDuplicateCount
        bannerMessage.value =
          when {
            addedCount > 0 && skipped > 0 ->
              "Added $addedCount video${if (addedCount == 1) "" else "s"} ($skipped duplicate${if (skipped == 1) "" else "s"} skipped)"
            addedCount > 0 ->
              "Added $addedCount video${if (addedCount == 1) "" else "s"} to batch queue"
            skipped > 0 ->
              "Selected video${if (skipped == 1) " is" else "s are"} already in the queue"
            else -> null
          }
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
    viewModelScope.launch {
      prefsRepo.updatePreset(preset)
      syncShareReviewJobsWithLatestConfig()
    }
  }

  fun selectResolution(resolution: TargetResolution) {
    viewModelScope.launch {
      prefsRepo.updateResolution(resolution)
      if (uiState.value.config.preset != CompressionPreset.CUSTOM) {
        prefsRepo.updatePreset(CompressionPreset.CUSTOM)
      }
      syncShareReviewJobsWithLatestConfig()
    }
  }

  fun selectCodec(codec: VideoCodec) {
    viewModelScope.launch {
      prefsRepo.updateCodec(codec)
      syncShareReviewJobsWithLatestConfig()
    }
  }

  fun updateCustomBitrateKbps(kbps: Int) {
    viewModelScope.launch {
      prefsRepo.updateCustomBitrateKbps(kbps)
      if (uiState.value.config.preset != CompressionPreset.CUSTOM) {
        prefsRepo.updatePreset(CompressionPreset.CUSTOM)
      }
      syncShareReviewJobsWithLatestConfig()
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
