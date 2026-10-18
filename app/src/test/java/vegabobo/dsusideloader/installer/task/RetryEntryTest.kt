package vegabobo.dsusideloader.installer.task

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vegabobo.dsusideloader.core.MemDir
import vegabobo.dsusideloader.core.TaskWorkspaces

/**
 * Retry entries (HomeViewModel.onClickRetryInstallation / onClickUnmountSdCardAndRetry /
 * onClickSetSeLinuxPermissive -> [RetryEntry]). The production [RetryEntry], gate, step launcher
 * and workspace layout run unchanged; the ViewModel callbacks it is wired to
 * (HomeViewModel.createRetryEntry) are modelled with the same rules: onPreparationStepUnsettled
 * (claim, task workspace cleanup, release in finally), startInstallation for ADB (script write +
 * finishIfNotCancelling) and the DSU app (boot id read, atomic setPhase + registration, refused
 * -> preparation side only while PREPARING), onTaskFailed (ignores cancelling tasks) and the
 * DSU_APP cancel path (joins the retry step, then the request, then reports).
 *
 * Every coroutine runs in a scope whose exception handler records escaping exceptions; each
 * test asserts that list (no unhandled exception may leak to a worker thread).
 */
class RetryEntryTest {

    private val models = mutableListOf<Model>()

    @After
    fun noUnhandledExceptions() {
        for (m in models) assertTrue("unhandled coroutine exceptions: ${m.uncaught}", m.uncaught.isEmpty())
    }

    private fun model() = Model().also { models += it }

