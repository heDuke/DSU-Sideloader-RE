package vegabobo.dsusideloader.review

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vegabobo.dsusideloader.core.MemDir
import vegabobo.dsusideloader.core.TaskWorkspaces
import vegabobo.dsusideloader.installer.task.InstallTaskGate
import vegabobo.dsusideloader.installer.task.TaskSteps

/**
 * Port of the fourth-review probe FR1 (docs/reviews/pr2-fourth-review-repro/
 * FourthReviewWiringTest.kt, HandoffExceptionTest, on docs/grok-handoff; reviewed head
 * cc6aec38) plus the interleavings asked for in the fix.
 *
 * The reviewer's probe runs the ViewModel methods extracted verbatim with the real ADB script
 * generator (run_fourth_review.py). Here the production gate, step launcher and workspace
 * layout run in the order HomeViewModel uses them: onConfirmInstallationSheet ->
 * onPreparationFinished -> setupAdbInstallation (script write + finishIfNotCancelling), with
 * onErrorAfterSettle -> onPreparationStepUnsettled(inStep = true) and its claim/cleanup/
 * finally-release. The Android ViewModel itself is not instantiated.
 */
class FourthReviewRegressionTest {

    private class Model {
        val gate = InstallTaskGate()

        /** Exceptions escaping a coroutine of the model: must stay empty (asserted). */
        val uncaught: MutableList<Throwable> = Collections.synchronizedList(mutableListOf<Throwable>())
        val reportErrors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf<Throwable>())
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e },
        )
        val root = MemDir("workspace_dsuhelper")
        val terminals: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

        @Volatile var card = "IDLE"

        @Volatile var errorText = ""
        var cleanup: (String) -> Unit = { key -> TaskWorkspaces.clean(root, key, deleteAlsoGz = true) }
        var reportHook: () -> Unit = {}

        private fun terminal(id: Long, step: String, detail: String = "") {
            terminals += step
            card = step
            errorText = detail
        }

        /** HomeViewModel.onPreparationStepUnsettled (inStep = true). */
        fun onPreparationStepUnsettled(id: Long, cause: Throwable?) {
            val key = gate.workspaceOf(id) ?: return
            if (!gate.claimTerminal(id)) return
            try {
                val cleanupError = runCatching { cleanup(key) }.exceptionOrNull()
                val cleanupNote = cleanupError?.let { "Temporary files could not be removed: ${it.message}" }
                reportHook()
                if (cause is CancellationException || gate.isCancelling(id)) {
                    val late = cause?.takeIf { it !is CancellationException }?.let { "Error while cancelling: ${it.message}" }
                    if (gate.finish(id)) terminal(id, "ERROR_CANCELED", listOfNotNull(late, cleanupNote).joinToString("\n"))
                    return
                }
                if (!gate.finish(id)) return
                terminal(id, "ERROR", listOfNotNull(cause?.message, cleanupNote).joinToString("\n"))
            } catch (e: Exception) {
                reportErrors += e // HomeViewModel logs it instead of letting it escape
            } finally {
                if (gate.finish(id)) terminal(id, "ERROR", "Installation ended unexpectedly")
            }
        }

        /** HomeViewModel.onClickCancelInstallationButton, PREPARING phase. */
        fun cancel() {
            val task = gate.requestCancel() ?: return
            card = "CANCELLING"
            task.job.cancel()
        }

        /**
         * onConfirmInstallationSheet with an ADB hand-over: preparation succeeds, settles, then
         * setupAdbInstallation writes the script synchronously ([write]) and finishes.
         */
        fun confirmAdb(write: () -> String, afterWrite: () -> Unit = {}): Pair<Long, Job> {
            val id = gate.begin { TaskWorkspaces.taskKey(it, 1) }!!
            root.findOrCreateDirectory(gate.workspaceOf(id)!!).file("system.img.gz")
            card = "PROCESSING"
            val job = TaskSteps.launch(
                scope,
                Dispatchers.IO + gate.jobOf(id)!!,
                onUnsettled = { onPreparationStepUnsettled(id, it) },
                onErrorAfterSettle = { e ->
                    if (gate.isOwnedByPreparation(id)) {
                        onPreparationStepUnsettled(id, e)
                    } else {
                        terminals += "onTaskFailed"
                    }
                },
            ) { step ->
                check(step.settle()) // onPreparationFinished
                if (gate.isCancelling(id)) {
                    onPreparationStepUnsettled(id, CancellationException("cancelled after preparation"))
                    return@launch
                }
                val path = write() // AdbInstallationHandler.generate -> writeStringToExternalFileDir
                afterWrite()
                if (gate.finishIfNotCancelling(id)) {
                    terminal(id, "REQUIRES_ADB_CMD_TO_CONTINUE", path)
                } else if (gate.isCancelling(id)) {
                    onPreparationStepUnsettled(id, CancellationException("cancelled while generating the ADB script"))
                }
            }
            return id to job
        }

        suspend fun assertReleasedWithSingleTerminal(expected: String) {
            // Job.join() can return before the job's completion handlers (the TaskSteps
            // fallback) ran: wait for the terminal, then give a duplicate time to show up.
            withTimeout(5000) { while (gate.isBusy() || card != expected) delay(5) }
            delay(50)
            assertTrue("no unhandled coroutine exception: $uncaught", uncaught.isEmpty())
            assertFalse("task lock must be released", gate.isBusy())
            assertEquals(listOf(expected), terminals.toList())
            assertEquals(expected, card)
            assertNotNull("a next task must be admitted", gate.begin())
        }
    }

    /** The reviewer's probe: cancel while the script write is blocked, then the write fails. */
    @Test
    fun cancelThenScriptWriteFailsReleasesGate() = runBlocking {
        val m = Model()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (_, job) = m.confirmAdb(write = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("externalFilesDir cannot be null.")
        })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        m.cancel()
        release.countDown()
        job.join()
        // Released when the task's Job has completed (outcome produced inside the step).
        m.assertReleasedWithSingleTerminal("ERROR_CANCELED")
        assertTrue(m.errorText, m.errorText.contains("Error while cancelling: externalFilesDir cannot be null."))
    }

    /** Cancel after the script was written, before the task finished: the cancel wins, once. */
    @Test
    fun cancelAfterScriptWriteIsReportedAsCancelledOnce() = runBlocking {
        val m = Model()
        val written = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (_, job) = m.confirmAdb(write = { "/ext/install.sh" }, afterWrite = {
            written.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        assertTrue(written.await(5, TimeUnit.SECONDS))
        m.cancel()
        release.countDown()
        job.join()
        m.assertReleasedWithSingleTerminal("ERROR_CANCELED")
    }

    /** Cancel after the write, then an exception before the task finished. */
    @Test
    fun cancelThenExceptionAfterScriptWriteReleasesGate() = runBlocking {
        val m = Model()
        val written = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (_, job) = m.confirmAdb(write = { "/ext/install.sh" }, afterWrite = {
            written.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("could not record script path")
        })
        assertTrue(written.await(5, TimeUnit.SECONDS))
        m.cancel()
        release.countDown()
        job.join()
        m.assertReleasedWithSingleTerminal("ERROR_CANCELED")
    }

    /** Cancel during the script write, and the cleanup of the task directory fails too. */
    @Test
    fun cancelWithFailingCleanupReportsCleanupErrorAndReleasesGate() = runBlocking {
        val m = Model()
        m.cleanup = { throw IOException("SAF delete failed") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (_, job) = m.confirmAdb(write = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("write failed")
        })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        m.cancel()
        release.countDown()
        job.join()
        m.assertReleasedWithSingleTerminal("ERROR_CANCELED")
        assertTrue(m.errorText, m.errorText.contains("Temporary files could not be removed: SAF delete failed"))
    }

    /** Reporting itself throws: the finally block still releases the task (no CANCELLING forever). */
    @Test
    fun exceptionWhileReportingStillReleasesGate() = runBlocking {
        val m = Model()
        m.reportHook = { throw IllegalStateException("UI update failed") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (_, job) = m.confirmAdb(write = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw IOException("write failed")
        })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        m.cancel()
        release.countDown()
        job.join()
        assertFalse(m.gate.isBusy())
        assertEquals(listOf("ERROR"), m.terminals.toList())
        assertFalse(m.card == "CANCELLING")
        // The injected failure is caught and recorded, not leaked to a background thread.
        assertEquals(listOf("UI update failed"), m.reportErrors.map { it.message })
        assertTrue("no unhandled coroutine exception: ${m.uncaught}", m.uncaught.isEmpty())
    }

    /** No cancel: a failed script write is a failure (and still releases the task). */
    @Test
    fun scriptWriteFailureWithoutCancelIsAnError() = runBlocking {
        val m = Model()
        val (_, job) = m.confirmAdb(write = { throw IOException("disk full") })
        job.join()
        m.assertReleasedWithSingleTerminal("ERROR")
        assertTrue(m.errorText.contains("disk full"))
    }

    /** Success path unchanged: script generated, task released with the ADB card. */
    @Test
    fun scriptGeneratedWithoutCancelEndsTheTask() = runBlocking {
        val m = Model()
        val (_, job) = m.confirmAdb(write = { "/ext/install.sh" })
        job.join()
        m.assertReleasedWithSingleTerminal("REQUIRES_ADB_CMD_TO_CONTINUE")
    }

    /**
     * Analogous window: preparation ended, cancel arrives before the switch to DSU_APP, and
     * capturing the submission parameters (e.g. currentBootId()) throws. The phase is still
     * PREPARING, so the preparation side owns the outcome.
     */
    @Test
    fun cancelBeforeDsuAppSwitchPlusExceptionReleasesGate() = runBlocking {
        val m = Model()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val (id, job) = m.confirmAdb(write = {
            // startPrivilegedInstallation before setPhase(DSU_APP)
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            throw SecurityException("Settings.Global read failed")
        })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertEquals(InstallTaskGate.Phase.PREPARING, m.gate.phaseOf(id))
        m.cancel()
        release.countDown()
        job.join()
        m.assertReleasedWithSingleTerminal("ERROR_CANCELED")
    }

    /** Once a backend owns the task (DSU_APP / ROOT_INSTALL), a hand-over error is not reported here. */
    @Test
    fun handOverErrorAfterBackendTookOverIsLeftToTheBackend() {
        val gate = InstallTaskGate()
        val id = gate.begin()!!
        assertTrue(gate.isOwnedByPreparation(id))
        assertTrue(gate.setPhase(id, InstallTaskGate.Phase.DSU_APP))
        assertFalse(gate.isOwnedByPreparation(id))
        gate.requestCancel()
        assertFalse(gate.isOwnedByPreparation(id))
    }

    @Test
    fun terminalClaimIsOncePerTaskAndSuccessFinishYieldsToCancel() {
        val gate = InstallTaskGate()
        val id = gate.begin()!!
        assertTrue(gate.claimTerminal(id))
        assertFalse(gate.claimTerminal(id))
        gate.requestCancel()
        assertFalse(gate.finishIfNotCancelling(id))
        assertTrue(gate.isBusy())
        assertTrue(gate.finish(id))
        assertFalse(gate.claimTerminal(id))
        val next = gate.begin()!!
        assertTrue(gate.finishIfNotCancelling(next))
    }
}
