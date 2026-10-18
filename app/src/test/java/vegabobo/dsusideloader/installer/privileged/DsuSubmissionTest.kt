package vegabobo.dsusideloader.installer.privileged

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vegabobo.dsusideloader.installer.task.InstallTaskGate
import vegabobo.dsusideloader.installer.task.TaskSteps

/**
 * Review RR2: the DSU app submission is a cancellable, awaitable child of the task Job.
 * Uses the production DsuSubmission/DsuSubmissionStep, TaskSteps and InstallTaskGate with the
 * ViewModel's cancel order (requestCancel -> cancel task Job -> cancelAndJoin -> finish).
 * The Android adapter (DsuInstallationHandler building the Intent) and the ViewModel are not
 * instantiated.
 */
class DsuSubmissionTest {

    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private inner class Target(
        val onUnmount: () -> Unit = {},
        val onPrepare: () -> Unit = {},
    ) : DsuSubmitTarget {
        override val volumeOps = object : VolumeOps {
            override fun volumeIds() = listOf("private", "public:179,1")
            override fun mount(id: String) {
                calls += "mount"
            }
            override fun unmount(id: String) {
                onUnmount()
                calls += "unmount"
            }
        }
        override fun prepareDsuApp() {
            onPrepare()
            calls += "forceStop(prepare)"
        }
        override fun startDsuInstall() {
            calls += "startActivity"
        }
    }

    private class Store(val beforeRead: suspend () -> Unit = {}) : PendingRemountStore {
        var record: PendingRemount? = null
        override suspend fun read(): PendingRemount? {
            beforeRead()
            return record
        }
        override suspend fun write(record: PendingRemount?) {
            this.record = record
        }
    }

    private class Task(val submission: DsuSubmission) {
        val gate = InstallTaskGate()
        val id = gate.begin(InstallTaskGate.Phase.DSU_APP)!!
        val taskJob = Job()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var result: DsuSubmission.Result? = null
        val step: DsuSubmissionStep

        init {
            val job = TaskSteps.launch(scope, Dispatchers.IO + taskJob, CoroutineStart.LAZY, onUnsettled = {}) { s ->
                result = submission.submit()
                if (result == DsuSubmission.Result.Submitted) s.settle()
            }
            step = DsuSubmissionStep(id, submission, job)
            job.start()
        }

        /** HomeViewModel.onClickCancelInstallationButton + cancelDsuAppInstallation order. */
        suspend fun cancel(calls: MutableList<String>): Boolean {
            gate.requestCancel()
            taskJob.cancel()
            val reached = step.cancelAndJoin()
            gate.finish(id)
            calls += "cancel-finished"
            return reached
        }
    }

    private fun submission(store: Store, connect: suspend () -> DsuSubmitTarget?) =
        DsuSubmission(SdCardRemounter(store), unmountSdCard = true, bootId = "7", connect = connect)

    private fun assertNothingAfterCancel() {
        val after = calls.dropWhile { it != "cancel-finished" }.drop(1)
        assertEquals("side effects after cancel: $calls", emptyList<String>(), after)
        assertFalse(calls.contains("startActivity"))
    }

    @Test
    fun cancelWhileStorageReadIsBlockedNeverUnmountsOrSubmitsLater() = runBlocking {
        // Reviewer repro: request blocked in a storage read, cancel completes, read released.
        val readEntered = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val store = Store { readEntered.complete(Unit); releaseRead.await() }
        val task = Task(submission(store) { Target() })
        withTimeout(3000) { readEntered.await() }
        assertFalse(task.cancel(calls))
        releaseRead.complete(Unit)
        delay(200)
        assertNothingAfterCancel()
        assertFalse(calls.contains("unmount"))
        assertEquals(DsuSubmission.Result.Cancelled, task.result)
        assertFalse(task.gate.isBusy())
    }

    @Test
    fun cancelDuringSlowUnmountWaitsAndThenDoesNotSubmit() = runBlocking {
        val inUnmount = CountDownLatch(1)
        val releaseUnmount = CountDownLatch(1)
        val task = Task(
            submission(Store()) {
                Target(onUnmount = { inUnmount.countDown(); releaseUnmount.await(5, TimeUnit.SECONDS) })
            },
        )
        assertTrue(inUnmount.await(3, TimeUnit.SECONDS))
        val cancel = async(Dispatchers.IO) { task.cancel(calls) }
        delay(200)
        assertFalse("cancel must wait for the blocking unmount", cancel.isCompleted)
        assertTrue(task.gate.isBusy())
        releaseUnmount.countDown()
        assertFalse(withTimeout(3000) { cancel.await() })
        assertEquals(listOf("unmount", "cancel-finished"), calls.toList())
        assertNothingAfterCancel()
    }

    @Test
    fun cancelWhileWaitingForServiceStopsTheWait() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val task = Task(
            submission(Store()) {
                waiting.complete(Unit)
                awaitCancellation()
            },
        )
        withTimeout(3000) { waiting.await() }
        assertFalse(withTimeout(3000) { task.cancel(calls) })
        assertEquals(listOf("cancel-finished"), calls.toList())
        assertEquals(DsuSubmission.Result.Cancelled, task.result)
    }

    @Test
    fun cancelRightBeforeStartActivityIsHonoured() = runBlocking {
        // Blocking (non-cancellable) prepare step: cancel lands between it and startActivity.
        val inPrepare = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val store = Store()
        val task = Task(
            submission(store) {
                Target(onPrepare = { inPrepare.countDown(); releasePrepare.await(5, TimeUnit.SECONDS) })
            },
        )
        assertTrue(inPrepare.await(3, TimeUnit.SECONDS))
        val cancel = async(Dispatchers.IO) { task.cancel(calls) }
        delay(100)
        releasePrepare.countDown()
        assertFalse(withTimeout(3000) { cancel.await() })
        assertNothingAfterCancel()
        // Unmounted volumes stay recorded for the cancel outcome to restore.
        // Unmounted volume stays recorded (still LOCAL_TASK) for the cancel outcome to restore.
        assertEquals(listOf("public:179,1"), store.record?.volumes)
        assertEquals(RemountPhase.LOCAL_TASK, store.record?.phase)
    }

    @Test
    fun cancelAfterSubmissionReportsReachedSystem() = runBlocking {
        val store = Store()
        val task = Task(submission(store) { Target() })
        withTimeout(3000) { task.step.job.join() }
        assertEquals(DsuSubmission.Result.Submitted, task.result)
        assertTrue(task.cancel(calls))
        assertEquals(RemountPhase.SYSTEM_UNKNOWN, store.record?.phase)
        assertEquals(listOf("unmount", "forceStop(prepare)", "startActivity", "cancel-finished"), calls.toList())
    }

    @Test
    fun taskCancelledBeforeStepStartsDoesNothing() = runBlocking {
        val sub = submission(Store()) { Target() }
        val taskJob = Job().apply { cancel() }
        val job = TaskSteps.launch(
            CoroutineScope(Dispatchers.IO),
            Dispatchers.IO + taskJob,
            CoroutineStart.LAZY,
            onUnsettled = {},
        ) { sub.submit() }
        val step = DsuSubmissionStep(1, sub, job)
        job.start()
        assertFalse(withTimeout(3000) { step.cancelAndJoin() })
        assertEquals(emptyList<String>(), calls.toList())
    }
}
