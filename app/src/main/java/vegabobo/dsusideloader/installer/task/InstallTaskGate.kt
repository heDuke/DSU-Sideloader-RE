package vegabobo.dsusideloader.installer.task

import kotlinx.coroutines.Job

/**
 * Minimal installation task mutex (not a full engine): at most one task exists, each task
 * has an id, and callbacks of a task that is no longer current are ignored.
 *
 * A task stays current through cancellation and cleanup; only [finish] (called once the
 * terminal result, including cleanup, is known) releases it, so a new installation can't
 * start while the previous one is still aborting/removing.
 *
 * Each task owns its [Job] and workspace directory key: code working for task `id` must get
 * them via [jobOf]/[workspaceOf] (null once the task is no longer current) and never through
 * a shared, mutable "current job" field that a later task could have replaced.
 */
class InstallTaskGate {

    enum class Phase { PREPARING, ROOT_INSTALL, DSU_APP }

    data class Task(
        val id: Long,
        val phase: Phase,
        val cancelling: Boolean,
        val job: Job = Job(),
        val workspaceKey: String = "",
        /** Set by [claimTerminal]: a terminal outcome of this task is being produced. */
        val terminating: Boolean = false,
        /** The task's retry step (see [attachRetry]), if one was started. */
        val retryJob: Job? = null,
    )

    private var nextId = 1L
    private var current: Task? = null

    /** @return id of the new task, or null while another task (incl. cleanup) is running. */
    @Synchronized
    fun begin(phase: Phase = Phase.PREPARING, workspaceKey: (id: Long) -> String = { "" }): Long? {
        if (current != null) return null
        val id = nextId++
        val task = Task(id, phase, cancelling = false, job = Job(), workspaceKey = workspaceKey(id))
        current = task
        return task.id
    }

    @Synchronized
    fun current(): Task? = current

    @Synchronized
    fun isBusy(): Boolean = current != null

    @Synchronized
    fun isCurrent(id: Long): Boolean = current?.id == id

    /** True if [id] is current and not being cancelled: progress updates may be shown. */
    @Synchronized
    fun acceptsUpdates(id: Long): Boolean = current?.let { it.id == id && !it.cancelling } ?: false

    /** The Job of task [id], or null if [id] is not the current task. */
    @Synchronized
    fun jobOf(id: Long): Job? = current?.takeIf { it.id == id }?.job

    /** The phase of task [id], or null if [id] is not the current task. */
    @Synchronized
    fun phaseOf(id: Long): Phase? = current?.takeIf { it.id == id }?.phase

    /**
     * True if nothing has taken over the terminal outcome of task [id] from its preparation
     * step: the task is current and still [Phase.PREPARING] (no root pipeline / DSU app step
     * was started). A failure or cancel in that state must be reported by the preparation
     * side itself, whatever the cancel state is.
     */
    @Synchronized
    fun isOwnedByPreparation(id: Long): Boolean = phaseOf(id) == Phase.PREPARING

    /**
     * Claims the (asynchronous) terminal outcome of task [id]. @return true for the first
     * caller only, while [id] is current: a second, concurrent terminal path of the same task
     * returns false and must do nothing.
     */
    @Synchronized
    fun claimTerminal(id: Long): Boolean {
        val task = current ?: return false
        if (task.id != id || task.terminating) return false
        current = task.copy(terminating = true)
        return true
    }

    /**
     * Releases [id] unless it is being cancelled (used for a successful end, e.g. the ADB
     * script was generated: a cancel requested meanwhile wins and is reported instead).
     */
    @Synchronized
    fun finishIfNotCancelling(id: Long): Boolean {
        if (current?.let { it.id == id && !it.cancelling } != true) return false
        return finish(id)
    }

    /**
     * Registers the retry step of task [id], created by [create] under the gate lock, if [id]
     * is current, not cancelling/terminating and no earlier retry step of it is still running.
     * Atomic against [requestCancel]: a cancel either comes first (null, nothing created) or
     * finds the step via [retryJobOf].
     * @return the created (not yet started) job, or null when the retry must be ignored.
     */
    @Synchronized
    fun attachRetry(id: Long, create: () -> Job): Job? {
        val task = current ?: return null
        if (task.id != id || task.cancelling || task.terminating) return null
        if (task.retryJob?.isCompleted == false) return null
        val job = create()
        current = task.copy(retryJob = job)
        return job
    }

    /** The retry step of task [id] (running or finished), or null. */
    @Synchronized
    fun retryJobOf(id: Long): Job? = current?.takeIf { it.id == id }?.retryJob

    /** The workspace directory key of task [id], or null if [id] is not the current task. */
    @Synchronized
    fun workspaceOf(id: Long): String? = current?.takeIf { it.id == id }?.workspaceKey

    /**
     * Switches task [id] to [phase] if it is current and not cancelling. [onSwitched] runs
     * under the gate lock, atomically with the switch: a concurrent [requestCancel] either
     * happens before (switch refused) or observes everything [onSwitched] published.
     */
    @Synchronized
    fun setPhase(id: Long, phase: Phase, onSwitched: () -> Unit = {}): Boolean {
        val task = current ?: return false
        if (task.id != id || task.cancelling) return false
        current = task.copy(phase = phase)
        onSwitched()
        return true
    }

    /**
     * Marks the current task as cancelling.
     * @return the task as it was before (to know which phase must be stopped), or null if
     * there is no task or it is already cancelling.
     */
    @Synchronized
    fun requestCancel(): Task? {
        val task = current ?: return null
        if (task.cancelling) return null
        current = task.copy(cancelling = true)
        return task
    }

    @Synchronized
    fun isCancelling(id: Long): Boolean = current?.let { it.id == id && it.cancelling } ?: false

    /** Releases [id]. @return false for a stale task (its terminal callback must be ignored). */
    @Synchronized
    fun finish(id: Long): Boolean {
        val task = current ?: return false
        if (task.id != id) return false
        current = null
        // Nothing may keep running on behalf of a finished task.
        task.job.cancel()
        return true
    }
}
