package vegabobo.dsusideloader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory model of the SAF workspace folder used by [TaskWorkspaces]. */
class MemDir(
    override val name: String,
    override val isDirectory: Boolean = true,
    private val parent: MemDir? = null,
) : WorkspaceRoot {
    val children = mutableListOf<MemDir>()
    var failDeletes = false

    override fun list(): List<WorkspaceEntry> = synchronized(this) { children.toList() }
    override fun delete(): Boolean {
        if (failDeletes) return false
        val p = parent ?: return false
        synchronized(p) { p.children.remove(this) }
        return true
    }
    override fun findOrCreateDirectory(name: String): MemDir = synchronized(this) {
        children.firstOrNull { it.name == name && it.isDirectory } ?: MemDir(name, true, this).also { children += it }
    }
    fun file(name: String): MemDir = synchronized(this) { MemDir(name, false, this).also { children += it } }
    fun names(): List<String> = synchronized(this) { children.map { it.name } }
    fun dir(name: String): MemDir? = synchronized(this) { children.firstOrNull { it.name == name } }
}

class TaskWorkspacesTest {

    @Test
    fun taskKeysAreUniquePerTask() {
        assertNotEquals(TaskWorkspaces.taskKey(1, 100), TaskWorkspaces.taskKey(2, 100))
        assertNotEquals(TaskWorkspaces.taskKey(1, 100), TaskWorkspaces.taskKey(1, 101))
        assertTrue(TaskWorkspaces.isTaskKey(TaskWorkspaces.taskKey(1, 100)))
    }

    @Test
    fun cleaningTaskADoesNotTouchTaskBFiles() {
        val root = MemDir("workspace_dsuhelper")
        val a = TaskWorkspaces.taskKey(1, 10)
        val b = TaskWorkspaces.taskKey(2, 20)
        root.findOrCreateDirectory(a).file("partial.img")
        root.findOrCreateDirectory(b).file("new-task-image.img")

        TaskWorkspaces.clean(root, a, deleteAlsoGz = true)

        assertFalse(root.names().contains(a))
        assertEquals(listOf("new-task-image.img"), root.dir(b)!!.names())
    }

    @Test
    fun cleanWithoutGzKeepsPreparedImageOfTheTask() {
        val root = MemDir("ws")
        val a = TaskWorkspaces.taskKey(1, 10)
        root.findOrCreateDirectory(a).apply { file("system.img"); file("system.img.gz") }
        TaskWorkspaces.clean(root, a, deleteAlsoGz = false)
        assertEquals(listOf("system.img.gz"), root.dir(a)!!.names())
    }

    @Test
    fun failedDeleteIsReported() {
        val root = MemDir("ws")
        val a = TaskWorkspaces.taskKey(1, 10)
        root.findOrCreateDirectory(a).file("locked.img").failDeletes = true
        val error = runCatching { TaskWorkspaces.clean(root, a, deleteAlsoGz = true) }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertTrue(error!!.message!!.contains("locked.img"))
    }

    @Test
    fun cleanRefusesNonTaskDirectories() {
        val root = MemDir("ws")
        assertTrue(runCatching { TaskWorkspaces.clean(root, "", true) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun staleEntriesAreRemovedExceptCurrentTask() {
        val root = MemDir("ws")
        val old = TaskWorkspaces.taskKey(1, 10)
        val cur = TaskWorkspaces.taskKey(2, 20)
        root.file("legacy-flat.img.gz")
        root.findOrCreateDirectory(old).file("old.img.gz")
        root.findOrCreateDirectory(cur).file("current.img")
        assertEquals(emptyList<String>(), TaskWorkspaces.removeAllExcept(root, cur))
        assertEquals(listOf(cur), root.names())
        assertEquals(listOf("current.img"), root.dir(cur)!!.names())
    }
}
