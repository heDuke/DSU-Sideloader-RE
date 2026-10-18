package vegabobo.dsusideloader.ui.screen.home

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vegabobo.dsusideloader.BuildConfig
import vegabobo.dsusideloader.IPrivilegedService
import vegabobo.dsusideloader.core.BaseViewModel
import vegabobo.dsusideloader.core.StorageManager
import vegabobo.dsusideloader.core.TaskWorkspaces
import vegabobo.dsusideloader.installer.adb.AdbInstallationHandler
import vegabobo.dsusideloader.installer.privileged.DataStorePendingRemountStore
import vegabobo.dsusideloader.installer.privileged.DsuInstallationHandler
import vegabobo.dsusideloader.installer.privileged.DsuSubmission
import vegabobo.dsusideloader.installer.privileged.DsuSubmissionStep
import vegabobo.dsusideloader.installer.privileged.LogcatDiagnostic
import vegabobo.dsusideloader.installer.privileged.SdCardRemounter
import vegabobo.dsusideloader.installer.privileged.SystemDsuState
import vegabobo.dsusideloader.installer.privileged.asVolumeOps
import vegabobo.dsusideloader.installer.root.CleanupResult
import vegabobo.dsusideloader.installer.root.DSUInstaller
import vegabobo.dsusideloader.installer.root.DsuInstallResult
import vegabobo.dsusideloader.installer.task.InstallOutcome
import vegabobo.dsusideloader.installer.task.InstallTaskGate
import vegabobo.dsusideloader.installer.task.TaskSteps
import vegabobo.dsusideloader.model.DSUInstallationSource
import vegabobo.dsusideloader.model.Session
import vegabobo.dsusideloader.preferences.AppPrefs
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.preparation.Preparation
import vegabobo.dsusideloader.service.PrivilegedProvider
import vegabobo.dsusideloader.util.DevicePropUtils
import vegabobo.dsusideloader.util.FilenameUtils
import vegabobo.dsusideloader.util.OperationMode
import vegabobo.dsusideloader.util.OperationModeUtils
import vegabobo.dsusideloader.util.StorageUtils

