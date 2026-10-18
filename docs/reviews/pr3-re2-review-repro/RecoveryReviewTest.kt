package review3

import android.content.Intent
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import vegabobo.dsusideloader.IPrivilegedService
import vegabobo.dsusideloader.ReviewProgress
import vegabobo.dsusideloader.ReviewVolume
import vegabobo.dsusideloader.installer.privileged.RemountPhase
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider
import vegabobo.dsusideloader.ui.screen.home.InstallationCardAction
import vegabobo.dsusideloader.util.OperationModeUtils

/** Actual extracted refresh/reset/restore/ACK methods, IPC and root status are boundaries. */
class RecoveryReviewTest {
    private class Service : IPrivilegedService {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val unmounts = AtomicInteger()
        val blockEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val mounted = AtomicBoolean(true)
        var blockUnmount = -1
        var launchFailure = false
        var removeResult = true
        var installedAfterRemove = false
        var progressStatus = 0
        override val installationProgress get() = ReviewProgress(progressStatus)
        override val isInstalled get() = installedAfterRemove
        override val volumes get() = listOf(ReviewVolume("public:179,1", if (mounted.get()) 2 else 0))
        override fun unmount(id: String) {
            val count = unmounts.incrementAndGet()
            calls += "unmount-$count"
            if (count == blockUnmount) { blockEntered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            mounted.set(false)
        }
        override fun mount(id: String) { calls += "mount"; mounted.set(true) }
        override fun forceStopPackage(name: String) { calls += "forceStop" }
        override fun startActivity(intent: Intent) { calls += "startActivity"; if (launchFailure) throw IOException("launch outcome unknown") }
        override fun abort(): Boolean { calls += "abort"; return true }
        override fun remove(): Boolean { calls += "remove"; return removeResult }
    }

    private suspend fun awaitCard(h: HomeWiringHarness, step: InstallationStep, pending: Boolean? = null) =
        withTimeout(5000) { while (h.busy() || h.cardStep() != step || (pending != null && h.card().storageRestorePending != pending)) delay(2) }

    private suspend fun first(h: HomeWiringHarness): Long {
        h.onClickUnmountSdCardAndRetry()
        val id = h.currentId()!!
        withTimeout(3000) { h.retryJob(id).join(); h.submissionJob().join() }
        assertEquals(InstallationStep.WAITING_USER_CONFIRMATION, h.cardStep())
        assertEquals(RemountPhase.SYSTEM_UNKNOWN, h.pendingRecord()!!.phase)
        return id
    }

    private suspend fun failedRetry(h: HomeWiringHarness) {
        val failOnce = AtomicBoolean(true)
        h.persistHook = { if (failOnce.getAndSet(false)) throw IOException("pending record write failed") }
        h.onClickRetryInstallation()
        awaitCard(h, InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED, true)
        assertTrue(h.cardError(), h.cardError().contains("pending record write failed"))
    }

    private fun checkErrors(h: HomeWiringHarness) = assertTrue(h.uncaught.toString(), h.uncaught.isEmpty())
    private fun close(h: HomeWiringHarness, service: Service) {
        service.release.countDown(); h.stopHarness(); PrivilegedProvider.connection.SERVICE = null
        OperationModeUtils.hook = { false }
    }

    @Test fun endedUnknownCardAcknowledgesThenExplicitlyRestoresStorage() = runBlocking {
        val h = HomeWiringHarness(); val s = Service()
        PrivilegedProvider.connection.SERVICE = s; OperationModeUtils.hook = { true }
        try {
            first(h); failedRetry(h)
            assertFalse(h.busy())
            assertEquals(0, s.calls.count { it == "mount" })
            assertNotNull(h.pendingRecord())
            // Normal click on the new ended-task card: no current task exists to finish.
            h.onInstallationCardAction(InstallationCardAction.ACK_UNCONFIRMED)
            awaitCard(h, InstallationStep.STORAGE_RESTORE_PENDING, true)
            assertEquals(0, s.calls.count { it == "mount" })
            h.onInstallationCardAction(InstallationCardAction.RESTORE_STORAGE)
            withTimeout(5000) { while (h.pendingRecord() != null || h.card().storageRestorePending) delay(2) }
            assertEquals(1, s.calls.count { it == "mount" })
            assertNotNull(h.newTask())
            checkErrors(h)
            println("RECOVERY ended card: pending=true; ACK -> restore prompt; explicit restore -> mount=1 pending=null")
        } finally { close(h, s) }
    }

