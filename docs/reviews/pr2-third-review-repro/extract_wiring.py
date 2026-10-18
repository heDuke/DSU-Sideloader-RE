"""Extract selected ViewModel methods verbatim; only external framework boundaries are substituted."""
from pathlib import Path
import re
import sys

repo = Path(sys.argv[1]).resolve()
here = Path(__file__).resolve().parent
source = (repo / 'app/src/main/java/vegabobo/dsusideloader/ui/screen/home/HomeViewModel.kt').read_text()
names = [
    'beginTask', 'ifAccepts', 'updateInstallationCard',
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
'''
out = prefix + '\n\n'.join(method(name) for name in names) + '\n}\n'
(here / 'HomeWiringHarness.kt').write_text(out)
print('Extracted production methods:', ', '.join(names))
