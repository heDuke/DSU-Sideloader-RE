package vegabobo.dsusideloader.core

/** Minimal view of a workspace directory entry (abstracted from SAF DocumentFile for tests). */
interface WorkspaceEntry {
    val name: String
    val isDirectory: Boolean
    fun list(): List<WorkspaceEntry>
    fun delete(): Boolean
}

/** A workspace directory in which sub-directories can be found or created. */
interface WorkspaceRoot : WorkspaceEntry {
    fun findOrCreateDirectory(name: String): WorkspaceEntry
}

/**
 * Per-task layout of the shared workspace folder.
 *
 * Every installation task writes only into its own sub-directory ([taskKey]); cleaning a task
 * touches only that directory, so a late cleanup of task A can never delete files of task B.
 * Leftovers of previous tasks (and files of the old flat layout) are removed by
 * [removeAllExcept] when a new task starts preparing; the task gate guarantees that no other
 * task is running at that point.
 */
object TaskWorkspaces {

    private const val PREFIX = "task-"

    /** Directory name of task [taskId]; [nonce] keeps names unique across app restarts. */
    fun taskKey(taskId: Long, nonce: Long): String = "$PREFIX$taskId-$nonce"

    fun isTaskKey(name: String): Boolean = name.startsWith(PREFIX)

    /**
     * Deletes the files of task [key] only. With [deleteAlsoGz] the task directory itself is
     * removed too; otherwise prepared `.gz` images (still needed by the DSU app) are kept.
     * @throws java.io.IOException if an entry could not be deleted.
     */
    fun clean(root: WorkspaceRoot, key: String, deleteAlsoGz: Boolean) {
        require(isTaskKey(key)) { "Not a task workspace: $key" }
        val dir = root.list().firstOrNull { it.isDirectory && it.name == key } ?: return
        val failed = mutableListOf<String>()
        for (entry in dir.list()) {
            if (deleteAlsoGz || !entry.name.endsWith("gz")) {
                if (!entry.delete()) failed += entry.name
            }
        }
        if (deleteAlsoGz && failed.isEmpty() && !dir.delete()) failed += key
        if (failed.isNotEmpty()) throw java.io.IOException("Could not delete from workspace: $failed")
    }

    /**
     * Removes everything in the workspace except the directory of task [keepKey]
     * (leftovers of earlier tasks and of the old flat layout).
     * @return names of entries that could not be deleted (best effort, like the old wipe).
     */
    fun removeAllExcept(root: WorkspaceRoot, keepKey: String): List<String> =
        root.list().filter { it.name != keepKey }.filterNot { it.delete() }.map { it.name }
}
