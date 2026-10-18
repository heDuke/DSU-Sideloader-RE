"""Extract selected ViewModel methods verbatim; only external framework boundaries are substituted."""
from pathlib import Path
import re
import sys

repo = Path(sys.argv[1]).resolve()
here = Path(__file__).resolve().parent
source = (repo / 'app/src/main/java/vegabobo/dsusideloader/ui/screen/home/HomeViewModel.kt').read_text()
names = [
    # Retry entries (fix/retry-entry-guard): currentOrNewTask moved into RetryEntry (TaskStep.kt).
    'startLogging', 'refreshStorageRestorePending', 'showStorageRestore', 'querySystemDsuState', 'onRestoreStorage', 'onAcknowledgeUnconfirmed', 'onAcknowledgeCleanupIncomplete', 'onInstallationCardAction', 'resetInstallationCard', 'remountSdCardsIfOwnedBy', 'createRetryEntry', 'onClickRetryInstallation', 'onClickUnmountSdCardAndRetry', 'onClickSetSeLinuxPermissive',
    'setupAdbInstallation', 'onConfirmInstallationSheet', 'onPreparationFinished', 'startInstallation', 'beginTask', 'ifAccepts', 'updateInstallationCard',
    'onPreparationStepUnsettled', 'onTaskCancelledWithoutSystemChanges',
    'startPrivilegedInstallation', 'onClickCancelInstallationButton',
    'onSubmissionStepUnsettled', 'cancelDsuAppInstallation',
    'finishCancelWithOutcome', 'onTaskFailed', 'onInstallationError',
]

def method(name):
    match = re.search(r'^    (?:(?:private|inline)\s+)*fun '+re.escape(name)+r'\b', source, re.M)
    if not match:
        raise ValueError('Missing production method: '+name)
    start = source.index('{', match.start())
    level = 0
    for end in range(start, len(source)):
        if source[end] == '{': level += 1
        if source[end] == '}': level -= 1
        if level == 0:
            return source[match.start():end+1]
    raise ValueError('Unbalanced production method: '+name)

prefix = '''package review3

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
    fun readFileFromAssets(name: String) = "#!/bin/sh\\n%ACTION_INSTALL\\n%UNMOUNT_SD"
    __STORAGE_WRITE_METHOD__
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
'''
storageSource = (repo / 'app/src/main/java/vegabobo/dsusideloader/core/StorageManager.kt').read_text()
def extract_storage():
    global source
    saved = source
    source = storageSource
    try: return method('writeStringToExternalFileDir')
    finally: source = saved
prefix = prefix.replace('__STORAGE_WRITE_METHOD__', extract_storage())
out = prefix + '\n\n'.join(method(name) for name in names) + '\n}\n'
(here / 'HomeWiringHarness.kt').write_text(out)
print('Extracted production methods:', ', '.join(names))
