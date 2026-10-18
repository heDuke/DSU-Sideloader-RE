package vegabobo.dsusideloader.installer.task

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review RR1: the preparation entry of an installation task (HomeViewModel.onConfirmInstallationSheet)
 * runs as a [TaskSteps] step. These tests use the production gate and step launcher with the
 * same terminal handling as the ViewModel (cancelled -> ERROR_CANCELED, otherwise failure).
 * The Android ViewModel itself is not instantiated.
 */
class TaskStepTest {

    /** Mirrors HomeViewModel's task wiring around one preparation step. */
    private class Coordinator {
        val gate = InstallTaskGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val terminals = mutableListOf<String>()
        val terminalCount = AtomicInteger()
        var installationJob = Job()

        fun beginTask(): Long {
            val id = gate.begin()!!
            installationJob = Job()
            return id
        }

        /** HomeViewModel.onPreparationStepUnsettled */
        fun onUnsettled(id: Long, cause: Throwable?) {
            terminalCount.incrementAndGet()
            val outcome = if (cause is CancellationException || gate.isCancelling(id)) {
                "ERROR_CANCELED"
            } else {
                "ERROR:${cause?.message}"
            }
            if (gate.finish(id)) synchronized(terminals) { terminals += outcome }
        }

        /** HomeViewModel.onClickCancelInstallationButton for the PREPARING phase. */
        fun cancel() {
            gate.requestCancel()
            installationJob.cancel()
        }

        fun launchPreparation(
            id: Long,
            context: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
            body: suspend (TaskStep) -> Unit,
        ): Job = TaskSteps.launch(scope, context + installationJob, onUnsettled = { onUnsettled(id, it) }) { step ->
            body(step)
        }
    }

    @Test
    fun cancelDuringPreferenceReadReleasesGate() = runBlocking {
        val c = Coordinator()
        val id = c.beginTask()
        val readEntered = CompletableDeferred<Unit>()
        val preference = CompletableDeferred<Boolean>() // never completes: suspended DataStore read
        val job = c.launchPreparation(id) {
            readEntered.complete(Unit)
            preference.await()
            error("must not continue after cancellation")
        }
        withTimeout(3000) { readEntered.await() }
        c.cancel()
        withTimeout(3000) { job.join() }
        assertEquals(listOf("ERROR_CANCELED"), c.terminals)
        assertFalse(c.gate.isBusy())
        assertTrue(c.gate.begin() != null)
    }

    @Test
    fun cancelBeforeStepStartsReleasesGate() = runBlocking {
        val c = Coordinator()
        val id = c.beginTask()
        val executor = Executors.newSingleThreadExecutor()
        val blocker = CountDownLatch(1)
        executor.execute { blocker.await(5, TimeUnit.SECONDS) } // dispatcher busy: step not started
        val ran = AtomicInteger()
        val job = c.launchPreparation(id, executor.asCoroutineDispatcher()) { ran.incrementAndGet() }
        c.cancel()
        blocker.countDown()
        withTimeout(3000) { job.join() }
        executor.shutdown()
        assertEquals(0, ran.get())
        assertEquals(listOf("ERROR_CANCELED"), c.terminals)
        assertFalse(c.gate.isBusy())
    }

    @Test
    fun taskJobCancelledBeforeLaunchStillProducesTerminal() = runBlocking {
        val c = Coordinator()
        val id = c.beginTask()
        c.cancel()
        val job = c.launchPreparation(id) { error("must not run") }
        withTimeout(3000) { job.join() }
        assertEquals(listOf("ERROR_CANCELED"), c.terminals)
        assertFalse(c.gate.isBusy())
    }

    @Test
    fun preferenceReadFailureIsReportedAndReleasesGate() = runBlocking {
        val c = Coordinator()
        val id = c.beginTask()
        val job = c.launchPreparation(id) { throw java.io.IOException("datastore corrupted") }
        withTimeout(3000) { job.join() }
        assertEquals(listOf("ERROR:datastore corrupted"), c.terminals)
        assertFalse(c.gate.isBusy())
    }

    @Test
    fun stepReturningWithoutResultIsAFailureNotAHang() = runBlocking {
        val c = Coordinator()
        val id = c.beginTask()
        withTimeout(3000) { c.launchPreparation(id) { }.join() }
        assertEquals(1, c.terminals.size)
        assertTrue(c.terminals[0].startsWith("ERROR"))
        assertFalse(c.gate.isBusy())
    }

    @Test
    fun handedOverStepDoesNotRunFallback() = runBlocking {
        val c = Coordinator()
        val id = c.beginTask()
        val job = c.launchPreparation(id) { step -> assertTrue(step.settle()) /* next step owns the task */ }
        withTimeout(3000) { job.join() }
        c.cancel()
        assertEquals(0, c.terminalCount.get())
        assertTrue(c.gate.isBusy()) // released by the next step's own terminal, not here
    }

    @Test
    fun fallbackWaitsUntilNonCooperativeBodyStopped() = runBlocking {
        val c = Coordinator()
        val id = c.beginTask()
        val inBody = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = c.launchPreparation(id) {
            inBody.countDown()
            release.await(5, TimeUnit.SECONDS) // blocking work that ignores cancellation
        }
        assertTrue(inBody.await(3, TimeUnit.SECONDS))
        c.cancel()
        Thread.sleep(100)
        assertEquals("no terminal while the body still runs", 0, c.terminalCount.get())
        assertTrue(c.gate.isBusy())
        release.countDown()
        withTimeout(3000) { job.join() }
        assertEquals(listOf("ERROR_CANCELED"), c.terminals)
    }
}
