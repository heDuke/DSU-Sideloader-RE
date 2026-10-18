package review3

import android.content.Intent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import vegabobo.dsusideloader.IPrivilegedService
import vegabobo.dsusideloader.ReviewVolume
import vegabobo.dsusideloader.installer.privileged.RemountPhase
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider
import vegabobo.dsusideloader.util.OperationModeUtils

/**
 * RE1 (PR #3 review): a retry that is cancelled or fails before its own handoff must not end
 * an earlier, unconfirmed handoff of the same task. Same harness as RetryHistoryReviewTest
 * (real remountSdCardsIfOwnedBy / SdCardRemounter), with the stricter assertions of the fix:
 * no mount, SYSTEM_UNKNOWN record kept, one terminal, next task, no leaked exceptions.
 */
class RetryHandoffWiringTest {
    private class Service(private val blockUnmount: Int = -1) : IPrivilegedService {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val unmounts = AtomicInteger()
        val blockedEntered = CountDownLatch(1)
        val releaseBlocked = CountDownLatch(1)
        private val mounted = AtomicBoolean(true)
        override val volumes get() = listOf(ReviewVolume("public:179,1", if (mounted.get()) 2 else 0))
        override fun unmount(id: String) {
            val n = unmounts.incrementAndGet()
            calls += "unmount-$n"
            if (n == blockUnmount) {
                blockedEntered.countDown()
                check(releaseBlocked.await(5, TimeUnit.SECONDS))
            }
            mounted.set(false)
        }
        override fun mount(id: String) { calls += "mount"; mounted.set(true) }
        override fun forceStopPackage(name: String) { calls += "forceStop" }
        override fun startActivity(intent: Intent) { calls += "startActivity" }
    }

    private suspend fun firstHandoff(h: HomeWiringHarness, service: Service): Long {
        h.onClickUnmountSdCardAndRetry()
        val id = h.currentId()!!
        withTimeout(3000) { h.retryJob(id).join(); h.submissionJob().join() }
        assertEquals(InstallationStep.WAITING_USER_CONFIRMATION, h.cardStep())
        assertEquals(listOf("unmount-1", "forceStop", "startActivity"), service.calls.toList())
        assertEquals(RemountPhase.SYSTEM_UNKNOWN, h.pendingRecord()!!.phase)
        return id
    }

    private suspend fun awaitTerminal(h: HomeWiringHarness, expected: InstallationStep) {
        withTimeout(5000) { while (h.busy() || h.cardStep() != expected) delay(2) }
        delay(700) // a (wrong) asynchronous remount would have called mount() by now
    }

    private fun assertNoUncaught(h: HomeWiringHarness) =
        assertTrue("no unhandled coroutine exception: ${h.uncaught}", h.uncaught.isEmpty())

    @Test fun retryCancelledBeforeHandoffKeepsEarlierHandoff() = runBlocking {
        val h = HomeWiringHarness()
        val service = Service(blockUnmount = 2)
        PrivilegedProvider.connection.SERVICE = service
        OperationModeUtils.hook = { true } // READ_LOGS: WAITING_USER_CONFIRMATION with its retry button
        try {
            val id = firstHandoff(h, service)
            h.onClickRetryInstallation()
            assertTrue(service.blockedEntered.await(3, TimeUnit.SECONDS)) // retry registered, not handed off
            assertEquals(id, h.currentId())
            h.onClickCancelInstallationButton()
            assertTrue("cancel waits for the blocked retry request", h.busy())
            assertNull("no new task while the cancel is pending", h.newTask())
            service.releaseBlocked.countDown()
            awaitTerminal(h, InstallationStep.CANCEL_REQUESTED_CLEANUP_UNVERIFIED)
            // The earlier system DSU app is stopped (cancel policy for a handed-off task).
            assertEquals(listOf("unmount-1", "forceStop", "startActivity", "unmount-2", "forceStop"), service.calls.toList())
            assertEquals("no automatic remount", 0, service.calls.count { it == "mount" })
            val record = h.pendingRecord()
            assertNotNull("pending record kept", record)
            assertEquals(RemountPhase.SYSTEM_UNKNOWN, record!!.phase)
            assertNotNull("next task can start after the terminal", h.newTask())
            assertNoUncaught(h)
            println("HANDOFF cancelled retry: card=${h.cardStep()} calls=${service.calls} pending=${record.phase}")
        } finally {
            service.releaseBlocked.countDown()
            OperationModeUtils.hook = { false }
            h.stopHarness()
            PrivilegedProvider.connection.SERVICE = null
        }
    }

    @Test fun retryFailedBeforeHandoffKeepsEarlierHandoff() = runBlocking {
        val h = HomeWiringHarness()
        val service = Service()
        PrivilegedProvider.connection.SERVICE = service
        OperationModeUtils.hook = { true }
        try {
            firstHandoff(h, service)
            val failOnce = AtomicBoolean(true)
            h.persistHook = { if (failOnce.getAndSet(false)) throw java.io.IOException("pending record storage unavailable") }
            h.onClickRetryInstallation()
            awaitTerminal(h, InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED)
            assertTrue(h.cardError(), h.cardError().contains("pending record storage unavailable"))
            assertEquals("retry never reached the system", listOf("unmount-1", "forceStop", "startActivity"), service.calls.toList())
            assertEquals("no automatic remount", 0, service.calls.count { it == "mount" })
            val record = h.pendingRecord()
            assertNotNull("pending record kept", record)
            assertEquals(RemountPhase.SYSTEM_UNKNOWN, record!!.phase)
            // One terminal: the task is released, a late callback does not change the card.
            delay(100)
            assertEquals(InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED, h.cardStep())
            assertNotNull("next task can start", h.newTask())
            assertNoUncaught(h)
            println("HANDOFF failed retry: card=${h.cardStep()} detail=${h.cardError()} calls=${service.calls} pending=${record.phase}")
        } finally {
            OperationModeUtils.hook = { false }
            h.stopHarness()
            PrivilegedProvider.connection.SERVICE = null
        }
    }

    /** Positive case: a task that never handed anything off is a clean cancel and restores storage. */
    @Test fun cancelBeforeAnyHandoffStillRemounts() = runBlocking {
        val h = HomeWiringHarness()
        val service = Service(blockUnmount = 1)
        PrivilegedProvider.connection.SERVICE = service
        try {
            h.onClickUnmountSdCardAndRetry()
            assertTrue(service.blockedEntered.await(3, TimeUnit.SECONDS))
            h.onClickCancelInstallationButton()
            service.releaseBlocked.countDown()
            withTimeout(5000) { while (h.busy() || h.cardStep() != InstallationStep.ERROR_CANCELED) delay(2) }
            withTimeout(5000) { while (h.pendingRecord() != null) delay(5) }
            assertEquals(listOf("unmount-1", "mount"), service.calls.toList())
            assertNotNull(h.newTask())
            assertNoUncaught(h)
            println("HANDOFF none: card=ERROR_CANCELED calls=${service.calls} pending=null")
        } finally {
            service.releaseBlocked.countDown()
            h.stopHarness()
            PrivilegedProvider.connection.SERVICE = null
        }
    }
}
