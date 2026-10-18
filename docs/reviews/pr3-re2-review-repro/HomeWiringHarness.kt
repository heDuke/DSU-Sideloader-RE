package review3

import android.util.Log
import vegabobo.dsusideloader.IPrivilegedService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import vegabobo.dsusideloader.installer.privileged.*
import vegabobo.dsusideloader.installer.root.*
import vegabobo.dsusideloader.installer.task.*
import vegabobo.dsusideloader.model.*
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider
import vegabobo.dsusideloader.ui.screen.home.*
import vegabobo.dsusideloader.util.OperationModeUtils
import vegabobo.dsusideloader.util.OperationMode
import vegabobo.dsusideloader.preferences.AppPrefs
import vegabobo.dsusideloader.preparation.Preparation
import vegabobo.dsusideloader.core.TaskWorkspaces
import vegabobo.dsusideloader.installer.adb.AdbInstallationHandler
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.IOException

class ReviewAppContext {
    var externalDirHook: () -> File? = { File(System.getProperty("java.io.tmpdir")) }
    fun getExternalFilesDir(type: String?): File? = externalDirHook()
}
class WorkspaceBoundary {
    val appContext = ReviewAppContext()
    fun readFileFromAssets(name: String) = "#!/bin/sh\n%ACTION_INSTALL\n%UNMOUNT_SD"
        fun writeStringToExternalFileDir(content: String, filename: String): String {
        val externalFilesDir = appContext.getExternalFilesDir(null)
            ?: throw IOException("externalFilesDir cannot be null.")
        val newFile = File(externalFilesDir.absolutePath + "/$filename")
        if (newFile.exists()) {
            newFile.delete()
        }
        newFile.createNewFile()
        newFile.writeBytes(content.toByteArray())
        return newFile.absolutePath
    }
    val files = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    var onClean: () -> Unit = { files.clear() }
    fun cleanTaskWorkspace(key: String, all: Boolean) { onClean() }
}

