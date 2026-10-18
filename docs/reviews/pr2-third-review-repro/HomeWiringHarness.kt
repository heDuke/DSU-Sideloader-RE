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

class WorkspaceBoundary {
    val files = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    var onClean: () -> Unit = { files.clear() }
    fun cleanWorkspaceFolder(all: Boolean) { onClean() }
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
    var installationJob: Job = Job()
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
    fun stopHarness() { installationJob.cancel(); viewModelScope.cancel() }
    private fun beginTask(): Long? {
        val id = taskGate.begin()
        if (id == null) {
            Log.w(tag, "Installation task still running or cleaning up: ${taskGate.current()}")
            return null
        }
        installationJob = Job()
        updateInstallationCard { it.copy(skippedImages = emptyList()) }
        return id
    }

    private inline fun ifAccepts(id: Long, block: () -> Unit) {
        if (taskGate.acceptsUpdates(id)) block() else Log.d(tag, "Dropping update of stale task $id")
    }

    private fun updateInstallationCard(update: (InstallationCardState) -> InstallationCardState) =
        _uiState.update { it.copy(installationCard = update(it.installationCard.copy())) }

    private fun onPreparationStepUnsettled(id: Long, cause: Throwable?) {
        // Don't leave partially decompressed/compressed files behind (off the caller thread:
        // a cancel-before-start completes synchronously in the cancelling thread).
        viewModelScope.launch(Dispatchers.IO) { runCatching { storageManager.cleanWorkspaceFolder(true) } }
        if (cause is CancellationException || taskGate.isCancelling(id)) {
            onTaskCancelledWithoutSystemChanges(id)
            return
        }
        // Unsupported/corrupt/truncated input, I/O, preference or storage errors.
        Log.e(tag, "Preparation failed", cause)
        onTaskFailed(id, InstallationStep.ERROR, cause?.message ?: cause?.toString() ?: "Preparation ended without a result")
    }

    private fun onTaskCancelledWithoutSystemChanges(id: Long) {
        finishCancelWithOutcome(id, InstallOutcome(InstallationStep.ERROR_CANCELED, ""))
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
