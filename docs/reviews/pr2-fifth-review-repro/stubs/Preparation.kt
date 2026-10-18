package vegabobo.dsusideloader.preparation
import kotlinx.coroutines.Job
import review3.WorkspaceBoundary
import vegabobo.dsusideloader.model.*
// Substitute file I/O only. Production onConfirm callback and all handoff methods are extracted verbatim.
class Preparation(storageManager: WorkspaceBoundary, session: Session, job: Job, taskKey: String,
    onStepUpdate: (InstallationStep) -> Unit, onPreparationProgressUpdate: (Float) -> Unit,
    onCanceled: () -> Unit, val onPreparationFinished: (DSUInstallationSource) -> Unit) {
    fun invoke() { onPreparationFinished(DSUInstallationSource()) }
}
