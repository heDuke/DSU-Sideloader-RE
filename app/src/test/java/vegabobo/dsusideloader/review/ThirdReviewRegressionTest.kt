package vegabobo.dsusideloader.review

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vegabobo.dsusideloader.core.MemDir
import vegabobo.dsusideloader.core.TaskWorkspaces
import vegabobo.dsusideloader.installer.privileged.DsuSubmission
import vegabobo.dsusideloader.installer.privileged.DsuSubmitTarget
import vegabobo.dsusideloader.installer.privileged.PendingRemount
import vegabobo.dsusideloader.installer.privileged.PendingRemountStore
import vegabobo.dsusideloader.installer.privileged.SdCardRemounter
import vegabobo.dsusideloader.installer.privileged.VolumeOps
import vegabobo.dsusideloader.installer.task.InstallTaskGate
import vegabobo.dsusideloader.installer.task.TaskSteps

/**
 * Port of the third-review probes (docs/reviews/pr2-third-review-repro/ThirdReviewWiringTest.kt
 * on docs/grok-handoff, reviewed head 9090fc71) to the project suite.
 *
 * The reviewer's probes run the ViewModel methods extracted verbatim (run_third_review.py);
 * that wiring is re-run with the reviewer's tool. Here the same interleavings run against the
 * production gate / workspace layout / step launcher / submission, in the order the
 * ViewModel now uses them (HomeViewModel.onPreparationStepUnsettled and
 * startPrivilegedInstallation).
 */
class ThirdReviewRegressionTest {

    // ---- TR1 ----------------------------------------------------------------------------

