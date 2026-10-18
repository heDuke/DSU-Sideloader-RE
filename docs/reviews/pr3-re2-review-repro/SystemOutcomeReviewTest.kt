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
import vegabobo.dsusideloader.preferences.AppPrefs
import vegabobo.dsusideloader.service.PrivilegedProvider
import vegabobo.dsusideloader.util.CmdRunner
import vegabobo.dsusideloader.util.OperationModeUtils

/** Contrasts app-side RE2 with an actual line through production LogcatDiagnostic. */
class SystemOutcomeReviewTest {
    @Test fun explicitSystemCancellationStillUsesConfirmedTerminalPath() = runBlocking {
        val h = HomeWiringHarness()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val mounted = AtomicBoolean(true)
        val handedBeforeLog = AtomicBoolean(false)
        val emitted = AtomicBoolean(false)
        PrivilegedProvider.connection.SERVICE = object : IPrivilegedService {
            override val volumes get() = listOf(ReviewVolume("public:179,1", if (mounted.get()) 2 else 0))
            override fun unmount(id: String) { calls += "unmount"; mounted.set(false) }
            override fun mount(id: String) { calls += "mount"; mounted.set(true) }
            override fun forceStopPackage(name: String) { calls += "forceStop" }
            override fun startActivity(intent: Intent) { calls += "startActivity" }
        }
        OperationModeUtils.hook = { true }
        h.prefHook = { it == AppPrefs.UMOUNT_SD }
        CmdRunner.linesHook = { _, receive ->
            runBlocking { withTimeout(3000) { h.submissionJob().join() } }
            handedBeforeLog.set(h.hasHanded(h.currentId()!!))
            emitted.set(true)
            receive("DynamicSystemInstallationService: status: NOT_STARTED, cause: INSTALL_CANCELLED")
        }
        try {
            h.onConfirmInstallationSheet()
            withTimeout(5000) {
                while (h.busy() || h.cardStep() != InstallationStep.ERROR_CANCELED || h.pendingRecord() != null) delay(2)
            }
            assertTrue("log emitted after handoff", emitted.get() && handedBeforeLog.get())
            assertEquals(listOf("unmount", "forceStop", "startActivity", "mount"), calls.toList())
            assertTrue(h.cardError(), h.cardError().contains("INSTALL_CANCELLED"))
            assertFalse("system terminal is not blanket-converted to unconfirmed", h.cardStep() == InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED)
            assertTrue(h.uncaught.toString(), h.uncaught.isEmpty())
            assertNotNull(h.newTask())
            println("SYSTEM outcome: handoff=true actual parsed INSTALL_CANCELLED -> ERROR_CANCELED mount=1 pending=null uncaught=[]")
        } finally {
            CmdRunner.linesHook = { _, _ -> }
            OperationModeUtils.hook = { false }
            h.stopHarness()
            PrivilegedProvider.connection.SERVICE = null
        }
    }
}