    class Model {
        val gate = InstallTaskGate()
        val uncaught: MutableList<Throwable> = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })
        val root = MemDir("workspace_dsuhelper")
        val terminals: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

        @Volatile var card = "IDLE"

        @Volatile var detail = ""

        @Volatile var adbMode = true

        /** ADB script write (writeStringToExternalFileDir); DSU mode: boot id read. */
        var boundary: () -> String = { "/ext/install.sh" }
        var cleanup: (String) -> Unit = { key -> TaskWorkspaces.clean(root, key, deleteAlsoGz = true) }

        /** The published DSU app request (HomeViewModel.dsuSubmission). */
        @Volatile var submission: Pair<Long, Job>? = null
        val runningSubmissions = AtomicInteger()
        val maxParallelSubmissions = AtomicInteger()
        var submissionBody: suspend () -> Unit = { delay(50) }

        private fun terminal(step: String, text: String = "") {
            terminals += step
            card = step
            detail = text
        }

        fun beginTask(): Long? = gate.begin { TaskWorkspaces.taskKey(it, 1) }?.also {
            root.findOrCreateDirectory(gate.workspaceOf(it)!!).file("prepared.img.gz")
        }

        /** HomeViewModel.onPreparationStepUnsettled */
        fun onPreparationStepUnsettled(id: Long, cause: Throwable?, inStep: Boolean) {
            val key = gate.workspaceOf(id) ?: return
            if (!gate.claimTerminal(id)) return
            fun report() {
                try {
                    val cleanupError = runCatching { cleanup(key) }.exceptionOrNull()
                    events += "cleanup-done"
                    val note = cleanupError?.let { "Temporary files could not be removed: ${it.message}" }
                    if (cause is CancellationException || gate.isCancelling(id)) {
                        val late = cause?.takeIf { it !is CancellationException }?.let { "Error while cancelling: ${it.message}" }
                        if (gate.finish(id)) terminal("ERROR_CANCELED", listOfNotNull(late, note).joinToString("\n"))
                        return
                    }
                    if (gate.finish(id)) terminal("ERROR", listOfNotNull(cause?.message, note).joinToString("\n"))
                } catch (e: Exception) {
                    events += "report-failed"
                } finally {
                    if (gate.finish(id)) terminal("ERROR", "Installation ended unexpectedly")
                }
            }
            if (inStep) report() else scope.launch(Dispatchers.IO) { report() }
        }

        /** HomeViewModel.onTaskFailed */
        fun onTaskFailed(id: Long, e: Throwable) {
            if (gate.isCancelling(id)) return
            if (gate.finish(id)) terminal("ERROR", e.message ?: e.toString())
        }

        /** HomeViewModel.startInstallation(id, inStep = true) */
        fun startInstallation(id: Long) {
            if (adbMode) {
                val path = boundary()
                if (gate.finishIfNotCancelling(id)) {
                    terminal("REQUIRES_ADB_CMD_TO_CONTINUE", path)
                } else if (gate.isCancelling(id)) {
                    onPreparationStepUnsettled(id, CancellationException("cancelled while generating"), true)
                }
                return
            }
            // startPrivilegedInstallation
            val job = gate.jobOf(id)
            if (job == null || !gate.acceptsUpdates(id)) {
                if (gate.isCancelling(id) && gate.isOwnedByPreparation(id)) {
                    onPreparationStepUnsettled(id, CancellationException("cancelled before submission"), false)
                }
                return
            }
            boundary() // currentBootId()
            val stepJob = scope.launch(Dispatchers.IO + job, CoroutineStart.LAZY) {
                val now = runningSubmissions.incrementAndGet()
                maxParallelSubmissions.accumulateAndGet(now) { a, b -> maxOf(a, b) }
                events += "submission-start-$id"
                try {
                    submissionBody()
                } finally {
                    runningSubmissions.decrementAndGet()
                }
            }
            val registered = gate.setPhase(id, InstallTaskGate.Phase.DSU_APP) { submission = id to stepJob }
            if (!registered) {
                stepJob.cancel()
                if (gate.isCancelling(id) && gate.isOwnedByPreparation(id)) {
                    onPreparationStepUnsettled(id, CancellationException("cancelled before submission"), false)
                }
                return
            }
            card = "WAITING_USER_CONFIRMATION"
            stepJob.start()
        }

        /** HomeViewModel.onClickCancelInstallationButton + cancelDsuAppInstallation */
        fun cancel(): Job? {
            val task = gate.requestCancel() ?: return null
            card = "CANCELLING"
            task.job.cancel()
            if (task.phase != InstallTaskGate.Phase.DSU_APP) return null
            return scope.launch(Dispatchers.IO) {
                gate.retryJobOf(task.id)?.let { it.cancel(); it.join() }
                events += "cancel-path-after-retry"
                submission?.takeIf { it.first == task.id }?.second?.let { it.cancel(); it.join() }
                if (gate.finish(task.id)) terminal("ERROR_CANCELED")
            }
        }

        val entry = RetryEntry(
            gate = gate,
            scope = scope,
            io = Dispatchers.IO,
            beginTask = { beginTask() },
            submissionInFlight = { id -> submission?.takeIf { it.first == id }?.second?.isCompleted == false },
            onAccepted = { card = "PROCESSING" },
            startInstallation = { id -> startInstallation(id) },
            onPreparationEnded = { id, cause, inStep -> onPreparationStepUnsettled(id, cause, inStep) },
            onTaskFailed = { id, e -> onTaskFailed(id, e) },
        )

        suspend fun awaitIdle() = withTimeout(5000) { while (gate.isBusy()) delay(5) }

        suspend fun assertSingleTerminalAndNextTaskStarts(expected: String) {
            // Job.join() can return before the job's completion handlers (the TaskSteps
            // fallback) ran, so wait for the terminal, then give a duplicate time to show up.
            withTimeout(5000) { while (gate.isBusy() || card != expected) delay(5) }
            delay(50)
            assertFalse("task lock must be released", gate.isBusy())
            assertEquals(listOf(expected), terminals.toList())
            assertEquals(expected, card)
            assertNotNull("a next task must be admitted", gate.begin())
        }
    }

    private fun started(r: RetryEntry.Result): RetryEntry.Result.Started {
        assertTrue(r.toString(), r is RetryEntry.Result.Started)
        return r as RetryEntry.Result.Started
    }

    // ---- read / write failures ------------------------------------------------------------

    @Test
    fun retryWithAdbScriptWriteFailureIsAnErrorAndReleasesTask() = runBlocking {
        val m = model()
        m.boundary = { throw IOException("externalFilesDir cannot be null.") }
        val r = started(m.entry.retry())
        r.job.join()
        m.awaitIdle()
        m.assertSingleTerminalAndNextTaskStarts("ERROR")
        assertTrue(m.detail, m.detail.contains("externalFilesDir cannot be null."))
    }

    @Test
    fun retryWithParameterReadFailureBeforeDsuAppIsAnError() = runBlocking {
        val m = model()
        m.adbMode = false
        m.boundary = { throw SecurityException("Settings.Global read failed") }
        started(m.entry.retry()).job.join()
        m.awaitIdle()
        m.assertSingleTerminalAndNextTaskStarts("ERROR")
        assertEquals(listOf<String>(), m.events.filter { it.startsWith("submission") })
    }

    @Test
    fun failingRemedyBeforeStartIsAnError() = runBlocking {
        val m = model()
        started(m.entry.retry { throw IOException("setenforce: permission denied") }).job.join()
        m.awaitIdle()
        m.assertSingleTerminalAndNextTaskStarts("ERROR")
    }

    @Test
    fun successfulRetryEndsTheAdbTaskOnce() = runBlocking {
        val m = model()
        started(m.entry.retry()).job.join()
        m.assertSingleTerminalAndNextTaskStarts("REQUIRES_ADB_CMD_TO_CONTINUE")
    }

    // ---- cancel interleavings -----------------------------------------------------------

    @Test
    fun cancelDuringRemedyDelayCleansUpBeforeRelease() = runBlocking {
        val m = model()
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        m.cleanup = { key ->
            cleanupEntered.countDown()
            releaseCleanup.await(5, TimeUnit.SECONDS)
            TaskWorkspaces.clean(m.root, key, true)
        }
        val inRemedy = CompletableDeferred<Unit>()
        val r = started(m.entry.retry { inRemedy.complete(Unit); delay(5000) }) // SELinux remedy
        withTimeout(3000) { inRemedy.await() }
        assertNull(m.cancel())
        assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS))
        // Still locked while the task's cleanup runs.
        assertNull(m.gate.begin())
        releaseCleanup.countDown()
        r.job.join()
        m.awaitIdle()
        m.assertSingleTerminalAndNextTaskStarts("ERROR_CANCELED")
        assertTrue(m.root.names().none { it == TaskWorkspaces.taskKey(r.taskId, 1) })
    }

    @Test
    fun cancelThenScriptWriteFailsIsCancelledOnce() = runBlocking {
        val m = model()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        m.boundary = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("write failed")
        }
        val r = started(m.entry.retry())
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        m.cancel()
        release.countDown()
        r.job.join()
        m.assertSingleTerminalAndNextTaskStarts("ERROR_CANCELED")
        assertTrue(m.detail, m.detail.contains("Error while cancelling: write failed"))
    }

    @Test
    fun cancelAfterScriptWriteSucceededIsCancelledOnce() = runBlocking {
        val m = model()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        m.boundary = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            "/ext/install.sh"
        }
        val r = started(m.entry.retry())
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        m.cancel()
        release.countDown()
        r.job.join()
        m.awaitIdle()
        m.assertSingleTerminalAndNextTaskStarts("ERROR_CANCELED")
    }

    @Test
    fun cancelWithFailingCleanupReportsItAndReleases() = runBlocking {
        val m = model()
        m.cleanup = { throw IOException("SAF delete failed") }
        val r = started(m.entry.retry { delay(5000) })
        delay(50)
        m.cancel()
        r.job.join()
        m.awaitIdle()
        m.assertSingleTerminalAndNextTaskStarts("ERROR_CANCELED")
        assertTrue(m.detail, m.detail.contains("SAF delete failed"))
    }

    @Test
    fun cancelImmediatelyAfterTapEndsTheTaskOnce() = runBlocking {
        val m = model()
        val r = started(m.entry.retry { delay(5000) })
        m.cancel() // may hit the step before it ran or inside the remedy
        r.job.join()
        m.awaitIdle()
        m.assertSingleTerminalAndNextTaskStarts("ERROR_CANCELED")
    }

    /**
     * Retry inside a task the DSU app took over (WAITING_USER_CONFIRMATION -> "try again"),
     * cancelled while the retry is still capturing its parameters: the DSU_APP cancel path
     * waits for the retry step, no new request starts, one terminal from the cancel path.
     */
    @Test
    fun cancelDuringDsuAppRetryKeepsCancelOrdering() = runBlocking {
        val m = model()
        m.adbMode = false
        started(m.entry.retry()).job.join() // first request of the task
        m.submission!!.second.join()
        assertEquals(InstallTaskGate.Phase.DSU_APP, m.gate.current()!!.phase)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        m.boundary = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            "7"
        }
        val retry = started(m.entry.retry())
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val cancelPath = m.cancel()!!
        delay(100)
        assertTrue("cancel path must wait for the retry step", m.gate.isBusy())
        assertFalse(m.events.contains("cancel-path-after-retry"))
        release.countDown()
        retry.job.join()
        cancelPath.join()
        assertEquals(listOf("submission-start-${retry.taskId}"), m.events.filter { it.startsWith("submission") })
        m.assertSingleTerminalAndNextTaskStarts("ERROR_CANCELED")
    }

    // ---- repeated taps ------------------------------------------------------------------

    @Test
    fun repeatedTapsStartOneTaskAndOneStep() = runBlocking {
        val m = model()
        val release = CountDownLatch(1)
        m.boundary = {
            release.await(5, TimeUnit.SECONDS)
            "/ext/install.sh"
        }
        val first = started(m.entry.retry())
        repeat(5) { assertTrue(m.entry.retry() is RetryEntry.Result.Ignored) }
        release.countDown()
        first.job.join()
        m.assertSingleTerminalAndNextTaskStarts("REQUIRES_ADB_CMD_TO_CONTINUE")
    }

    @Test
    fun repeatedTapsInDsuAppNeverSubmitInParallel() = runBlocking {
        val m = model()
        m.adbMode = false
        val releaseSubmission = CompletableDeferred<Unit>()
        m.submissionBody = { releaseSubmission.await() }
        val first = started(m.entry.retry())
        first.job.join()
        // Request in flight: every further tap is ignored.
        repeat(5) { assertTrue(m.entry.retry() is RetryEntry.Result.Ignored) }
        releaseSubmission.complete(Unit)
        m.submission!!.second.join()
        // Request finished (prompt shown): "try again" re-submits within the same task, once.
        m.submissionBody = { delay(50) }
        val again = started(m.entry.retry())
        assertEquals(first.taskId, again.taskId)
        assertTrue(m.entry.retry() is RetryEntry.Result.Ignored)
        again.job.join()
        m.submission!!.second.join()
        assertEquals(1, m.maxParallelSubmissions.get())
        assertEquals(2, m.events.count { it.startsWith("submission-start") })
        m.cancel()!!.join()
        m.assertSingleTerminalAndNextTaskStarts("ERROR_CANCELED")
    }

    @Test
    fun retryIsIgnoredWhileTaskPreparesOrCancels() = runBlocking {
        val m = model()
        val id = m.beginTask()!! // e.g. the first installation is still preparing
        assertTrue(m.entry.retry() is RetryEntry.Result.Ignored)
        m.gate.requestCancel()
        assertTrue(m.entry.retry() is RetryEntry.Result.Ignored)
        assertTrue(m.gate.finish(id))
        assertTrue(m.entry.retry() is RetryEntry.Result.Started)
    }
}
