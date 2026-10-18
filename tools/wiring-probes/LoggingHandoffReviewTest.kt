package review3

import android.content.Intent
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import vegabobo.dsusideloader.IPrivilegedService
import vegabobo.dsusideloader.ReviewVolume
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.preferences.AppPrefs
import vegabobo.dsusideloader.service.PrivilegedProvider
import vegabobo.dsusideloader.ui.screen.home.InstallationCardAction
import vegabobo.dsusideloader.util.CmdRunner
import vegabobo.dsusideloader.util.OperationModeUtils

/** Actual onConfirm/startLogging/LogcatDiagnostic.destroy, only the transport close is faulted. */
class LoggingHandoffReviewTest {
    @Test fun loggingInitializationErrorAfterHandoffMustNotRemountUnconfirmedStorage() = runBlocking {
        val h = HomeWiringHarness()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val mounted = CountDownLatch(1)
        val mountedState = AtomicBoolean(true)
        val handedAtFailure = AtomicBoolean(false)
        PrivilegedProvider.connection.SERVICE = object : IPrivilegedService {
            override val volumes get() = listOf(ReviewVolume("public:179,1", if (mountedState.get()) 2 else 0))
            override fun unmount(id: String) { calls += "unmount"; mountedState.set(false) }
            override fun mount(id: String) { calls += "mount"; mountedState.set(true); mounted.countDown() }
            override fun forceStopPackage(name: String) { calls += "forceStop" }
            override fun startActivity(intent: Intent) { calls += "startActivity" }
        }
        OperationModeUtils.hook = { true }
        try {
            // A normal prior tracked request leaves the real logger object in the ViewModel.
            h.onClickRetryInstallation()
            val previous = h.currentId()!!
            withTimeout(3000) { h.retryJob(previous).join(); h.submissionJob().join() }
            h.onClickCancelInstallationButton()
            withTimeout(5000) { while (h.busy() || h.cardStep() != InstallationStep.CANCEL_REQUESTED_CLEANUP_UNVERIFIED) delay(2) }
            h.installationCardActionHandler(h.card())(InstallationCardAction.ACK_CLEANUP_INCOMPLETE)
            withTimeout(3000) { while (h.cardStep() != InstallationStep.NOT_INSTALLING) delay(2) }
            calls.clear()

            // New ordinary install, not RetryEntry: preference reads/preparation/handoff are real
            // extracted methods, with Preparation's file I/O substituted as in the original probe.
            h.prefHook = { key -> key == AppPrefs.UMOUNT_SD }
            CmdRunner.destroyHook = {
                // LogcatDiagnostic.destroy is synchronous within startLogging. Let the new
                // sibling request finish handing off before its transport close fails.
                runBlocking { withTimeout(3000) { h.submissionJob().join() } }
                handedAtFailure.set(h.hasHanded(h.currentId()!!))
                throw IOException("logcat transport close failed")
            }
            h.onConfirmInstallationSheet()
            withTimeout(5000) { while (h.busy() || !h.cardError().contains("logcat transport close failed")) delay(2) }
            withContext(Dispatchers.IO) { mounted.await(500, TimeUnit.MILLISECONDS) }
            withTimeoutOrNull(1000) { if (calls.contains("mount")) while (h.pendingRecord() != null) delay(1) }
            println("REVIEW logging handoff: handedAtFailure=${handedAtFailure.get()} card=${h.cardStep()} calls=$calls pending=${h.pendingRecord()} uncaught=${h.uncaught}")
            assertTrue("system handoff must have happened before the injected log error", handedAtFailure.get())
            assertTrue(h.uncaught.toString(), h.uncaught.isEmpty())
            assertEquals("An app-side log initialization failure cannot confirm system installation ended", 0, calls.count { it == "mount" })
            assertEquals(InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED, h.cardStep())
            assertNotNull(h.pendingRecord())
        } finally {
            CmdRunner.destroyHook = {}
            OperationModeUtils.hook = { false }
            h.stopHarness()
            PrivilegedProvider.connection.SERVICE = null
        }
    }
}
