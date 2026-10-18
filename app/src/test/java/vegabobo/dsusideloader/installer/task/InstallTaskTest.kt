package vegabobo.dsusideloader.installer.task

import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vegabobo.dsusideloader.installer.root.CleanupResult
import vegabobo.dsusideloader.installer.root.DsuBackend
import vegabobo.dsusideloader.installer.root.DsuImageSource
import vegabobo.dsusideloader.installer.root.DsuInstallPipeline
import vegabobo.dsusideloader.installer.root.DsuInstallResult
import vegabobo.dsusideloader.installer.root.DsuWriteChannel
import vegabobo.dsusideloader.preparation.InstallationStep

class InstallTaskTest {

    @Test
    fun outcomeMapsCleanupResults() {
        val ok = CleanupResult(aborted = false, removed = true, stillInstalled = false)
        val bad = CleanupResult(aborted = true, removed = false, stillInstalled = true)
        assertEquals(
            InstallationStep.INSTALL_SUCCESS_REBOOT_DYN_OS,
            InstallOutcome.from(DsuInstallResult.Success).step,
        )
        assertEquals(InstallationStep.ERROR_CANCELED, InstallOutcome.from(DsuInstallResult.Cancelled(null)).step)
        assertEquals(InstallationStep.ERROR_CANCELED, InstallOutcome.from(DsuInstallResult.Cancelled(ok)).step)
        val cancelBad = InstallOutcome.from(DsuInstallResult.Cancelled(bad))
        assertEquals(InstallationStep.ERROR_CLEANUP_INCOMPLETE, cancelBad.step)
        assertTrue(cancelBad.detail.contains("remove=false"))
        assertEquals(
            InstallationStep.ERROR_CREATE_PARTITION,
            InstallOutcome.from(DsuInstallResult.Failed(InstallationStep.ERROR_CREATE_PARTITION, "x", ok)).step,
        )
        val failBad = InstallOutcome.from(DsuInstallResult.Failed(InstallationStep.ERROR, "submit failed", bad))
        assertEquals(InstallationStep.ERROR_CLEANUP_INCOMPLETE, failBad.step)
        assertTrue(failBad.detail.contains("submit failed"))
        // Unknown post-cleanup state must not look like a clean cancel.
        val unknown = CleanupResult(aborted = true, removed = true, stillInstalled = null)
        assertEquals(
            InstallationStep.ERROR_CLEANUP_INCOMPLETE,
            InstallOutcome.from(DsuInstallResult.Cancelled(unknown)).step,
        )
    }

    @Test
    fun gateAllowsOneTaskAndCancelOnlyOnce() {
        val gate = InstallTaskGate()
        val id = gate.begin()!!
        assertNull(gate.begin())
        assertTrue(gate.acceptsUpdates(id))
        val t = gate.requestCancel()!!
        assertEquals(InstallTaskGate.Phase.PREPARING, t.phase)
        assertNull(gate.requestCancel())
        assertFalse(gate.acceptsUpdates(id))
        assertFalse(gate.setPhase(id, InstallTaskGate.Phase.ROOT_INSTALL))
        assertNull(gate.begin())
        assertTrue(gate.finish(id))
        assertNotNull(gate.begin())
    }

    @Test
    fun staleTaskCallbacksCannotOverwriteNewTask() {
        val gate = InstallTaskGate()
        val ui = AtomicReference("idle")
        val first = gate.begin()!!
        assertTrue(gate.finish(first))
        val second = gate.begin()!!
        ui.set("task2-installing")
        // Late callbacks of the first task:
        if (gate.acceptsUpdates(first)) ui.set("task1-progress")
        if (gate.finish(first)) ui.set("task1-terminal")
        assertEquals("task2-installing", ui.get())
        assertTrue(gate.isCurrent(second))
    }

    /** Backend whose remove() blocks until released, to observe the cleanup window. */
    private class BlockingCleanupBackend : DsuBackend {
        val removeEntered = CountDownLatch(1)
        val releaseRemove = CountDownLatch(1)
        override fun enableDynamicSystemFeature() {}
        override fun isInUse() = false

        @Volatile var installed = false
        override fun isInstalled() = installed
        override fun forceStopDsuApp() {}
        override fun startInstallation(slot: String) = true
        override fun createPartition(name: String, size: Long, readOnly: Boolean) = 0
        override fun bytesProcessed() = 0L
        override fun openWriteChannel(partition: String) = object : DsuWriteChannel {
            override val capacity = 8
            override fun register() = true
            override fun submit(data: ByteArray, length: Int) = true
            override fun close() {}
        }
        override fun closePartition() = true
        override fun finishInstallation() = true
        override fun abort() = true
        override fun remove(): Boolean {
            removeEntered.countDown()
            releaseRemove.await(5, TimeUnit.SECONDS)
            return true
        }
    }

    @Test
    fun newInstallIsRejectedWhileCancelCleanupIsBlocked() {
        val gate = InstallTaskGate()
        val backend = BlockingCleanupBackend()
        val cancelled = AtomicBoolean(false)
        val result = AtomicReference<DsuInstallResult>()
        val ui = AtomicReference("installing")

        val id = gate.begin(InstallTaskGate.Phase.ROOT_INSTALL)!!
        val worker = Thread {
            val r = DsuInstallPipeline(
                backend,
                1024,
                DsuImageSource.SingleImage(DsuImageSource.Image("system", 64) { ByteArrayInputStream(ByteArray(64)) }),
                isCancelled = { cancelled.get() },
                progressPollIntervalMs = 5,
                listener = object : DsuInstallPipeline.Listener {
                    override fun onProgress(progress: Float, partition: String) {
                        cancelled.set(true) // user cancels during writing
                    }
                },
            ).run()
            result.set(r)
            if (gate.finish(id)) ui.set(InstallOutcome.from(r).step.name)
        }
        // UI: cancel requested -> cancelling (task stays locked)
        assertNotNull(gate.requestCancel())
        ui.set("CANCELLING")
        worker.start()

        assertTrue(backend.removeEntered.await(5, TimeUnit.SECONDS))
        // Cleanup is blocked in remove(): a new installation must not start.
        assertNull(gate.begin())
        assertEquals("CANCELLING", ui.get())

        backend.releaseRemove.countDown()
        worker.join(5000)
        assertTrue(result.get() is DsuInstallResult.Cancelled)
        assertEquals(InstallationStep.ERROR_CANCELED.name, ui.get())
        assertNotNull(gate.begin())
    }

    /** RE1: the handoff of an earlier request survives later retries and cancel, per task only. */
    @Test
    fun handoffToSystemIsKeptForTheWholeTask() {
        val gate = InstallTaskGate()
        val id = gate.begin()!!
        assertFalse(gate.hasHandedToSystem(id))
        assertTrue(gate.setPhase(id, InstallTaskGate.Phase.DSU_APP))
        gate.markHandedToSystem(id)
        // A retry of the same task (new step, phase switch again) doesn't reset it.
        val retry = gate.attachRetry(id) { kotlinx.coroutines.Job() }
        assertNotNull(retry)
        retry!!.cancel()
        assertTrue(gate.setPhase(id, InstallTaskGate.Phase.DSU_APP))
        assertTrue(gate.hasHandedToSystem(id))
        assertNotNull(gate.requestCancel())
        assertTrue("kept while cancelling", gate.hasHandedToSystem(id))
        gate.markHandedToSystem(id + 1) // stale/other id: no effect
        assertTrue(gate.finish(id))
        assertFalse(gate.hasHandedToSystem(id))
        val next = gate.begin()!!
        assertFalse("a new task starts without handoff", gate.hasHandedToSystem(next))
    }
}
