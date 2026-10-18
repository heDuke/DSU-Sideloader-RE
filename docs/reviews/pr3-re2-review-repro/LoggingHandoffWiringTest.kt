package review3

import android.content.Intent
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import vegabobo.dsusideloader.IPrivilegedService
import vegabobo.dsusideloader.ReviewVolume
import vegabobo.dsusideloader.installer.privileged.RemountPhase
import vegabobo.dsusideloader.preferences.AppPrefs
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider
import vegabobo.dsusideloader.ui.screen.home.InstallationCardAction
import vegabobo.dsusideloader.util.CmdRunner
import vegabobo.dsusideloader.util.OperationModeUtils

/**
 * RE2 (PR #3 rereview): ordinary install entry (onConfirmInstallationSheet, not RetryEntry);
 * the request reached the system, then the synchronous log initialisation (real startLogging /
 * LogcatDiagnostic, CmdRunner.destroy faulted) throws. Same setup as LoggingHandoffReviewTest
 * with the full set of assertions: no mount, SYSTEM_UNKNOWN kept, single terminal "submitted,
 * unconfirmed" with detail, explicit restore works, next task starts, no leaked exceptions.
 */
class LoggingHandoffWiringTest {
    @Test fun logInitFailureAfterHandoffEndsUnconfirmedWithoutRemount() = runBlocking {
        val h = HomeWiringHarness()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val mountedState = AtomicBoolean(true)
        val handedAtFailure = AtomicBoolean(false)
        PrivilegedProvider.connection.SERVICE = object : IPrivilegedService {
            override val volumes get() = listOf(ReviewVolume("public:179,1", if (mountedState.get()) 2 else 0))
            override fun unmount(id: String) { calls += "unmount"; mountedState.set(false) }
            override fun mount(id: String) { calls += "mount"; mountedState.set(true) }
            override fun forceStopPackage(name: String) { calls += "forceStop" }
            override fun startActivity(intent: Intent) { calls += "startActivity" }
        }
        OperationModeUtils.hook = { true } // READ_LOGS: startLogging runs
        try {
            // A previous tracked request leaves a logger in the ViewModel (destroyed on the next start).
            h.onClickRetryInstallation()
            val previous = h.currentId()!!
            withTimeout(3000) { h.retryJob(previous).join(); h.submissionJob().join() }
            h.onClickCancelInstallationButton()
            withTimeout(5000) { while (h.busy() || h.cardStep() != InstallationStep.CANCEL_REQUESTED_CLEANUP_UNVERIFIED) delay(2) }
            h.onInstallationCardAction(InstallationCardAction.ACK_CLEANUP_INCOMPLETE)
            withTimeout(3000) { while (h.cardStep() != InstallationStep.NOT_INSTALLING) delay(2) }
            calls.clear()

            h.prefHook = { key -> key == AppPrefs.UMOUNT_SD }
            CmdRunner.destroyHook = {
                runBlocking { withTimeout(3000) { h.submissionJob().join() } } // handoff first
                handedAtFailure.set(h.hasHanded(h.currentId()!!))
                throw IOException("logcat transport close failed")
            }
            h.onConfirmInstallationSheet()
            withTimeout(5000) { while (h.busy() || h.cardStep() != InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED) delay(2) }
            withTimeout(3000) { while (!h.card().storageRestorePending) delay(2) }
            delay(700) // a (wrong) asynchronous remount would have called mount() by now
            assertTrue("handoff happened before the log error", handedAtFailure.get())
            assertEquals(listOf("unmount", "forceStop", "startActivity"), calls.toList())
            assertTrue(h.cardError(), h.cardError().contains("logcat transport close failed"))
            assertEquals("single terminal", InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED, h.cardStep())
            val record = h.pendingRecord()
            assertNotNull("record kept", record)
            assertEquals(RemountPhase.SYSTEM_UNKNOWN, record!!.phase)
            assertTrue("explicit restore offered", h.card().storageRestorePending)
            assertTrue("no unhandled coroutine exception: ${h.uncaught}", h.uncaught.isEmpty())

            // Only the explicit action restores storage.
            h.onInstallationCardAction(InstallationCardAction.RESTORE_STORAGE)
            withTimeout(5000) { while (h.pendingRecord() != null) delay(5) }
            assertEquals(1, calls.count { it == "mount" })
            assertNotNull("next task can start", h.newTask())
            assertTrue("no unhandled coroutine exception: ${h.uncaught}", h.uncaught.isEmpty())
            println("LOGGING handoff: card=INSTALL_SUBMITTED_UNCONFIRMED detail=${h.cardError()} calls=$calls record=${record.phase} restore=explicit")
        } finally {
            CmdRunner.destroyHook = {}
            OperationModeUtils.hook = { false }
            h.stopHarness()
            PrivilegedProvider.connection.SERVICE = null
        }
    }
}