    @Test fun startActivityFailureKeepsUnknownDetailAndRestoreState() = runBlocking {
        val h = HomeWiringHarness(); val s = Service(); s.launchFailure = true
        PrivilegedProvider.connection.SERVICE = s
        try {
            h.onClickUnmountSdCardAndRetry()
            awaitCard(h, InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED, true)
            assertTrue(h.cardError(), h.cardError().contains("launch outcome unknown"))
            assertEquals(RemountPhase.SYSTEM_UNKNOWN, h.pendingRecord()!!.phase)
            assertEquals(0, s.calls.count { it == "mount" })
            assertNotNull(h.newTask())
            checkErrors(h)
            println("RECOVERY startActivity throws: unknown + error detail + restore state, mount=0")
        } finally { close(h, s) }
    }

    @Test fun rootVerifiedCleanupRestoresAfterRetryCancellation() = runBlocking {
        val h = HomeWiringHarness(); val s = Service(); h.session.rootMode = true
        PrivilegedProvider.connection.SERVICE = s
        try {
            first(h); s.blockUnmount = 2
            h.onClickRetryInstallation(); assertTrue(s.blockEntered.await(3, TimeUnit.SECONDS))
            h.onClickCancelInstallationButton(); assertTrue(h.busy())
            s.release.countDown()
            awaitCard(h, InstallationStep.ERROR_CANCELED)
            withTimeout(5000) { while (h.pendingRecord() != null) delay(2) }
            assertEquals(1, s.calls.count { it == "mount" })
            assertTrue(s.calls.toString(), s.calls.indexOf("abort") < s.calls.indexOf("remove"))
            assertTrue(s.calls.toString(), s.calls.indexOf("remove") < s.calls.indexOf("mount"))
            assertNotNull(h.newTask()); checkErrors(h)
            println("RECOVERY root clean: $s ${s.calls}, pending=null")
        } finally { close(h, s) }
    }

    @Test fun rootFailedCleanupKeepsUnknownRecordAndDoesNotMount() = runBlocking {
        val h = HomeWiringHarness(); val s = Service(); h.session.rootMode = true
        s.removeResult = false; s.installedAfterRemove = true
        PrivilegedProvider.connection.SERVICE = s
        try {
            first(h); s.blockUnmount = 2
            h.onClickRetryInstallation(); assertTrue(s.blockEntered.await(3, TimeUnit.SECONDS))
            h.onClickCancelInstallationButton(); s.release.countDown()
            awaitCard(h, InstallationStep.ERROR_CLEANUP_INCOMPLETE)
            assertEquals(0, s.calls.count { it == "mount" })
            assertEquals(RemountPhase.SYSTEM_UNKNOWN, h.pendingRecord()!!.phase)
            assertNotNull(h.newTask()); checkErrors(h)
            println("RECOVERY root incomplete: ${s.calls}, mount=0 pending=SYSTEM_UNKNOWN")
        } finally { close(h, s) }
    }

    @Test fun explicitRestoreIsBlockedWhileRootReportsInstalling() = runBlocking {
        val h = HomeWiringHarness(); val s = Service(); h.session.rootMode = true
        PrivilegedProvider.connection.SERVICE = s
        try {
            first(h); failedRetry(h); s.progressStatus = 1
            h.onInstallationCardAction(InstallationCardAction.RESTORE_STORAGE)
            withTimeout(5000) { while (!h.card().storageRestoreBlocked) delay(2) }
            assertEquals(0, s.calls.count { it == "mount" })
            assertNotNull(h.pendingRecord())
            s.progressStatus = 0
            h.onInstallationCardAction(InstallationCardAction.RESTORE_STORAGE)
            withTimeout(5000) { while (h.pendingRecord() != null || h.card().storageRestorePending) delay(2) }
            assertEquals(1, s.calls.count { it == "mount" })
            checkErrors(h)
            println("RECOVERY explicit restore: installing blocked; idle + explicit click restores")
        } finally { close(h, s) }
    }
}
