package review3

import android.util.Log
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
    val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val session = Session()
    val storageManager = WorkspaceBoundary()
    private val tag = "ViewModel-extracted-wiring"
    private val application = Any()
    private val taskGate = InstallTaskGate()
    private val _uiState = MutableStateFlow(HomeUiState())
    @Volatile private var dsuSubmission: DsuSubmissionStep? = null
    @Volatile private var taskThatUnmounted: Long? = null
    private class Logger { fun destroy() {} }
    private var logger: Logger? = null
    private val sdCardRemounter = SdCardRemounter(object : PendingRemountStore {
        var record: PendingRemount? = null
        override suspend fun read() = record
        override suspend fun write(record: PendingRemount?) { this.record = record }
    })
    var bootIdHook: () -> String? = { "7" }
    private fun currentBootId(): String? = bootIdHook()
    private fun startLogging(id: Long) {}
    private fun remountSdCardsIfOwnedBy(id: Long, reason: String) {}
    private fun dismissSheet() {}
    private fun resetInstallationCard() { _uiState.value = HomeUiState() }
    fun newTask(): Long? = beginTask()
    fun beginSubmission(id: Long) = startPrivilegedInstallation(id)
    fun preparationStopped(id: Long) = onPreparationStepUnsettled(id, CancellationException("review cancellation"))
    fun busy(): Boolean = taskGate.isBusy()
    fun currentId(): Long? = taskGate.current()?.id
    fun taskJob(id: Long) = taskGate.jobOf(id)!!
    fun cardStep() = _uiState.value.installationCard.installationStep
    private fun readBoolPref(key: String) = false
    private fun onStepUpdate(step: InstallationStep) {}
    private fun onPreparationProgressUpdate(p: Float) {}
    private fun startDSUInstallation(id: Long) { error("unused root boundary") }
    fun stopHarness() { taskGate.current()?.job?.cancel(); viewModelScope.cancel() }
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

    private fun onTaskCancelledWithoutSystemChanges(id: Long) {
        finishCancelWithOutcome(id, InstallOutcome(InstallationStep.ERROR_CANCELED, ""))
    }

    private fun startPrivilegedInstallation(id: Long) {
        // The task's own Job: never a shared field a newer task may have replaced meanwhile.
        val job = taskGate.jobOf(id)
        if (job == null || !taskGate.acceptsUpdates(id)) {
            if (taskGate.isCancelling(id)) onTaskCancelledWithoutSystemChanges(id)
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
            if (taskGate.isCancelling(id)) onTaskCancelledWithoutSystemChanges(id)
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
