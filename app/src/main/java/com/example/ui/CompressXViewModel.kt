package com.example.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.CompressXApplication
import com.example.data.EnqueueBatchResult
import com.example.data.RelinkResult
import com.example.data.VideoJobEntity
import com.example.engine.CompressionLogger
import com.example.engine.MediaStoreMeta
import com.example.engine.ParsedSharePayload
import com.example.engine.ReplacementOutcome
import com.example.model.BatchSummaryStats
import com.example.model.CodecCapabilities
import com.example.model.CompressionPreset
import com.example.model.GuardrailState
import com.example.model.JobStatus
import com.example.model.OutputMode
import com.example.model.ReplacementPermissionStatus
import com.example.model.TargetResolution
import com.example.model.VideoCodec
import com.example.model.VideoCompressionConfig
import com.example.service.CompressionForegroundService
import com.example.ui.components.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class QueueFilter(val label: String) {
  ALL("All"),
  QUEUED("Queued"),
  PROCESSING("Active"),
  AWAITING("Approve"),
  COMPLETED("Done"),
  FAILED("Failed"),
}

enum class SystemApprovalKind {
  BATCH_MEDIASTORE_DELETE,
  LEGACY_SINGLE_WRITE,
}

data class PendingSystemApprovalRequest(
  val intentSender: IntentSender,
  val kind: SystemApprovalKind,
  val approvedJobIds: Set<String>,
  val snapshots: Map<String, MediaStoreMeta> = emptyMap(),
  val directSafJobIds: Set<String> = emptySet(),
  val legacyJobIds: Set<String> = emptySet(),
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
  val selectedApprovalJobIds: Set<String> = emptySet(),
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

  val eligibleAwaitingApprovalJobs: List<VideoJobEntity>
    get() =
      awaitingApprovalJobs.filter { it.replacementStatus != ReplacementPermissionStatus.READ_ONLY }

  val readOnlyAwaitingApprovalJobs: List<VideoJobEntity>
    get() =
      awaitingApprovalJobs.filter { it.replacementStatus == ReplacementPermissionStatus.READ_ONLY }

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

  private val queueMutationMutex = Mutex()

  private val selectedFilter = MutableStateFlow(QueueFilter.ALL)
  private val selectedApprovalJobIds = MutableStateFlow<Set<String>>(emptySet())
  private val isImporting = MutableStateFlow(false)
  private val isApplyingReplacements = MutableStateFlow(false)
  private val showReplaceConsentDialog = MutableStateFlow(false)
  private val showBatchReplaceDialog = MutableStateFlow(false)
  private val shareReviewState = MutableStateFlow<IncomingShareReviewState?>(null)
  private val pendingSystemApproval = MutableStateFlow<PendingSystemApprovalRequest?>(null)
  private val bannerMessage = MutableStateFlow<String?>(null)
  private val guardrailsFlow = MutableStateFlow(guardrailsMonitor.sample())

  /**
   * Pure state derivation — ZERO side-effect mutations inside combine() to prevent re-entrant
   * StateFlow loops and crashes during rapid queue mutations or deletions.
   */
  val uiState: StateFlow<StudioUiState> =
    combine(
      queueRepo.allJobs,
      queueRepo.completedHistory,
      prefsRepo.configFlow,
      selectedFilter,
      selectedApprovalJobIds,
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
      @Suppress("UNCHECKED_CAST")
      val selectedApproval = values[4] as Set<String>
      val importing = values[5] as Boolean
      val replacing = values[6] as Boolean
      val showConsent = values[7] as Boolean
      val showBatchConfirm = values[8] as Boolean
      val shareReview = values[9] as IncomingShareReviewState?
      val sysApproval = values[10] as PendingSystemApprovalRequest?
      val banner = values[11] as String?
      val guardrails = values[12] as GuardrailState

      val stats =
        queueRepo.computeStats(
          jobs = jobs,
          hasHardwareEncoder = app.codecCapabilities.hasAnyHardwareEncoder,
          preferHardware = config.preferHardwareAcceleration,
        )

      val currentEligibleIds =
        jobs
          .filter {
            it.status == JobStatus.AWAITING_APPROVAL &&
              it.replacementStatus != ReplacementPermissionStatus.READ_ONLY
          }
          .map { it.id }
          .toSet()

      val sanitizedSelection = selectedApproval intersect currentEligibleIds

      StudioUiState(
        jobs = jobs,
        history = history,
        stats = stats,
        config = config,
        capabilities = app.codecCapabilities,
        guardrails = guardrails,
        selectedFilter = filter,
        selectedApprovalJobIds = sanitizedSelection,
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
    // Dedicated side-effect collector on main/viewModelScope for approval auto-selection & dialog trigger
    viewModelScope.launch {
      var prevProcessingCount = 0
      var knownEligibleAwaitingIds = emptySet<String>()

      queueRepo.allJobs.collect { jobs ->
        val processingCount = jobs.count { it.status == JobStatus.PROCESSING }
        val awaitingCount = jobs.count { it.status == JobStatus.AWAITING_APPROVAL }
        val currentEligibleIds =
          jobs
            .filter {
              it.status == JobStatus.AWAITING_APPROVAL &&
                it.replacementStatus != ReplacementPermissionStatus.READ_ONLY
            }
            .map { it.id }
            .toSet()

        val newlyArrivedEligible = currentEligibleIds - knownEligibleAwaitingIds
        selectedApprovalJobIds.update { existing ->
          (existing intersect currentEligibleIds) + newlyArrivedEligible
        }
        knownEligibleAwaitingIds = currentEligibleIds

        if (prevProcessingCount > 0 && processingCount == 0 && awaitingCount > 0) {
          if (currentEligibleIds.isNotEmpty()) {
            showBatchReplaceDialog.value = true
          } else {
            bannerMessage.value =
              "Compressed outputs ready. Read-only sources kept intact — tap Save Copies."
          }
        }
        prevProcessingCount = processingCount
      }
    }

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
          syncAllQueuedJobsWithLatestConfig()
          bannerMessage.value =
            "Replace Original Mode active — originals replaced after verification & approval."
        } else {
          showReplaceConsentDialog.value = true
        }
      } else {
        prefsRepo.updateOutputMode(OutputMode.KEEP_ORIGINAL)
        syncAllQueuedJobsWithLatestConfig()
        bannerMessage.value = "Keep Original Mode active — compressed copies saved separately."
      }
    }
  }

  fun confirmReplaceOriginalConsent() {
    viewModelScope.launch {
      prefsRepo.updateOutputMode(OutputMode.REPLACE_ORIGINAL, consentGranted = true)
      showReplaceConsentDialog.value = false
      syncAllQueuedJobsWithLatestConfig()
      bannerMessage.value =
        "Replace Original Mode enabled. Originals are only replaced after verification & approval."
    }
  }

  fun dismissReplaceOriginalConsent() {
    showReplaceConsentDialog.value = false
  }

  fun toggleApprovalSelection(jobId: String) {
    val job = uiState.value.jobs.firstOrNull { it.id == jobId } ?: return
    if (job.status != JobStatus.AWAITING_APPROVAL) return
    if (job.replacementStatus == ReplacementPermissionStatus.READ_ONLY) {
      bannerMessage.value =
        job.errorMessage
          ?: "Read-only source provider does not support replacement. Tap Save Copy instead."
      return
    }
    selectedApprovalJobIds.update { current ->
      if (jobId in current) current - jobId else current + jobId
    }
  }

  fun selectAllEligibleForReplacement() {
    val eligibleIds = uiState.value.eligibleAwaitingApprovalJobs.map { it.id }.toSet()
    selectedApprovalJobIds.value = eligibleIds
    if (eligibleIds.isEmpty()) {
      val readOnlyCount = uiState.value.readOnlyAwaitingApprovalJobs.size
      if (readOnlyCount > 0) {
        bannerMessage.value =
          "$readOnlyCount read-only video(s) cannot replace originals — tap Save Read-Only Copies."
      }
    }
  }

  fun openBatchReplaceDialog() {
    val eligible = uiState.value.eligibleAwaitingApprovalJobs
    if (eligible.isNotEmpty()) {
      if (selectedApprovalJobIds.value.isEmpty()) {
        selectedApprovalJobIds.value = eligible.map { it.id }.toSet()
      }
      showBatchReplaceDialog.value = true
    } else if (uiState.value.readOnlyAwaitingApprovalJobs.isNotEmpty()) {
      bannerMessage.value =
        "All pending items come from read-only providers — tap Save Read-Only Copies instead."
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
        val activeSelection = selectedApprovalJobIds.value
        val candidates =
          when {
            targetJobIds != null -> allAwaiting.filter { it.id in targetJobIds }
            activeSelection.isNotEmpty() -> allAwaiting.filter { it.id in activeSelection }
            else -> allAwaiting
          }

        if (candidates.isEmpty()) {
          bannerMessage.value = "No verified eligible videos selected for replacement."
          return@launch
        }

        val prep =
          withContext(Dispatchers.IO) { replacementManager.prepareBatchDeleteApproval(candidates) }

        for ((badJob, reason) in prep.readOnlyOrFailedJobs) {
          val assessment =
            withContext(Dispatchers.IO) {
              replacementManager.assessEligibility(Uri.parse(badJob.sourceUri))
            }
          val permStatus =
            if (assessment.status == ReplacementPermissionStatus.READ_ONLY) {
              ReplacementPermissionStatus.READ_ONLY
            } else {
              ReplacementPermissionStatus.FAILED
            }
          queueRepo.markAwaitingApprovalError(badJob.id, reason, permStatus)
        }

        if (prep.deleteRequestIntentSender != null && prep.deleteApprovedJobIds.isNotEmpty()) {
          for (id in prep.deleteApprovedJobIds) {
            queueRepo.updateReplacementStatus(
              id,
              ReplacementPermissionStatus.PENDING_BATCH_APPROVAL,
              null,
            )
          }
          pendingSystemApproval.value =
            PendingSystemApprovalRequest(
              intentSender = prep.deleteRequestIntentSender,
              kind = SystemApprovalKind.BATCH_MEDIASTORE_DELETE,
              approvedJobIds = prep.deleteApprovedJobIds,
              snapshots = prep.snapshots,
              directSafJobIds = prep.directSafJobs.map { it.id }.toSet(),
              legacyJobIds = prep.legacyMediaStoreJobs.map { it.id }.toSet(),
            )
          return@launch
        }

        val directAndLegacy = prep.directSafJobs + prep.legacyMediaStoreJobs
        if (directAndLegacy.isEmpty()) {
          bannerMessage.value =
            prep.readOnlyOrFailedJobs.firstOrNull()?.second
              ?: "Original files are read-only; tap Save Compressed Copy on each card."
          return@launch
        }

        executeApprovedReplacements(directAndLegacy)
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXVM", "Failed preparing batch original replacement", t)
        bannerMessage.value =
          "Replacement failed (originals preserved): ${CompressionLogger.formatUserErrorMessage(t)}"
      } finally {
        if (pendingSystemApproval.value == null) {
          isApplyingReplacements.value = false
        }
      }
    }
  }

  fun onSystemReplacementApprovalResult(
    granted: Boolean,
    request: PendingSystemApprovalRequest,
  ) {
    pendingSystemApproval.value = null
    viewModelScope.launch {
      if (!granted) {
        isApplyingReplacements.value = false
        val deniedIds =
          request.approvedJobIds + request.directSafJobIds + request.legacyJobIds
        for (id in deniedIds) {
          queueRepo.markAwaitingApprovalError(
            id = id,
            errorMessage =
              "Batch deletion permission denied — original video preserved. Tap Save Copy or retry.",
            replacementStatus = ReplacementPermissionStatus.DENIED,
          )
        }
        bannerMessage.value =
          "Batch permission denied — all original videos preserved."
        return@launch
      }

      isApplyingReplacements.value = true
      try {
        val awaitingById =
          withContext(Dispatchers.IO) { queueRepo.getAwaitingApprovalJobs().associateBy { it.id } }

        var replacedCount = 0
        var failedCount = 0
        var freedBytes = 0L

        if (request.kind == SystemApprovalKind.BATCH_MEDIASTORE_DELETE) {
          for (jobId in request.approvedJobIds) {
            val job = awaitingById[jobId] ?: continue
            queueRepo.updateReplacementStatus(
              job.id,
              ReplacementPermissionStatus.BATCH_APPROVED,
              null,
            )
            val outcome =
              withContext(Dispatchers.IO) {
                replacementManager.publishBatchApprovedOutput(job, request.snapshots[job.id])
              }
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
                  replacementStatus = ReplacementPermissionStatus.REPLACED,
                )
                replacedCount++
                freedBytes += (job.originalSizeBytes - outcome.outputSizeBytes).coerceAtLeast(0L)
              }
              is ReplacementOutcome.Failed -> {
                queueRepo.markAwaitingApprovalError(
                  job.id,
                  outcome.errorMessage,
                  ReplacementPermissionStatus.FAILED,
                )
                failedCount++
              }
              is ReplacementOutcome.NeedsSystemIntentSender -> {
                failedCount++
              }
            }
          }

          val remainingNonBatch =
            (request.directSafJobIds + request.legacyJobIds).mapNotNull { awaitingById[it] }
          if (remainingNonBatch.isNotEmpty()) {
            val subResult = executeApprovedReplacements(remainingNonBatch, emitBanner = false)
            replacedCount += subResult.replacedCount
            failedCount += subResult.failedCount
            freedBytes += subResult.freedBytes
            if (subResult.pausedForIntentSender) {
              return@launch
            }
          }

          bannerMessage.value =
            when {
              replacedCount > 0 && failedCount == 0 ->
                "Replaced $replacedCount original video${if (replacedCount == 1) "" else "s"} • Freed ${FormatUtils.formatBytes(freedBytes)}"
              replacedCount > 0 && failedCount > 0 ->
                "Replaced $replacedCount original(s); $failedCount could not be published"
              failedCount > 0 ->
                "Failed publishing $failedCount video(s); staged copies preserved."
              else -> null
            }
        } else {
          val approvedJobs = request.approvedJobIds.mapNotNull { awaitingById[it] }
          executeApprovedReplacements(approvedJobs)
        }
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXVM", "Error executing approved replacements", t)
        bannerMessage.value =
          "Replacement failed (originals preserved): ${CompressionLogger.formatUserErrorMessage(t)}"
      } finally {
        if (pendingSystemApproval.value == null) {
          isApplyingReplacements.value = false
        }
      }
    }
  }

  private data class ExecutionBatchSummary(
    val replacedCount: Int,
    val failedCount: Int,
    val freedBytes: Long,
    val pausedForIntentSender: Boolean,
  )

  private suspend fun executeApprovedReplacements(
    jobs: List<VideoJobEntity>,
    emitBanner: Boolean = true,
  ): ExecutionBatchSummary {
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
            replacementStatus = ReplacementPermissionStatus.REPLACED,
          )
          replacedCount++
          savedBytes += (job.originalSizeBytes - outcome.outputSizeBytes).coerceAtLeast(0L)
        }
        is ReplacementOutcome.NeedsSystemIntentSender -> {
          val remainingIds = jobs.drop(idx).map { it.id }.toSet()
          pendingSystemApproval.value =
            PendingSystemApprovalRequest(
              intentSender = outcome.intentSender,
              kind = SystemApprovalKind.LEGACY_SINGLE_WRITE,
              approvedJobIds = remainingIds,
            )
          return ExecutionBatchSummary(
            replacedCount,
            failedCount,
            savedBytes,
            pausedForIntentSender = true,
          )
        }
        is ReplacementOutcome.Failed -> {
          val isReadOnly =
            replacementManager.assessEligibility(Uri.parse(job.sourceUri)).status ==
              ReplacementPermissionStatus.READ_ONLY
          queueRepo.markAwaitingApprovalError(
            id = job.id,
            errorMessage = outcome.errorMessage,
            replacementStatus =
              if (isReadOnly) ReplacementPermissionStatus.READ_ONLY
              else ReplacementPermissionStatus.FAILED,
          )
          failedCount++
        }
      }
    }

    if (emitBanner) {
      bannerMessage.value =
        when {
          replacedCount > 0 && failedCount == 0 ->
            "Replaced $replacedCount original video${if (replacedCount == 1) "" else "s"} • Freed ${FormatUtils.formatBytes(savedBytes)}"
          replacedCount > 0 && failedCount > 0 ->
            "Replaced $replacedCount original(s); $failedCount preserved due to storage permissions"
          failedCount > 0 ->
            "Replacement failed ($failedCount preserved intact). See card details or Save Copy."
          else -> null
        }
    }
    return ExecutionBatchSummary(
      replacedCount,
      failedCount,
      savedBytes,
      pausedForIntentSender = false,
    )
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
          replacementStatus = ReplacementPermissionStatus.SAVED_COPY,
        )
        bannerMessage.value = "Saved copy as ${saved.outputFileName} (original preserved)"
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXVM", "Failed saving staged copy for $jobId", t)
        queueRepo.markAwaitingApprovalError(
          id = jobId,
          errorMessage = CompressionLogger.formatUserErrorMessage(t),
          replacementStatus = ReplacementPermissionStatus.FAILED,
        )
        bannerMessage.value = "Failed saving copy: ${CompressionLogger.formatUserErrorMessage(t)}"
      } finally {
        isApplyingReplacements.value = false
      }
    }
  }

  fun saveAllReadOnlyAsCopies() {
    saveAwaitingAsCopies(readOnlyOnly = true)
  }

  fun saveAllStagedAsCopies() {
    saveAwaitingAsCopies(readOnlyOnly = false)
  }

  private fun saveAwaitingAsCopies(readOnlyOnly: Boolean) {
    viewModelScope.launch {
      showBatchReplaceDialog.value = false
      isApplyingReplacements.value = true
      try {
        val config = prefsRepo.configFlow.first()
        val awaiting = withContext(Dispatchers.IO) { queueRepo.getAwaitingApprovalJobs() }
        val targetJobs =
          if (readOnlyOnly) {
            awaiting.filter { it.replacementStatus == ReplacementPermissionStatus.READ_ONLY }
          } else {
            awaiting
          }
        var savedCount = 0
        for (job in targetJobs) {
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
              replacementStatus = ReplacementPermissionStatus.SAVED_COPY,
            )
            savedCount++
          }.onFailure {
            queueRepo.markAwaitingApprovalError(
              id = job.id,
              errorMessage = CompressionLogger.formatUserErrorMessage(it),
              replacementStatus = ReplacementPermissionStatus.FAILED,
            )
          }
        }
        if (savedCount > 0) {
          bannerMessage.value =
            if (readOnlyOnly) {
              "Saved $savedCount compressed copies from read-only sources (originals untouched)"
            } else {
              "Saved $savedCount compressed copies (originals preserved)"
            }
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
          bannerMessage.value = formatEnqueueMessage(result, isShared = true)
        } else if (skippedCount > 0) {
          selectedFilter.value =
            if (result.skippedCompletedCount > 0 && result.skippedActiveCount == 0) {
              QueueFilter.COMPLETED
            } else {
              QueueFilter.QUEUED
            }
          bannerMessage.value = formatEnqueueMessage(result, isShared = true)
        }
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXVM", "Failed receiving shared videos", t)
        bannerMessage.value =
          "Failed receiving shared videos: ${CompressionLogger.formatUserErrorMessage(t)}"
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
      syncAllQueuedJobsWithLatestConfig()
      shareReviewState.value = null
      onReadyToStart()
    }
  }

  private suspend fun syncAllQueuedJobsWithLatestConfig() {
    val updatedConfig = prefsRepo.configFlow.first()
    queueRepo.applyConfigToAllQueuedJobs(updatedConfig)
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
        bannerMessage.value = formatEnqueueMessage(result, isShared = false)
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXVM", "Failed to import selected videos", t)
        bannerMessage.value =
          "Failed to import selected videos: ${CompressionLogger.formatUserErrorMessage(t)}"
      } finally {
        isImporting.value = false
      }
    }
  }

  private fun formatEnqueueMessage(result: EnqueueBatchResult, isShared: Boolean): String? {
    val added = result.addedJobs.size
    val skipped = result.skippedDuplicateCount
    val readOnlyAdded =
      result.addedJobs.count { it.replacementStatus == ReplacementPermissionStatus.READ_ONLY }
    val verb = if (isShared) "Received" else "Added"
    val suffix = if (isShared) "shared video" else "video"

    if (added > 0) {
      return buildString {
        append("$verb $added $suffix${if (added == 1) "" else "s"}")
        if (skipped > 0) {
          val dupDetail =
            when {
              result.skippedCompletedCount > 0 && result.skippedActiveCount == 0 ->
                "$skipped already compressed"
              result.skippedCompletedCount > 0 ->
                "$skipped duplicate${if (skipped == 1) "" else "s"} (${result.skippedCompletedCount} already compressed)"
              else ->
                "$skipped duplicate${if (skipped == 1) "" else "s"} skipped"
            }
          append(" • $dupDetail")
        }
        if (readOnlyAdded > 0) {
          append(" • $readOnlyAdded read-only")
        }
      }
    }

    if (skipped > 0) {
      return when {
        result.skippedCompletedCount == skipped ->
          if (skipped == 1) "Duplicate skipped: this video was already compressed."
          else "$skipped duplicates skipped: these videos were already compressed."
        result.skippedCompletedCount > 0 ->
          "$skipped duplicates skipped (${result.skippedCompletedCount} already compressed, ${result.skippedActiveCount + result.skippedInBatchCount} in queue)."
        else ->
          if (skipped == 1) "Duplicate skipped: this video is already in the queue."
          else "$skipped duplicates skipped: already in the compression queue."
      }
    }
    return null
  }

  fun relinkJob(jobId: String, newUri: Uri) {
    viewModelScope.launch {
      try {
        when (val res = queueRepo.relinkJob(jobId, newUri)) {
          is RelinkResult.Success -> {
            bannerMessage.value = "Relinked ${res.job.fileName} — queued for compression."
            if (uiState.value.isBatchRunning) {
              // Active runner will naturally dequeue this job next
            }
          }
          is RelinkResult.DuplicateConflict -> {
            bannerMessage.value = "Relink cancelled: ${res.message}"
          }
          is RelinkResult.InvalidFile -> {
            bannerMessage.value = "Cannot relink: ${res.message}"
          }
          RelinkResult.NotFound -> {
            bannerMessage.value = "Queue item was already removed."
          }
        }
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXVM", "Relink failed for $jobId", t)
        bannerMessage.value = "Relink failed: ${CompressionLogger.formatUserErrorMessage(t)}"
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
      queueMutationMutex.withLock {
        runCatching {
          val job = withContext(Dispatchers.IO) { queueRepo.getJobById(jobId) }
          val isActive =
            job?.status == JobStatus.PROCESSING || app.batchQueueRunner.activeJobId == jobId
          if (isActive) {
            CompressionForegroundService.cancelSpecificJob(app, jobId)
          }
          queueRepo.cancelJob(jobId)
        }.onFailure {
          CompressionLogger.e("CompressXVM", "Failed cancelling job $jobId", it)
          bannerMessage.value = "Unable to cancel video: ${CompressionLogger.formatUserErrorMessage(it)}"
        }
      }
    }
  }

  fun retryJob(jobId: String) {
    viewModelScope.launch {
      val job = withContext(Dispatchers.IO) { queueRepo.getJobById(jobId) } ?: return@launch
      if (job.needsRelink && !queueRepo.isUriReadable(Uri.parse(job.sourceUri))) {
        bannerMessage.value =
          "Storage permission lost for ${job.fileName}. Tap Relink File to re-select it."
        return@launch
      }
      val retried = queueRepo.retryJob(jobId)
      if (retried) {
        startQueueOrFallback(isAutoResume = false)
      }
    }
  }

  fun retryAllFailed() {
    viewModelScope.launch {
      try {
        val result = queueRepo.retryAllFailedJobs()
        if (result.retriedCount > 0) {
          startQueueOrFallback(isAutoResume = false)
          bannerMessage.value =
            if (result.needsRelinkRemainingCount > 0) {
              "Retrying ${result.retriedCount} failed video(s) • ${result.needsRelinkRemainingCount} need Relink File."
            } else {
              "Retrying ${result.retriedCount} failed video${if (result.retriedCount == 1) "" else "s"}."
            }
        } else if (result.needsRelinkRemainingCount > 0) {
          bannerMessage.value =
            "${result.needsRelinkRemainingCount} failed video(s) lost storage access — tap Relink File on each card."
        } else {
          bannerMessage.value = "No failed videos available to retry."
        }
      } catch (t: Throwable) {
        CompressionLogger.e("CompressXVM", "Retry all failed error", t)
        bannerMessage.value = "Retry failed: ${CompressionLogger.formatUserErrorMessage(t)}"
      }
    }
  }

  /**
   * Thread-safe, race-free deletion of ANY queue item (QUEUED, PROCESSING, PAUSED, FAILED,
   * AWAITING_APPROVAL, COMPLETED, CANCELLED). If the item is currently encoding, ONLY that job is
   * cancelled while the rest of the batch continues uninterrupted.
   */
  fun deleteJob(jobId: String) {
    viewModelScope.launch {
      queueMutationMutex.withLock {
        runCatching {
          selectedApprovalJobIds.update { it - jobId }
          shareReviewState.update { review ->
            if (review == null) null
            else {
              val remaining = review.jobIds.filterNot { it == jobId }
              if (remaining.isEmpty()) null else review.copy(jobIds = remaining)
            }
          }

          val existing = withContext(Dispatchers.IO) { queueRepo.getJobById(jobId) }
          val isActive =
            existing?.status == JobStatus.PROCESSING || app.batchQueueRunner.activeJobId == jobId
          if (isActive) {
            CompressionForegroundService.cancelSpecificJob(app, jobId)
          }

          queueRepo.deleteJob(jobId)
        }.onFailure {
          CompressionLogger.e("CompressXVM", "Safe deleteJob failed for $jobId", it)
          bannerMessage.value = "Unable to remove item: ${CompressionLogger.formatUserErrorMessage(it)}"
        }
      }
    }
  }

  fun clearCompleted() {
    viewModelScope.launch {
      queueMutationMutex.withLock {
        runCatching { queueRepo.clearCompletedAndCancelled() }
          .onFailure { CompressionLogger.e("CompressXVM", "clearCompleted failed", it) }
      }
    }
  }

  fun clearAllQueue() {
    viewModelScope.launch {
      queueMutationMutex.withLock {
        runCatching {
          CompressionForegroundService.pauseQueue(app)
          delay(150L)
          selectedApprovalJobIds.value = emptySet()
          shareReviewState.value = null
          queueRepo.clearAll()
        }.onFailure { CompressionLogger.e("CompressXVM", "clearAllQueue failed", it) }
      }
    }
  }

  fun moveJobById(jobId: String, direction: Int, visibleJobIds: List<String>) {
    if (direction == 0) return
    viewModelScope.launch {
      queueMutationMutex.withLock {
        runCatching {
          val fromIdx = visibleJobIds.indexOf(jobId)
          val toIdx = fromIdx + direction
          if (fromIdx < 0 || toIdx !in visibleJobIds.indices) return@withLock

          val mutableVisible = visibleJobIds.toMutableList()
          val movedId = mutableVisible.removeAt(fromIdx)
          mutableVisible.add(toIdx, movedId)

          val allOrdered = uiState.value.jobs.map { it.id }.toMutableList()
          val visibleSet = visibleJobIds.toSet()
          val slots = allOrdered.mapIndexedNotNull { idx, id -> if (id in visibleSet) idx else null }
          slots.forEachIndexed { slotIdx, globalIdx ->
            if (slotIdx < mutableVisible.size && globalIdx in allOrdered.indices) {
              allOrdered[globalIdx] = mutableVisible[slotIdx]
            }
          }
          queueRepo.reorderJobs(allOrdered)
        }.onFailure {
          CompressionLogger.w("CompressXVM", "Safe reorder skipped due to concurrent update", it)
        }
      }
    }
  }

  fun moveJob(fromIndex: Int, toIndex: Int, visibleJobs: List<VideoJobEntity>) {
    if (fromIndex == toIndex) return
    val target = visibleJobs.getOrNull(fromIndex) ?: return
    val direction = if (toIndex > fromIndex) 1 else -1
    moveJobById(target.id, direction, visibleJobs.map { it.id })
  }

  fun selectFilter(filter: QueueFilter) {
    selectedFilter.value = filter
  }

  fun selectPreset(preset: CompressionPreset) {
    viewModelScope.launch {
      prefsRepo.updatePreset(preset)
      syncAllQueuedJobsWithLatestConfig()
    }
  }

  fun selectResolution(resolution: TargetResolution) {
    viewModelScope.launch {
      prefsRepo.updateResolution(resolution)
      if (uiState.value.config.preset != CompressionPreset.CUSTOM) {
        prefsRepo.updatePreset(CompressionPreset.CUSTOM)
      }
      syncAllQueuedJobsWithLatestConfig()
    }
  }

  fun selectCodec(codec: VideoCodec) {
    viewModelScope.launch {
      prefsRepo.updateCodec(codec)
      syncAllQueuedJobsWithLatestConfig()
    }
  }

  fun updateCustomBitrateKbps(kbps: Int) {
    viewModelScope.launch {
      prefsRepo.updateCustomBitrateKbps(kbps)
      if (uiState.value.config.preset != CompressionPreset.CUSTOM) {
        prefsRepo.updatePreset(CompressionPreset.CUSTOM)
      }
      syncAllQueuedJobsWithLatestConfig()
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