@HiltViewModel
class HomeViewModel @Inject constructor(
    val application: Application,
    override val dataStore: DataStore<Preferences>,
    private val storageManager: StorageManager,
    var session: Session,
) : BaseViewModel(dataStore) {

    private val tag = this.javaClass.simpleName

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    var checkDynamicPartitions = true
    var checkUnavaiableStorage = true
    var checkReadLogsPermission = true
    var disabledStorageCheck = false

    var installationJob: Job = Job()
    var logger: LogcatDiagnostic? = null
    private val sdCardRemounter =
        SdCardRemounter(DataStorePendingRemountStore(dataStore), log = { Log.d("SdCardRemounter", it) })

    /** Task that unmounted SD volumes; only its confirmed terminal outcome may remount them. */
    @Volatile
    private var taskThatUnmounted: Long? = null

    private val allocPercentage = DevicePropUtils.getGsidBinaryAllowedPerc()
    val allocPercentageInt = String.format("%.0f", allocPercentage * 100).toInt()

    private val storageStats = StorageUtils.getAllocInfo(allocPercentage)
    private val hasAvailableStorage = storageStats.first
    private val maximumAllowedForAllocation = storageStats.second

    //
    // Helper methods used for controlling UI State
    //

    private fun updateAdditionalCardState(additionalCard: AdditionalCardState) =
        _uiState.update { it.copy(additionalCard = additionalCard) }

    private fun updateUserdataCard(update: (UserDataCardState) -> UserDataCardState) =
        _uiState.update { it.copy(userDataCard = update(it.userDataCard.copy())) }

    private fun updateInstallationCard(update: (InstallationCardState) -> InstallationCardState) =
        _uiState.update { it.copy(installationCard = update(it.installationCard.copy())) }

    private fun updateImageSizeCard(update: (ImageSizeCardState) -> ImageSizeCardState) =
        _uiState.update { it.copy(imageSizeCard = update(it.imageSizeCard.copy())) }

    private fun updateSheetState(sheetDisplay: SheetDisplayState) =
        _uiState.update { it.copy(sheetDisplay = sheetDisplay) }

    fun resetInstallationCard() {
        _uiState.update {
            it.copy(
                installationCard = InstallationCardState(),
                sheetDisplay = SheetDisplayState.NONE,
            )
        }
        // Leaving a card is NOT a terminal outcome: never remount here (review R3).
        refreshStorageRestorePending()
    }

    /** Remount only for the confirmed terminal outcome of the task that unmounted. */
    private fun remountSdCardsIfOwnedBy(id: Long, reason: String) {
        if (taskThatUnmounted != id) return
        taskThatUnmounted = null
        viewModelScope.launch(Dispatchers.IO) {
            sdCardRemounter.remountPendingIfAny(reason)
            refreshStorageRestorePending()
        }
    }

    private fun currentBootId(): String? =
        runCatching { Settings.Global.getString(application.contentResolver, Settings.Global.BOOT_COUNT) }
            .getOrNull()

    private fun querySystemDsuState(service: IPrivilegedService): SystemDsuState {
        // getInstallationProgress needs MANAGE_DYNAMIC_SYSTEM: only meaningful as root.
        if (!session.isRoot()) return SystemDsuState.UNKNOWN
        return runCatching {
            if (service.installationProgress.status == 1) SystemDsuState.INSTALLING else SystemDsuState.IDLE
        }.getOrDefault(SystemDsuState.UNKNOWN)
    }

    private fun refreshStorageRestorePending() {
        viewModelScope.launch(Dispatchers.IO) {
            val pending = sdCardRemounter.hasPending()
            showStorageRestore(pending, promptIfIdle = pending && !taskGate.isBusy())
        }
    }

    private fun showStorageRestore(pending: Boolean, promptIfIdle: Boolean, blocked: Boolean = false, error: String = "") {
        updateInstallationCard {
            val step = when {
                !pending && it.installationStep == InstallationStep.STORAGE_RESTORE_PENDING ->
                    InstallationStep.NOT_INSTALLING
                promptIfIdle && it.installationStep == InstallationStep.NOT_INSTALLING ->
                    InstallationStep.STORAGE_RESTORE_PENDING
                else -> it.installationStep
            }
            it.copy(
                installationStep = step,
                storageRestorePending = pending,
                storageRestoreBlocked = pending && blocked,
                storageRestoreError = if (pending) error else "",
            )
        }
    }

    /**
     * App start: volumes left unmounted by a previous process. Auto-remount only when the
     * policy proves no installation can still depend on it; otherwise ask the user.
     */
    private fun checkPendingStorageOnAppStart() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!sdCardRemounter.hasPending()) return@launch
            val bootId = currentBootId()
            PrivilegedProvider.run(
                onFail = {
                    viewModelScope.launch(Dispatchers.IO) {
                        sdCardRemounter.onAppStart(null, bootId, SystemDsuState.UNKNOWN)
                        refreshStorageRestorePending()
                    }
                },
            ) {
                sdCardRemounter.onAppStart(asVolumeOps(), bootId, querySystemDsuState(this))
                refreshStorageRestorePending()
            }
        }
    }

    /** Explicit "restore storage": refuse while gsid reports an installation in progress. */
    private fun onRestoreStorage() {
        PrivilegedProvider.run(
            onFail = { showStorageRestore(true, promptIfIdle = false, error = "privileged service unavailable") },
        ) {
            if (querySystemDsuState(this) == SystemDsuState.INSTALLING) {
                showStorageRestore(true, promptIfIdle = false, blocked = true)
                return@run
            }
            val failed = sdCardRemounter.remountPending(asVolumeOps(), "user restore")
            if (failed.isNotEmpty()) {
                val reasons = sdCardRemounter.failureReasons()
                val text = failed.joinToString("; ") { v -> reasons[v]?.let { r -> "$v: $r" } ?: v }
                showStorageRestore(true, promptIfIdle = false, error = text)
                return@run
            }
            taskThatUnmounted = null
            showStorageRestore(false, promptIfIdle = false)
        }
    }

    fun dismissSheet() = updateSheetState(SheetDisplayState.NONE)

    //
    // Home startup and checks
    //

    init {
        // Volumes left unmounted by a previous process (e.g. killed mid-installation).
        checkPendingStorageOnAppStart()

        // Check if a DSU is already installed
        // Root-only because MANAGE_DYNAMIC_SYSTEM is required
        if (session.isRoot()) {
            PrivilegedProvider.run {
                if (isInUse) {
                    updateInstallationCard { it.copy(installationStep = InstallationStep.DSU_ALREADY_RUNNING_DYN_OS) }
                    return@run
                }
                if (isInstalled) {
                    updateInstallationCard { it.copy(installationStep = InstallationStep.DSU_ALREADY_INSTALLED) }
                    return@run
                }
            }
        }
    }

    fun initialChecks() {
        if (checkDynamicPartitions && !DevicePropUtils.hasDynamicPartitions()) {
            updateAdditionalCardState(AdditionalCardState.NO_DYNAMIC_PARTITIONS)
            return
        }

        if (checkUnavaiableStorage && !hasAvailableStorage) {
            updateAdditionalCardState(AdditionalCardState.UNAVAIABLE_STORAGE)
            return
        }

        viewModelScope.launch {
            val result = readStringPref(AppPrefs.SAF_PATH)
            if (!storageManager.arePermissionsGrantedToFolder(result)) {
                updateAdditionalCardState(AdditionalCardState.SETUP_STORAGE)
                return@launch
            }

            if (session.getOperationMode() == OperationMode.SHIZUKU && checkReadLogsPermission &&
                !OperationModeUtils.isReadLogsPermissionGranted(application)
            ) {
                _uiState.update { it.copy(passedInitialChecks = false) }
                updateAdditionalCardState(AdditionalCardState.MISSING_READ_LOGS_PERMISSION)
                return@launch
            }

            val seenUnlockedBootloaderWarning = readBoolPref(AppPrefs.BOOTLOADER_UNLOCKED_WARNING)
            if (!seenUnlockedBootloaderWarning) {
                _uiState.update { it.copy(passedInitialChecks = false) }
                updateAdditionalCardState(AdditionalCardState.BOOTLOADER_UNLOCKED_WARNING)
                return@launch
            }

            updateAdditionalCardState(AdditionalCardState.NONE)
            _uiState.update { it.copy(passedInitialChecks = true) }
        }
    }

    fun setupUserPreferences() {
        viewModelScope.launch {
            val shouldKeepScreenOn = readBoolPref(AppPrefs.KEEP_SCREEN_ON)
            Log.d(tag, "shouldKeepScreenOn: $shouldKeepScreenOn")
            _uiState.update { it.copy(shouldKeepScreenOn = shouldKeepScreenOn) }

            disabledStorageCheck = readBoolPref(AppPrefs.DISABLE_STORAGE_CHECK)
            Log.d(tag, "disabledStorageCheck: $shouldKeepScreenOn")
        }
    }

    fun overrideDynamicPartitionCheck() {
        checkDynamicPartitions = false
        Log.d(tag, "checkDynamicPartitions: $checkDynamicPartitions")
        initialChecks()
    }

    fun overrideUnavaiableStorage() {
        checkUnavaiableStorage = false
        Log.d(tag, "checkUnavaiableStorage: $checkUnavaiableStorage")
        initialChecks()
    }

    fun onClickBootloaderUnlockedWarning() {
        viewModelScope.launch {
            updateBoolPref(AppPrefs.BOOTLOADER_UNLOCKED_WARNING, true)
            initialChecks()
        }
    }

    //
    // Installation
    //
    // Every installation runs as a task of [taskGate]: one task at a time, identified by an
    // id. Callbacks carry the id; updates from a task that is no longer current (or that is
    // being cancelled) are dropped, so a stale task can't overwrite a newer task's state.
    // The task is released only when its terminal result (including cleanup) is known.
    //

    private val taskGate = InstallTaskGate()

    /** The DSU app submission step of the current task, if any. */
    @Volatile
    private var dsuSubmission: DsuSubmissionStep? = null

    fun obtainSelectedFilename(): String = session.userSelection.selectedFileName

    fun onClickCancel() {
        if (uiState.value.isInstalling() && taskGate.current()?.cancelling == false) {
            updateSheetState(SheetDisplayState.CANCEL_INSTALLATION)
        }
    }

    fun onClickInstall() {
        session.userSelection.setUserDataSize(uiState.value.userDataCard.text)
        session.userSelection.setImageSize(uiState.value.imageSizeCard.text)
        updateSheetState(SheetDisplayState.CONFIRM_INSTALLATION)
    }

    /** Starts a new task, or returns null if one is still running/cleaning up. */
    private fun beginTask(): Long? {
        // Each task gets its own workspace directory (TaskWorkspaces), so the cleanup of one
        // task can never delete files of another.
        val id = taskGate.begin { TaskWorkspaces.taskKey(it, System.currentTimeMillis()) }
        if (id == null) {
            Log.w(tag, "Installation task still running or cleaning up: ${taskGate.current()}")
            return null
        }
        installationJob = Job()
        updateInstallationCard { it.copy(skippedImages = emptyList()) }
        return id
    }

    /** Runs [block] only if task [id] is current and not cancelling. */
    private inline fun ifAccepts(id: Long, block: () -> Unit) {
        if (taskGate.acceptsUpdates(id)) block() else Log.d(tag, "Dropping update of stale task $id")
    }

    fun onConfirmInstallationSheet() {
        dismissSheet()
        val id = beginTask() ?: return
        updateInstallationCard { it.copy(installationStep = InstallationStep.PROCESSING) }
        val job = installationJob
        val taskKey = taskGate.workspaceOf(id) ?: return
        // The whole entry (preference reads included) is one task step: if it is cancelled
        // before it starts, while suspended in a preference read, or fails anywhere, the
        // completion fallback produces exactly one terminal outcome and releases the task.
        TaskSteps.launch(
            scope = viewModelScope,
            context = Dispatchers.IO + job,
            onUnsettled = { cause -> onPreparationStepUnsettled(id, cause) },
            onErrorAfterSettle = { e ->
                Log.e(tag, "Starting the installation failed", e)
                onTaskFailed(id, InstallationStep.ERROR, e.message ?: e.toString())
            },
        ) { step ->
            session.preferences.isUnmountSdCard = readBoolPref(AppPrefs.UMOUNT_SD)
            session.preferences.useBuiltinInstaller = readBoolPref(AppPrefs.USE_BUILTIN_INSTALLER)
            Preparation(
                storageManager = storageManager,
                session = session,
                job = job,
                taskKey = taskKey,
                onStepUpdate = { step -> ifAccepts(id) { onStepUpdate(step) } },
                onPreparationProgressUpdate = { p -> ifAccepts(id) { onPreparationProgressUpdate(p) } },
                onCanceled = { if (step.settle()) onPreparationStepUnsettled(id, CancellationException("preparation cancelled")) },
                onPreparationFinished = { if (step.settle()) onPreparationFinished(id, it) },
            ).invoke()
        }
    }

    /**
     * Terminal outcome of a preparation step that did not hand the task over.
     *
     * The task's own workspace directory is cleaned first (off the caller thread: a
     * cancel-before-start completes synchronously in the cancelling thread) and the task is
     * released only after the cleanup finished, so a next task can't start while it runs; a
     * cleanup failure is part of the reported result.
     */
    private fun onPreparationStepUnsettled(id: Long, cause: Throwable?) {
        val taskKey = taskGate.workspaceOf(id) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val cleanupError = runCatching { storageManager.cleanTaskWorkspace(taskKey, true) }.exceptionOrNull()
            if (cleanupError != null) Log.w(tag, "Workspace cleanup of task $id failed", cleanupError)
            val cleanupNote = cleanupError?.let { "Temporary files could not be removed: ${it.message ?: it}" }
            if (cause is CancellationException || taskGate.isCancelling(id)) {
                finishCancelWithOutcome(id, InstallOutcome(InstallationStep.ERROR_CANCELED, cleanupNote ?: ""))
                return@launch
            }
            // Unsupported/corrupt/truncated input, I/O, preference or storage errors. Finished
            // directly (not via onTaskFailed): a cancel that arrives now must not leave the
            // task unfinished, nobody else would report it.
            Log.e(tag, "Preparation failed", cause)
            val message = cause?.message ?: cause?.toString() ?: "Preparation ended without a result"
            if (!taskGate.finish(id)) return@launch
            remountSdCardsIfOwnedBy(id, "preparation error")
            onInstallationError(InstallationStep.ERROR, listOfNotNull(message, cleanupNote).joinToString("\n"))
        }
    }

    private fun onPreparationFinished(id: Long, dsuInstallation: DSUInstallationSource) {
        Log.d(tag, "DSU preparation finished, result: $dsuInstallation")
        if (taskGate.isCancelling(id)) {
            onTaskCancelledWithoutSystemChanges(id)
            return
        }
        if (!taskGate.isCurrent(id)) return
        session.dsuInstallation = dsuInstallation
        startInstallation(id)
    }

    private fun startInstallation(id: Long) {
        Log.d(tag, "startInstallation(task $id), session: \n$session")
        ifAccepts(id) { updateInstallationCard { it.copy(installationStep = InstallationStep.PROCESSING) } }

        if (session.getOperationMode() == OperationMode.ADB) {
            setupAdbInstallation(id)
            return
        }

        if (session.preferences.useBuiltinInstaller && session.isRoot()) {
            startDSUInstallation(id)
            return
        }

        startPrivilegedInstallation(id)
    }

    private fun setupAdbInstallation(id: Long) {
        AdbInstallationHandler(storageManager, session).generate { scriptPath ->
            Log.d(tag, "Installation script generated: $scriptPath")
            session.installationScriptPath = scriptPath
            // From the app's point of view the task ends here: the user runs the script.
            if (taskGate.finish(id)) {
                resetInstallationCard()
                updateInstallationCard { it.copy(installationStep = InstallationStep.REQUIRES_ADB_CMD_TO_CONTINUE) }
            }
        }
    }

    private fun startDSUInstallation(id: Long) {
        if (!taskGate.setPhase(id, InstallTaskGate.Phase.ROOT_INSTALL)) {
            if (taskGate.isCancelling(id)) onTaskCancelledWithoutSystemChanges(id)
            return
        }
        val job = installationJob
        // Never run the (blocking) installation on the main thread. Not bound to the job:
        // the pipeline polls job.isCancelled and must always run its cleanup and report.
        viewModelScope.launch(Dispatchers.IO) {
            DSUInstaller(
                application = application,
                userdataSize = session.userSelection.userSelectedUserdata,
                dsuInstallation = session.dsuInstallation,
                installationJob = job,
                onInstallationProgressUpdate = { p, part -> ifAccepts(id) { onInstallationProgressUpdate(p, part) } },
                onCreatePartition = { part -> ifAccepts(id) { onCreatePartition(part) } },
                onInstallationStepUpdate = { step -> ifAccepts(id) { onStepUpdate(step) } },
                onFinished = { result -> onRootInstallationFinished(id, result) },
                onImageSkipped = { image ->
                    ifAccepts(id) { updateInstallationCard { it.copy(skippedImages = it.skippedImages + image) } }
                },
            ).invoke()
        }
    }

    private fun onRootInstallationFinished(id: Long, result: DsuInstallResult) {
        val outcome = InstallOutcome.from(result)
        Log.d(tag, "Task $id finished: $result -> $outcome")
        if (!taskGate.finish(id)) {
            Log.w(tag, "Ignoring result of stale task $id")
            return
        }
        updateInstallationCard {
            it.copy(installationStep = outcome.step, errorText = outcome.detail)
        }
    }

    private fun startPrivilegedInstallation(id: Long) {
        if (!taskGate.setPhase(id, InstallTaskGate.Phase.DSU_APP)) {
            if (taskGate.isCancelling(id)) onTaskCancelledWithoutSystemChanges(id)
            return
        }
        ifAccepts(id) {
            updateInstallationCard { it.copy(installationStep = InstallationStep.WAITING_USER_CONFIRMATION) }
        }
        val canTrackResult =
            session.isRoot() || OperationModeUtils.isReadLogsPermissionGranted(application)
        if (session.preferences.isUnmountSdCard) taskThatUnmounted = id
        // Parameters are captured now; the submission runs as a child of the task's Job, so
        // the cancel path can stop it and wait for it (see cancelDsuAppInstallation).
        val submission = DsuInstallationHandler(
            session = session,
            sdCardRemounter = sdCardRemounter,
            bootId = currentBootId(),
        ).submission
        val stepJob = TaskSteps.launch(
            scope = viewModelScope,
            context = Dispatchers.IO + installationJob,
            start = CoroutineStart.LAZY,
            onUnsettled = { cause -> onSubmissionStepUnsettled(id, cause, submission) },
        ) { step ->
            when (val result = submission.submit()) {
                DsuSubmission.Result.Submitted -> {
                    step.settle()
                    if (!canTrackResult) {
                        // Without READ_LOGS we cannot observe the DSU app: the request was submitted,
                        // but success can't be confirmed, so never report INSTALL_SUCCESS here.
                        ifAccepts(id) {
                            updateInstallationCard {
                                it.copy(installationStep = InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED)
                            }
                        }
                    }
                }
                is DsuSubmission.Result.Failed ->
                    if (step.settle()) onTaskFailed(id, InstallationStep.ERROR, result.message)
                // Left unsettled: the completion fallback / cancel path decides.
                DsuSubmission.Result.Cancelled -> Unit
            }
        }
        // Published before the step can run: a concurrent cancel either sees it (and waits
        // for it) or cancelled the task Job first (then the lazy step never runs).
        dsuSubmission = DsuSubmissionStep(id, submission, stepJob)
        stepJob.start()
        if (canTrackResult) {
            startLogging(id)
        }
    }

    // Track and diagnose installation by reading logcat
    private fun startLogging(id: Long) {
        logger?.destroy()
        val newLogger = LogcatDiagnostic(
            onInstallationError = { step, text -> onTaskFailed(id, step, text) },
            onStepUpdate = { step -> ifAccepts(id) { onStepUpdate(step) } },
            onInstallationProgressUpdate = { p, part -> ifAccepts(id) { onInstallationProgressUpdate(p, part) } },
            onInstallationSuccess = { onTaskConfirmedSuccess(id) },
            onLogLineReceived = { if (taskGate.isCurrent(id)) onLogLineReceived() },
        )
        logger = newLogger
        viewModelScope.launch(Dispatchers.IO + installationJob) {
            newLogger.shouldLogEverything = readBoolPref(AppPrefs.FULL_LOGCAT_LOGGING)
            newLogger.startLogging(generateUsefulLogInfo())
        }
    }

    private fun generateUsefulLogInfo(): String {
        return "Device: ${Build.MODEL}\n" +
            "SDK: Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})\n" +
            "$session\n" +
            "Package: ${BuildConfig.APPLICATION_ID}\n" +
            "Version: ${BuildConfig.VERSION_NAME} - ${BuildConfig.VERSION_CODE} (${BuildConfig.BUILD_TYPE}})\n" +
            "checkDynamicPartitions: $checkDynamicPartitions\n" +
            "checkUnavaiableStorage: $checkUnavaiableStorage\n" +
            "checkReadLogsPermission: $checkReadLogsPermission\n" +
            "allocPercentage: $allocPercentage\n" +
            "hasAvailableStorage: $hasAvailableStorage\n" +
            "maximumAllowedForAllocation: $maximumAllowedForAllocation\n"
    }

    fun saveLogs(uriToSaveLogs: Uri) {
        Log.d(tag, "Writing logs to: $uriToSaveLogs")
        storageManager.writeStringToUri(uiState.value.installationLogs, uriToSaveLogs)
    }

    /**
     * Cancel: the card switches to CANCELLING and the task stays locked until the
     * cancellation/cleanup result is known; only then a terminal state is shown.
     */
    fun onClickCancelInstallationButton() {
        dismissSheet()
        val task = taskGate.requestCancel()
        if (task == null) {
            // No task (e.g. ADB result card) -> nothing to stop. Already cancelling -> wait.
            if (!taskGate.isBusy()) resetInstallationCard()
            return
        }
        updateInstallationCard { it.copy(installationStep = InstallationStep.CANCELLING, errorText = "") }
        installationJob.cancel()
        when (task.phase) {
            // Preparation coroutine / root pipeline report their own cancellation result.
            InstallTaskGate.Phase.PREPARING, InstallTaskGate.Phase.ROOT_INSTALL -> Unit
            InstallTaskGate.Phase.DSU_APP -> cancelDsuAppInstallation(task.id)
        }
    }

    /** Submission step ended without a result (cancelled, or returned without settling). */
    private fun onSubmissionStepUnsettled(id: Long, cause: Throwable?, submission: DsuSubmission) {
        if (taskGate.isCancelling(id)) return // the cancel path waits for this step and decides
        if (submission.hasReachedSystem) {
            onTaskFailed(id, InstallationStep.ERROR, cause?.toString() ?: "Submission ended without a result")
        } else {
            onTaskCancelledWithoutSystemChanges(id)
        }
    }

    private fun cancelDsuAppInstallation(id: Long) {
        logger?.destroy()
        val step = dsuSubmission?.takeIf { it.taskId == id }
        viewModelScope.launch(Dispatchers.IO) {
            // Wait until the request can no longer unmount storage or open the DSU prompt.
            val reachedSystem = step?.cancelAndJoin() ?: false
            if (!reachedSystem) {
                finishCancelWithOutcome(id, InstallOutcome(InstallationStep.ERROR_CANCELED, ""))
                return@launch
            }
            val service = PrivilegedProvider.awaitService()
            if (service == null) {
                finishCancelWithOutcome(
                    id,
                    InstallOutcome(InstallationStep.ERROR_CLEANUP_INCOMPLETE, "privileged service unavailable"),
                )
                return@launch
            }
            val outcome = with(service) {
                try {
                    // Since stopping installation requires MANAGE_DYNAMIC_SYSTEM
                    // then, we stop installation using other way, not so polite, but works :)))
                    forceStopPackage("com.android.dynsystem")
                    if (session.isRoot()) {
                        // Root can verify and clean up what the killed DSU app left behind.
                        val aborted = runCatching { abort() }.getOrDefault(false)
                        val removed = runCatching { remove() }.getOrDefault(false)
                        val stillInstalled = runCatching { isInstalled }.getOrNull()
                        InstallOutcome.from(
                            DsuInstallResult.Cancelled(
                                CleanupResult(aborted, removed, stillInstalled = stillInstalled),
                            ),
                        )
                    } else {
                        // Shell can't query DynamicSystem (no MANAGE_DYNAMIC_SYSTEM): state unknown.
                        InstallOutcome(
                            InstallationStep.CANCEL_REQUESTED_CLEANUP_UNVERIFIED,
                            "DSU app stopped; system DSU state can't be verified without root",
                        )
                    }
                } catch (e: Exception) {
                    InstallOutcome(InstallationStep.ERROR_CLEANUP_INCOMPLETE, e.toString())
                }
            }
            finishCancelWithOutcome(id, outcome)
        }
    }

    /** Cancelled before anything was submitted to the system (preparation phase). */
    private fun onTaskCancelledWithoutSystemChanges(id: Long) {
        finishCancelWithOutcome(id, InstallOutcome(InstallationStep.ERROR_CANCELED, ""))
    }

    private fun finishCancelWithOutcome(id: Long, outcome: InstallOutcome) {
        if (!taskGate.finish(id)) return
        session.dsuInstallation = DSUInstallationSource()
        if (outcome.step == InstallationStep.ERROR_CANCELED) {
            remountSdCardsIfOwnedBy(id, "installation cancelled")
        }
        updateInstallationCard { it.copy(installationStep = outcome.step, errorText = outcome.detail) }
    }

    private fun onTaskFailed(id: Long, error: InstallationStep, errorContent: String) {
        if (taskGate.isCancelling(id)) return // the cancel path reports the terminal state
        if (!taskGate.finish(id)) return
        remountSdCardsIfOwnedBy(id, "installation error: $error")
        onInstallationError(error, errorContent)
    }

    // Only called when logs confirmed the DSU app reported completion.
    private fun onTaskConfirmedSuccess(id: Long) {
        if (taskGate.isCancelling(id)) return
        if (!taskGate.finish(id)) return
        updateInstallationCard { it.copy(installationStep = InstallationStep.INSTALL_SUCCESS) }
        remountSdCardsIfOwnedBy(id, "installation completed")
    }

    //
    // Installation Card actions
    //

    fun onClickRebootToDynOS() {
        updateInstallationCard { it.copy(installationStep = InstallationStep.PROCESSING) }
        PrivilegedProvider.run {
            setEnable(true, true)
            Shell.cmd("reboot").exec()
        }
    }

    fun onClickDiscardGsiAndStartInstallation() {
        val id = beginTask() ?: return
        updateInstallationCard { it.copy(installationStep = InstallationStep.PROCESSING) }
        PrivilegedProvider.run(onFail = { onTaskFailed(id, InstallationStep.ERROR, "Service unavailable") }) {
            remove()
            forceStopPackage("com.android.dynsystem")
            startDSUInstallation(id)
        }
    }

    fun onClickDiscardGsi() {
        updateInstallationCard { it.copy(installationStep = InstallationStep.PROCESSING) }
        PrivilegedProvider.run {
            remove()
            forceStopPackage("com.android.dynsystem")
            dismissSheet()
            resetInstallationCard()
        }
    }

    /** Retry within the current task (e.g. re-open the DSU prompt) or as a new task. */
    private fun currentOrNewTask(): Long? {
        val task = taskGate.current()
        if (task != null) return if (task.cancelling) null else task.id
        return beginTask()
    }

    fun onClickRetryInstallation() {
        val id = currentOrNewTask() ?: return
        updateInstallationCard { it.copy(installationStep = InstallationStep.PROCESSING) }
        startInstallation(id)
    }

    fun onClickUnmountSdCardAndRetry() {
        val id = currentOrNewTask() ?: return
        updateInstallationCard { it.copy(installationStep = InstallationStep.PROCESSING) }
        session.preferences.isUnmountSdCard = true
        startInstallation(id)
    }

    fun onClickSetSeLinuxPermissive() {
        val id = currentOrNewTask() ?: return
        updateInstallationCard { it.copy(installationStep = InstallationStep.PROCESSING) }
        viewModelScope.launch(Dispatchers.IO) {
            Shell.cmd("setenforce 0").exec()
            delay(5000)
            startInstallation(id)
        }
    }

    fun onInstallationCardAction(action: InstallationCardAction) {
        when (action) {
            InstallationCardAction.ACK_CLEANUP_INCOMPLETE -> onAcknowledgeCleanupIncomplete()
            InstallationCardAction.ACK_UNCONFIRMED -> onAcknowledgeUnconfirmed()
            InstallationCardAction.RESTORE_STORAGE -> onRestoreStorage()
            InstallationCardAction.RESTORE_STORAGE_LATER ->
                updateInstallationCard { it.copy(installationStep = InstallationStep.NOT_INSTALLING) }
        }
    }

    /**
     * The user states the system DSU flow was finished or cancelled; ends our task.
     * This does not remount: the pending record stays and the restore prompt is shown.
     */
    private fun onAcknowledgeUnconfirmed() {
        taskThatUnmounted = null
        taskGate.current()?.let { taskGate.finish(it.id) }
        resetInstallationCard()
    }

    /**
     * "Return" on the cleanup-incomplete card: re-check the system instead of assuming idle.
     * With root, an image left behind is shown as an installed DSU (discard/reboot actions).
     */
    private fun onAcknowledgeCleanupIncomplete() {
        resetInstallationCard()
        if (!session.isRoot()) return
        PrivilegedProvider.run {
            val installed = runCatching { isInstalled }.getOrNull()
            if (installed != false) {
                updateInstallationCard {
                    it.copy(installationStep = InstallationStep.DSU_ALREADY_INSTALLED)
                }
            }
        }
    }

    fun showDiscardSheet() = updateSheetState(SheetDisplayState.DISCARD_DSU)

    //
    // Userdata card
    //

    fun onCheckUserdataCard() =
        updateUserdataCard { it.copy(isSelected = !it.isSelected, text = "") }

    fun updateUserdataSize(input: String) {
        val selectedSize = FilenameUtils.getDigits(input)
        val sizeWithSuffix = FilenameUtils.appendToDigitsToString(input, "GB")
        Log.d(
            tag,
            "disabledStorageCheck: $disabledStorageCheck, selectedSize: $selectedSize, maximumAllowedForAllocation: $maximumAllowedForAllocation",
        )

        if (!disabledStorageCheck && selectedSize.isNotEmpty() && selectedSize.toInt() > maximumAllowedForAllocation) {
            val fixedSize =
                FilenameUtils.appendToDigitsToString("$maximumAllowedForAllocation", "GB")
            updateUserdataCard {
                it.copy(
                    text = fixedSize,
                    isError = true,
                    maximumAllowed = maximumAllowedForAllocation,
                )
            }
            viewModelScope.launch {
                delay(5000)
                updateUserdataCard { it.copy(isError = false) }
            }
            return
        }

        updateUserdataCard { it.copy(text = sizeWithSuffix) }
    }

    //
    // Image size card
    //

    fun onCheckImageSizeCard() {
        if (!uiState.value.imageSizeCard.isSelected) {
            updateSheetState(SheetDisplayState.IMAGESIZE_WARNING)
        } else {
            dismissSheet()
        }
        updateImageSizeCard { it.copy(isSelected = !it.isSelected, text = "") }
    }

    fun updateImageSize(input: String) {
        val inputWithSuffix = FilenameUtils.appendToDigitsToString(input, "b")
        updateImageSizeCard { it.copy(text = inputWithSuffix) }
    }

    //
    // File selection
    //

    fun takeUriPermission(uri: Uri) {
        application.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        viewModelScope.launch {
            if (storageManager.arePermissionsGrantedToFolder(uri.toString())) {
                updateStringPref(AppPrefs.SAF_PATH, uri.toString()) { initialChecks() }
            }
        }
    }

    fun onFileSelectionResult(uri: Uri) {
        val filename = FilenameUtils.queryName(application.contentResolver, uri)
        val extension = filename.substringAfterLast(".", "")
        val supportedFiles = arrayListOf("gz", "xz", "img", "gzip")

        // DSU packages (zip files), are only supported in R+
        if (Build.VERSION.SDK_INT > 29) {
            supportedFiles.add("zip")
        }
        val isFileSupported = supportedFiles.contains(extension)
        Log.d(tag, "isFileSupported: $isFileSupported, extension: $extension, filename: $filename")

        if (!isFileSupported) {
            viewModelScope.launch {
                updateInstallationCard { it.copy(isError = true, isTextFieldEnabled = false) }
                delay(2000)
                updateInstallationCard { it.copy(isError = false, isTextFieldEnabled = true) }
            }
            return
        }

        session.userSelection.selectedFileName = filename
        session.userSelection.selectedFileUri = uri
        updateInstallationCard {
            it.copy(
                text = filename,
                isTextFieldEnabled = false,
                isInstallable = true,
            )
        }
    }

    //
    // Read logs permission warning
    //

    fun grantReadLogs() {
        updateAdditionalCardState(AdditionalCardState.GRANTING_READ_LOGS_PERMISSION)
        val intent = Intent()
        intent.setClassName(
            BuildConfig.APPLICATION_ID,
            "${BuildConfig.APPLICATION_ID}.MainActivity",
        )
        intent.flags += Intent.FLAG_ACTIVITY_NEW_TASK
        PrivilegedProvider.run {
            grantPermission("android.permission.READ_LOGS")
            if (Build.VERSION.SDK_INT <= 30) {
                forceStopPackage(BuildConfig.APPLICATION_ID)
            }
            startActivity(intent)
        }
    }

    fun refuseReadLogs() {
        checkReadLogsPermission = false
        Log.d(tag, "checkReadLogsPermission: $checkReadLogsPermission")
        initialChecks()
    }

    fun showLogsWarning() {
        updateSheetState(SheetDisplayState.VIEW_LOGS)
    }

    //
    // Progress tracking
    //

    private fun onLogLineReceived() {
        val logs = logger?.logs ?: return
        _uiState.update { it.copy(installationLogs = logs) }
    }

    private fun onStepUpdate(step: InstallationStep) =
        updateInstallationCard { it.copy(installationStep = step) }

    private fun onPreparationProgressUpdate(progress: Float) =
        updateInstallationCard { it.copy(installationProgress = progress) }

    private fun onInstallationError(error: InstallationStep, errorContent: String) {
        updateInstallationCard {
            if (error == InstallationStep.ERROR_SELINUX && !session.isRoot()) {
                it.copy(
                    installationStep = InstallationStep.ERROR_SELINUX_ROOTLESS,
                    errorText = errorContent,
                )
            } else {
                it.copy(installationStep = error, errorText = errorContent)
            }
        }
    }

    private fun onInstallationProgressUpdate(progress: Float, partition: String) =
        updateInstallationCard {
            it.copy(currentPartitionText = partition, installationProgress = progress)
        }

    private fun onCreatePartition(partition: String) =
        updateInstallationCard {
            it.copy(
                installationStep = InstallationStep.CREATING_PARTITION,
                currentPartitionText = partition,
            )
        }
}