// The methods inserted below are copied verbatim, not hand-translated coordinator logic.
// This is NOT an Android ViewModel/lifecycle/Binder integration test.
class HomeWiringHarness {
    /** Exceptions that escaped to the scope (would crash the app): every probe expects none. */
    val uncaught = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
    val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })
    val session = Session()
    val storageManager = WorkspaceBoundary()
    private val tag = "ViewModel-extracted-wiring"
    private val application = Any()
    private val taskGate = InstallTaskGate()
    private val _uiState = MutableStateFlow(HomeUiState())
    @Volatile private var dsuSubmission: DsuSubmissionStep? = null
    @Volatile private var taskThatUnmounted: Long? = null
    private var logger: LogcatDiagnostic? = null
    var persistHook: (PendingRemount?) -> Unit = {}
    private val sdCardRemounter = SdCardRemounter(object : PendingRemountStore {
        var record: PendingRemount? = null
        override suspend fun read() = record
        override suspend fun write(record: PendingRemount?) { persistHook(record); this.record = record }
    })
    var bootIdHook: () -> String? = { "7" }
    private fun currentBootId(): String? = bootIdHook()
    private fun dismissSheet() {}
    fun newTask(): Long? = beginTask()
    fun beginSubmission(id: Long) = startPrivilegedInstallation(id)
    fun preparationStopped(id: Long) = onPreparationStepUnsettled(id, CancellationException("review cancellation"))
    fun busy(): Boolean = taskGate.isBusy()
    fun currentId(): Long? = taskGate.current()?.id
    fun retryJob(id: Long) = taskGate.retryJobOf(id)!!
    fun submissionJob() = dsuSubmission!!.job
    suspend fun pendingRecord() = sdCardRemounter.pending()
    fun taskJob(id: Long) = taskGate.jobOf(id)!!
    fun card() = _uiState.value.installationCard
    fun cardError() = _uiState.value.installationCard.errorText
    fun cardStep() = _uiState.value.installationCard.installationStep
    var prefHook: (String) -> Boolean = { false }
    private fun readBoolPref(key: String) = prefHook(key)
    private fun generateUsefulLogInfo() = "review log context"
    private fun onInstallationProgressUpdate(p: Float, part: String) {}
    private fun onLogLineReceived() {}
    private fun onTaskConfirmedSuccess(id: Long) {}
    fun hasHanded(id: Long) = taskGate.hasHandedToSystem(id)
    private fun onStepUpdate(step: InstallationStep) {}
    private fun onPreparationProgressUpdate(p: Float) {}
    private fun startDSUInstallation(id: Long) { error("unused root boundary") }
    // Property of HomeViewModel (not a method, so declared here); the factory is extracted.
    private val retryEntry: RetryEntry by lazy { createRetryEntry() }
    fun stopHarness() { taskGate.current()?.job?.cancel(); viewModelScope.cancel() }
    private fun startLogging(id: Long) {
        // Only for the current, non-cancelled task, on that task's own Job.
        val job = taskGate.jobOf(id)
        if (job == null || !taskGate.acceptsUpdates(id)) return
        logger?.destroy()
        val newLogger = LogcatDiagnostic(
            onInstallationError = { step, text -> onTaskFailed(id, step, text, systemReported = true) },
            onStepUpdate = { step -> ifAccepts(id) { onStepUpdate(step) } },
            onInstallationProgressUpdate = { p, part -> ifAccepts(id) { onInstallationProgressUpdate(p, part) } },
            onInstallationSuccess = { onTaskConfirmedSuccess(id) },
            onLogLineReceived = { if (taskGate.isCurrent(id)) onLogLineReceived() },
        )
        logger = newLogger
        viewModelScope.launch(Dispatchers.IO + job) {
            newLogger.shouldLogEverything = readBoolPref(AppPrefs.FULL_LOGCAT_LOGGING)
            newLogger.startLogging(generateUsefulLogInfo())
        }
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

    private fun querySystemDsuState(service: IPrivilegedService): SystemDsuState {
        // getInstallationProgress needs MANAGE_DYNAMIC_SYSTEM: only meaningful as root.
        if (!session.isRoot()) return SystemDsuState.UNKNOWN
        return runCatching {
            if (service.installationProgress.status == 1) SystemDsuState.INSTALLING else SystemDsuState.IDLE
        }.getOrDefault(SystemDsuState.UNKNOWN)
    }

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

    private fun onAcknowledgeUnconfirmed() {
        taskThatUnmounted = null
        taskGate.current()?.let { taskGate.finish(it.id) }
        resetInstallationCard()
    }

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

    fun onInstallationCardAction(action: InstallationCardAction) {
        when (action) {
            InstallationCardAction.ACK_CLEANUP_INCOMPLETE -> onAcknowledgeCleanupIncomplete()
            InstallationCardAction.ACK_UNCONFIRMED -> onAcknowledgeUnconfirmed()
            InstallationCardAction.RESTORE_STORAGE -> onRestoreStorage()
            InstallationCardAction.RESTORE_STORAGE_LATER ->
                updateInstallationCard { it.copy(installationStep = InstallationStep.NOT_INSTALLING) }
        }
    }

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

    private fun remountSdCardsIfOwnedBy(id: Long, reason: String) {
        if (taskThatUnmounted != id) return
        taskThatUnmounted = null
        viewModelScope.launch(Dispatchers.IO) {
            sdCardRemounter.remountPendingIfAny(reason)
            refreshStorageRestorePending()
        }
    }

    private fun createRetryEntry(): RetryEntry {
        return RetryEntry(
            gate = taskGate,
            scope = viewModelScope,
            io = Dispatchers.IO,
            beginTask = { beginTask() },
            submissionInFlight = { id -> dsuSubmission?.takeIf { it.taskId == id }?.job?.isCompleted == false },
            onAccepted = { id -> ifAccepts(id) { updateInstallationCard { it.copy(installationStep = InstallationStep.PROCESSING) } } },
            startInstallation = { id -> startInstallation(id, inStep = true) },
            onPreparationEnded = { id, cause, inStep -> onPreparationStepUnsettled(id, cause, inStep) },
            onTaskFailed = { id, e ->
                Log.e(tag, "Retry failed", e)
                onTaskFailed(id, InstallationStep.ERROR, e.message ?: e.toString())
            },
            log = { Log.d(tag, it) },
        )
    }

    fun onClickRetryInstallation() {
        retryEntry.retry()
    }

    fun onClickUnmountSdCardAndRetry() {
        retryEntry.retry { session.preferences.isUnmountSdCard = true }
    }

    fun onClickSetSeLinuxPermissive() {
        retryEntry.retry {
            val result = Shell.cmd("setenforce 0").exec()
            if (!result.isSuccess) Log.w(tag, "setenforce 0 failed: ${result.err}")
            delay(5000) // cancellable
        }
    }

    private fun setupAdbInstallation(id: Long, inStep: Boolean = false) {
        // Runs synchronously in the preparation step while still PREPARING: a write failure
        // (also after a cancel) is reported by that step's onErrorAfterSettle.
        AdbInstallationHandler(storageManager, session).generate { scriptPath ->
            Log.d(tag, "Installation script generated: $scriptPath")
            // From the app's point of view the task ends here: the user runs the script.
            // A cancel requested while the script was being written wins.
            if (taskGate.finishIfNotCancelling(id)) {
                session.installationScriptPath = scriptPath
                resetInstallationCard()
                updateInstallationCard { it.copy(installationStep = InstallationStep.REQUIRES_ADB_CMD_TO_CONTINUE) }
            } else if (taskGate.isCancelling(id)) {
                onPreparationStepUnsettled(id, CancellationException("cancelled while generating the ADB script"), inStep)
            }
        }
    }

    fun onConfirmInstallationSheet() {
        dismissSheet()
        val id = beginTask() ?: return
        updateInstallationCard { it.copy(installationStep = InstallationStep.PROCESSING) }
        val job = taskGate.jobOf(id) ?: return
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
                if (taskGate.isOwnedByPreparation(id)) {
                    // Settled, but no backend took the task over (e.g. the ADB script could not
                    // be written, or a parameter read failed before DSU_APP): this step still
                    // owns the terminal outcome, also when a cancel arrived meanwhile.
                    onPreparationStepUnsettled(id, e, inStep = true)
                } else {
                    // ROOT_INSTALL / DSU_APP: a cancel is reported by that backend's path.
                    onTaskFailed(id, InstallationStep.ERROR, e.message ?: e.toString())
                }
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
                onCanceled = {
                    if (step.settle()) onPreparationStepUnsettled(id, CancellationException("preparation cancelled"), inStep = true)
                },
                onPreparationFinished = { if (step.settle()) onPreparationFinished(id, it) },
            ).invoke()
        }
    }

    private fun onPreparationFinished(id: Long, dsuInstallation: DSUInstallationSource) {
        Log.d(tag, "DSU preparation finished, result: $dsuInstallation")
        if (taskGate.isCancelling(id)) {
            // Only called from the preparation step (see onConfirmInstallationSheet).
            onPreparationStepUnsettled(id, CancellationException("cancelled after preparation"), inStep = true)
            return
        }
        if (!taskGate.isCurrent(id)) return
        session.dsuInstallation = dsuInstallation
        startInstallation(id, inStep = true)
    }

    private fun startInstallation(id: Long, inStep: Boolean = false) {
        Log.d(tag, "startInstallation(task $id), session: \n$session")
        ifAccepts(id) { updateInstallationCard { it.copy(installationStep = InstallationStep.PROCESSING) } }

        if (session.getOperationMode() == OperationMode.ADB) {
            setupAdbInstallation(id, inStep)
            return
        }

        if (session.preferences.useBuiltinInstaller && session.isRoot()) {
            startDSUInstallation(id)
            return
        }

        startPrivilegedInstallation(id)
    }

    private fun beginTask(): Long? {
        // Each task gets its own workspace directory (TaskWorkspaces), so the cleanup of one
        // task can never delete files of another.
        val id = taskGate.begin { TaskWorkspaces.taskKey(it, System.currentTimeMillis()) }
        if (id == null) {
            Log.w(tag, "Installation task still running or cleaning up: ${taskGate.current()}")
            return null
        }
        updateInstallationCard { it.copy(skippedImages = emptyList()) }
        return id
    }

    private inline fun ifAccepts(id: Long, block: () -> Unit) {
        if (taskGate.acceptsUpdates(id)) block() else Log.d(tag, "Dropping update of stale task $id")
    }

    private fun updateInstallationCard(update: (InstallationCardState) -> InstallationCardState) =
        _uiState.update { it.copy(installationCard = update(it.installationCard.copy())) }

    private fun onPreparationStepUnsettled(id: Long, cause: Throwable?, inStep: Boolean = false) {
        val taskKey = taskGate.workspaceOf(id) ?: return
        if (!taskGate.claimTerminal(id)) return
        fun report() {
            try {
                val cleanupError = runCatching { storageManager.cleanTaskWorkspace(taskKey, true) }.exceptionOrNull()
                if (cleanupError != null) Log.w(tag, "Workspace cleanup of task $id failed", cleanupError)
                val cleanupNote = cleanupError?.let { "Temporary files could not be removed: ${it.message ?: it}" }
                if (cause is CancellationException || taskGate.isCancelling(id)) {
                    // An error that happened after the cancel request is kept as detail.
                    val lateError = cause?.takeIf { it !is CancellationException }
                        ?.let { "Error while cancelling: ${it.message ?: it}" }
                    finishCancelWithOutcome(
                        id,
                        InstallOutcome(InstallationStep.ERROR_CANCELED, listOfNotNull(lateError, cleanupNote).joinToString("\n")),
                    )
                    return
                }
                // Unsupported/corrupt/truncated input, I/O, preference or storage errors. Finished
                // directly (not via onTaskFailed): a cancel that arrives now must not leave the
                // task unfinished, nobody else would report it.
                Log.e(tag, "Preparation failed", cause)
                val message = cause?.message ?: cause?.toString() ?: "Preparation ended without a result"
                if (!taskGate.finish(id)) return
                remountSdCardsIfOwnedBy(id, "preparation error")
                onInstallationError(InstallationStep.ERROR, listOfNotNull(message, cleanupNote).joinToString("\n"))
            } catch (e: Exception) {
                // Reporting itself failed: don't let it escape into the step / scope (it would
                // crash the app); the finally block below still releases the task.
                Log.e(tag, "Reporting the outcome of task $id failed", e)
            } finally {
                // Last resort (reporting itself threw): never keep the task locked.
                if (taskGate.finish(id)) {
                    Log.e(tag, "Task $id ended without a reported outcome")
                    updateInstallationCard {
                        it.copy(installationStep = InstallationStep.ERROR, errorText = "Installation ended unexpectedly")
                    }
                }
            }
        }
        if (inStep) report() else viewModelScope.launch(Dispatchers.IO) { report() }
    }

    private fun onTaskCancelledWithoutSystemChanges(id: Long) {
        finishCancelWithOutcome(id, InstallOutcome(InstallationStep.ERROR_CANCELED, ""))
    }

    private fun startPrivilegedInstallation(id: Long) {
        // The task's own Job: never a shared field a newer task may have replaced meanwhile.
        val job = taskGate.jobOf(id)
        if (job == null || !taskGate.acceptsUpdates(id)) {
            if (taskGate.isCancelling(id) && taskGate.isOwnedByPreparation(id)) {
                onPreparationStepUnsettled(id, CancellationException("cancelled before submission"))
            }
            return
        }
        val canTrackResult =
            session.isRoot() || OperationModeUtils.isReadLogsPermissionGranted(application)
        val unmountSdCard = session.preferences.isUnmountSdCard
        // Parameters are captured now (still in phase PREPARING: a cancel here is reported
        // below, when the phase switch is refused). The submission runs as a child of the
        // task's Job and re-checks the task's identity/cancel state before startActivity.
        val submission = DsuInstallationHandler(
            session = session,
            sdCardRemounter = sdCardRemounter,
            bootId = currentBootId(),
            isOwner = { taskGate.acceptsUpdates(id) },
        ).submission
        val stepJob = TaskSteps.launch(
            scope = viewModelScope,
            context = Dispatchers.IO + job,
            start = CoroutineStart.LAZY,
            onUnsettled = { cause -> onSubmissionStepUnsettled(id, cause, submission) },
        ) { step ->
            val result = submission.submit()
            // Kept for the whole task: a later retry can't undo this handoff.
            if (submission.hasReachedSystem) taskGate.markHandedToSystem(id)
            when (result) {
                DsuSubmission.Result.Submitted -> {
                    step.settle()
                    if (!canTrackResult) {
                        // Without READ_LOGS we cannot observe the DSU app: the request was submitted,
                        // but success can't be confirmed, so never report INSTALL_SUCCESS here.
                        ifAccepts(id) {
                            updateInstallationCard {
                                it.copy(installationStep = InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED, errorText = "")
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
        val dsuStep = DsuSubmissionStep(id, submission, stepJob)
        // Phase switch and step registration are one atomic gate operation: a concurrent
        // cancel either comes first (switch refused, the lazy step never runs and the task is
        // reported here) or sees DSU_APP together with this step and waits for it.
        val registered = taskGate.setPhase(id, InstallTaskGate.Phase.DSU_APP) {
            dsuSubmission = dsuStep
            if (unmountSdCard) taskThatUnmounted = id
        }
        if (!registered) {
            stepJob.cancel()
            if (taskGate.isCancelling(id) && taskGate.isOwnedByPreparation(id)) {
                onPreparationStepUnsettled(id, CancellationException("cancelled before submission"))
            }
            return
        }
        ifAccepts(id) {
            updateInstallationCard { it.copy(installationStep = InstallationStep.WAITING_USER_CONFIRMATION) }
        }
        stepJob.start()
        if (canTrackResult) {
            startLogging(id)
        }
    }

    fun onClickCancelInstallationButton() {
        dismissSheet()
        val task = taskGate.requestCancel()
        if (task == null) {
            // No task (e.g. ADB result card) -> nothing to stop. Already cancelling -> wait.
            if (!taskGate.isBusy()) resetInstallationCard()
            return
        }
        updateInstallationCard { it.copy(installationStep = InstallationStep.CANCELLING, errorText = "") }
        // The Job of the task being cancelled (captured atomically by requestCancel).
        task.job.cancel()
        when (task.phase) {
            // Preparation coroutine / root pipeline report their own cancellation result.
            InstallTaskGate.Phase.PREPARING, InstallTaskGate.Phase.ROOT_INSTALL -> Unit
            InstallTaskGate.Phase.DSU_APP -> cancelDsuAppInstallation(task.id)
        }
    }

    private fun onSubmissionStepUnsettled(id: Long, cause: Throwable?, submission: DsuSubmission) {
        if (taskGate.isCancelling(id)) return // the cancel path waits for this step and decides
        if (submission.hasReachedSystem) taskGate.markHandedToSystem(id)
        if (taskGate.hasHandedToSystem(id)) {
            // This request or an earlier one of the task reached the system: still unconfirmed.
            onTaskFailed(id, InstallationStep.ERROR, cause?.toString() ?: "Submission ended without a result")
        } else {
            onTaskCancelledWithoutSystemChanges(id)
        }
    }

    private fun cancelDsuAppInstallation(id: Long) {
        logger?.destroy()
        viewModelScope.launch(Dispatchers.IO) {
            // A retry step of this task (already cancelled with the task Job) may still be
            // registering a new request: wait until it stopped, then the registered request
            // (setPhase/registration is refused after the cancel) is the one to stop.
            taskGate.retryJobOf(id)?.let { it.cancel(); it.join() }
            val step = dsuSubmission?.takeIf { it.taskId == id }
            // Wait until the request can no longer unmount storage or open the DSU prompt.
            // An earlier request of this task that reached the system counts too: a retry
            // cancelled before its own handoff doesn't end the earlier system installation.
            val reachedSystem = (step?.cancelAndJoin() ?: false) || taskGate.hasHandedToSystem(id)
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

    private fun finishCancelWithOutcome(id: Long, outcome: InstallOutcome) {
        if (!taskGate.finish(id)) return
        session.dsuInstallation = DSUInstallationSource()
        if (outcome.step == InstallationStep.ERROR_CANCELED) {
            remountSdCardsIfOwnedBy(id, "installation cancelled")
        }
        updateInstallationCard { it.copy(installationStep = outcome.step, errorText = outcome.detail) }
    }

    private fun onTaskFailed(id: Long, error: InstallationStep, errorContent: String, systemReported: Boolean = false) {
        if (taskGate.isCancelling(id)) return // the cancel path reports the terminal state
        val handedToSystem = !systemReported && taskGate.hasHandedToSystem(id)
        if (!taskGate.finish(id)) return
        if (handedToSystem) {
            Log.w(tag, "Task $id: request failed after an earlier handoff to the system: $errorContent")
            if (taskThatUnmounted == id) taskThatUnmounted = null // no automatic remount
            updateInstallationCard {
                it.copy(installationStep = InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED, errorText = errorContent)
            }
            refreshStorageRestorePending()
            return
        }
        remountSdCardsIfOwnedBy(id, "installation error: $error")
        onInstallationError(error, errorContent)
    }

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
}
