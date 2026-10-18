package review3

import android.content.Intent
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import vegabobo.dsusideloader.IPrivilegedService
import vegabobo.dsusideloader.ReviewVolume
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider
import vegabobo.dsusideloader.ui.screen.home.InstallationCardAction

/** Uses the public callback factory bound by HomeScreen to the rendered immutable card. */
class CardOwnershipWiringTest {
    private class Service : IPrivilegedService {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val mounted = AtomicBoolean(true)
        override val volumes get() = listOf(ReviewVolume("public:179,1", if (mounted.get()) 2 else 0))
        override fun unmount(id: String) { mounted.set(false); calls += "unmount" }
        override fun mount(id: String) { mounted.set(true); calls += "mount" }
        override fun forceStopPackage(name: String) { calls += "forceStop" }
        override fun startActivity(intent: Intent) { calls += "startActivity" }
    }

    private suspend fun submitted(h: HomeWiringHarness): Long {
        h.onClickUnmountSdCardAndRetry()
        val id = h.currentId()!!
        withTimeout(3000) { h.retryJob(id).join(); h.submissionJob().join() }
        withTimeout(3000) { while (h.cardStep() != InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED) delay(2) }
        assertEquals(id, h.card().taskId)
        return id
    }

    @Test fun lateAcknowledgementCannotFinishANewerTaskWithTheSameCardStep() = runBlocking {
        val h = HomeWiringHarness(); val s = Service()
        PrivilegedProvider.connection.SERVICE = s
        try {
            val first = submitted(h)
            val oldClick = h.installationCardActionHandler(h.card())
            oldClick(InstallationCardAction.ACK_UNCONFIRMED)
            assertFalse(h.busy())
            val second = submitted(h)
            assertNotEquals(first, second)
            val job = h.taskJob(second)
            oldClick(InstallationCardAction.ACK_UNCONFIRMED)
            assertEquals(second, h.currentId())
            assertTrue("old ACK must not cancel the new task Job", job.isActive)
            assertEquals(InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED, h.cardStep())
            assertEquals(0, s.calls.count { it == "mount" })
            h.installationCardActionHandler(h.card())(InstallationCardAction.ACK_UNCONFIRMED)
            assertFalse("current task can still be acknowledged", h.busy())
            assertTrue(h.uncaught.toString(), h.uncaught.isEmpty())
            println("OWNERSHIP late ACK: task $first ignored while task $second remains active; fresh ACK works")
        } finally { h.stopHarness(); PrivilegedProvider.connection.SERVICE = null }
    }

    @Test fun endedCardCannotAcknowledgeOrRestoreStorageForTheNextTask() = runBlocking {
        val h = HomeWiringHarness(); val s = Service()
        PrivilegedProvider.connection.SERVICE = s
        try {
            submitted(h)
            val failOnce = AtomicBoolean(true)
            h.persistHook = { if (failOnce.getAndSet(false)) throw java.io.IOException("pending write failed") }
            h.onClickRetryInstallation()
            withTimeout(5000) { while (h.busy() || !h.cardError().contains("pending write failed")) delay(2) }
            val endedClick = h.installationCardActionHandler(h.card())
            val second = submitted(h)
            endedClick(InstallationCardAction.RESTORE_STORAGE)
            endedClick(InstallationCardAction.ACK_UNCONFIRMED)
            delay(100)
            assertEquals(second, h.currentId())
            assertTrue(h.taskJob(second).isActive)
            assertNotNull(h.pendingRecord())
            assertEquals(0, s.calls.count { it == "mount" })
            assertTrue(h.uncaught.toString(), h.uncaught.isEmpty())
            println("OWNERSHIP ended card: old restore/ACK ignored; next task active, mount=0")
        } finally { h.stopHarness(); PrivilegedProvider.connection.SERVICE = null }
    }

    @Test fun changedCardStepRejectsEarlierAcknowledgement() = runBlocking {
        val h = HomeWiringHarness(); val s = Service()
        PrivilegedProvider.connection.SERVICE = s
        try {
            submitted(h)
            val oldClick = h.installationCardActionHandler(h.card())
            h.onClickCancelInstallationButton()
            withTimeout(5000) { while (h.busy() || h.cardStep() != InstallationStep.CANCEL_REQUESTED_CLEANUP_UNVERIFIED) delay(2) }
            oldClick(InstallationCardAction.ACK_UNCONFIRMED)
            assertEquals(InstallationStep.CANCEL_REQUESTED_CLEANUP_UNVERIFIED, h.cardStep())
            assertNotNull(h.pendingRecord())
            assertEquals(0, s.calls.count { it == "mount" })
            assertTrue(h.uncaught.toString(), h.uncaught.isEmpty())
            println("OWNERSHIP changed step: late ACK cannot dismiss cleanup-unverified card")
        } finally { h.stopHarness(); PrivilegedProvider.connection.SERVICE = null }
    }
}