    /** TR1 probe: B's file survives A's late cleanup; B can't even start before it finished. */
    @Test
    fun cancelledTaskCleanupMustNotDeleteNextTaskFiles() {
        val gate = InstallTaskGate()
        val root = MemDir("workspace_dsuhelper")
        val first = gate.begin { TaskWorkspaces.taskKey(it, 1) }!!
        val keyA = gate.workspaceOf(first)!!
        root.findOrCreateDirectory(keyA).file("partial.img")

        // onPreparationStepUnsettled(first): cleanup on another thread, finish after it.
        gate.requestCancel()
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleaner = Thread {
            cleanupEntered.countDown()
            releaseCleanup.await(5, TimeUnit.SECONDS)
            TaskWorkspaces.clean(root, keyA, deleteAlsoGz = true)
            gate.finish(first)
        }
        cleaner.start()
        assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))

        // While A's cleanup runs, B can't start (the task is still held).
        assertNull(gate.begin { TaskWorkspaces.taskKey(it, 2) })
        releaseCleanup.countDown()
        cleaner.join(5000)

        val second = gate.begin { TaskWorkspaces.taskKey(it, 2) }!!
        val keyB = gate.workspaceOf(second)!!
        assertNotEquals(keyA, keyB)
        root.findOrCreateDirectory(keyB).file("new-task-image.img")
        // Even a (hypothetical) late repeat of A's cleanup only touches A's directory.
        TaskWorkspaces.clean(root, keyA, deleteAlsoGz = true)
        assertEquals(listOf("new-task-image.img"), root.dir(keyB)!!.names())
    }

    // ---- TR2 ----------------------------------------------------------------------------

    @Test
    fun everyTaskOwnsItsJobAndAFinishedTaskHasNone() {
        val gate = InstallTaskGate()
        val a = gate.begin()!!
        val jobA = gate.jobOf(a)!!
        assertTrue(gate.finish(a))
        assertNull(gate.jobOf(a))
        assertTrue(jobA.isCancelled)
        val b = gate.begin()!!
        val jobB = gate.jobOf(b)!!
        assertNotSame(jobA, jobB)
        assertTrue(jobB.isActive)
        // A cancel captures the job of the task it cancels.
        assertTrue(gate.requestCancel()!!.job === jobB)
    }

    @Test
    fun phaseSwitchAndRegistrationAreAtomicAgainstCancel() {
        repeat(500) {
            val gate = InstallTaskGate()
            val id = gate.begin()!!
            val registered = AtomicReference<String?>(null)
            val seenByCancel = AtomicReference<String?>("not-run")
            val barrier = CyclicBarrier(2)
            val switcher = Thread {
                barrier.await()
                gate.setPhase(id, InstallTaskGate.Phase.DSU_APP) { registered.set("step-$id") }
            }
            val canceller = Thread {
                barrier.await()
                val task = gate.requestCancel()!!
                // What cancelDsuAppInstallation would read right after requestCancel.
                seenByCancel.set(if (task.phase == InstallTaskGate.Phase.DSU_APP) registered.get() else null)
            }
            switcher.start()
            canceller.start()
            switcher.join()
            canceller.join()
            if (registered.get() != null) {
                // Cancel saw DSU_APP: it must also see the registered step.
                assertEquals("step-$id", seenByCancel.get())
            } else {
                assertNull(seenByCancel.get())
            }
        }
    }

    private class Calls {
        val list: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        fun target(gate: InstallTaskGate) = object : DsuSubmitTarget {
            override val volumeOps = object : VolumeOps {
                override fun volumeIds() = listOf("public:179,1")
                override fun unmount(id: String) {
                    list += "unmount"
                }
                override fun mount(id: String) {}
            }
            override fun prepareDsuApp() {
                list += "forceStop"
            }
            override fun startDsuInstall() {
                list += "startActivity currentTask=${gate.current()?.id}"
            }
        }
    }

    private class MemStore : PendingRemountStore {
        var record: PendingRemount? = null
        override suspend fun read() = record
        override suspend fun write(record: PendingRemount?) {
            this.record = record
        }
    }

    /**
     * TR2 probe: A is blocked between capturing its parameters and registering its step
     * (the reviewer blocks currentBootId()); A is cancelled; B tries to start; A resumes.
     * Before: cancel-finished-next-task=2, startActivity-from-old-task=1 currentTask=2.
     */
    @Test
    fun cancelInRegistrationWindowMustNotLetOldTaskSubmit() = runBlocking {
        val gate = InstallTaskGate()
        val calls = Calls()
        val scope = CoroutineScope(SupervisorJob())
        val first = gate.begin()!!
        val bootReadEntered = CountDownLatch(1)
        val releaseBootRead = CountDownLatch(1)
        val stepRef = AtomicReference<Job?>(null)

        // Same order as HomeViewModel.startPrivilegedInstallation(first).
        val worker = Thread {
            val job = gate.jobOf(first) ?: return@Thread
            if (!gate.acceptsUpdates(first)) return@Thread
            bootReadEntered.countDown()
            releaseBootRead.await(5, TimeUnit.SECONDS) // currentBootId()
            val submission = DsuSubmission(
                SdCardRemounter(MemStore()),
                unmountSdCard = true,
                bootId = "7",
                connect = { calls.target(gate) },
                isOwner = { gate.acceptsUpdates(first) },
            )
            val stepJob = TaskSteps.launch(scope, Dispatchers.IO + job, CoroutineStart.LAZY, onUnsettled = {}) {
                submission.submit()
            }
            stepRef.set(stepJob)
            val registered = gate.setPhase(first, InstallTaskGate.Phase.DSU_APP) {}
            if (!registered) {
                stepJob.cancel()
                if (gate.isCancelling(first)) {
                    gate.finish(first)
                    calls.list += "cancel-finished"
                }
                return@Thread
            }
            stepJob.start()
        }
        worker.start()
        assertTrue(bootReadEntered.await(5, TimeUnit.SECONDS))

        // onClickCancelInstallationButton(): phase is still PREPARING -> the worker reports.
        val cancelled = gate.requestCancel()!!
        assertEquals(InstallTaskGate.Phase.PREPARING, cancelled.phase)
        cancelled.job.cancel()
        // The old task is still held: B can't start and can't take over its Job.
        assertNull(gate.begin())

        releaseBootRead.countDown()
        worker.join(5000)
        stepRef.get()?.join()
        val second = gate.begin()
        assertNotNull("old task must be released after its cancel was reported", second)
        assertTrue(gate.jobOf(second!!)!!.isActive)
        Thread.sleep(200)
        assertFalse(calls.list.toString(), calls.list.any { it.startsWith("startActivity") || it == "unmount" })
        assertEquals(listOf("cancel-finished"), calls.list.toList())
    }

    /** Even on an active Job, a request whose task is no longer current never starts the DSU app. */
    @Test
    fun submissionOfReplacedTaskChecksItsOwnIdentityBeforeStartActivity() = runBlocking {
        val gate = InstallTaskGate()
        val calls = Calls()
        val first = gate.begin()!!
        val submission = DsuSubmission(
            SdCardRemounter(MemStore()),
            unmountSdCard = false,
            bootId = "7",
            connect = {
                // Task replaced while the service was being awaited.
                gate.finish(first)
                gate.begin()
                calls.target(gate)
            },
            isOwner = { gate.acceptsUpdates(first) },
        )
        val outcome = AtomicReference<DsuSubmission.Result?>(null)
        TaskSteps.launch(CoroutineScope(SupervisorJob()), Dispatchers.IO + Job(), onUnsettled = {}) {
            outcome.set(submission.submit())
        }.join()
        assertEquals(DsuSubmission.Result.Cancelled, outcome.get())
        assertFalse(submission.hasReachedSystem)
        assertFalse(calls.list.toString(), calls.list.any { it.startsWith("startActivity") })
    }
}
