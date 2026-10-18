package vegabobo.dsusideloader.installer.task

/**
 * Minimal installation task mutex (not a full engine): at most one task exists, each task
 * has an id, and callbacks of a task that is no longer current are ignored.
 *
 * A task stays current through cancellation and cleanup; only [finish] (called once the
 * terminal result, including cleanup, is known) releases it, so a new installation can't
 * start while the previous one is still aborting/removing.
 */
class InstallTaskGate {

    enum class Phase { PREPARING, ROOT_INSTALL, DSU_APP }

    data class Task(val id: Long, val phase: Phase, val cancelling: Boolean)

    private var nextId = 1L
    private var current: Task? = null

    /** @return id of the new task, or null while another task (incl. cleanup) is running. */
    @Synchronized
    fun begin(phase: Phase = Phase.PREPARING): Long? {
        if (current != null) return null
        val task = Task(nextId++, phase, cancelling = false)
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

    @Synchronized
    fun setPhase(id: Long, phase: Phase): Boolean {
        val task = current ?: return false
        if (task.id != id || task.cancelling) return false
        current = task.copy(phase = phase)
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
        if (current?.id != id) return false
        current = null
        return true
    }
}
