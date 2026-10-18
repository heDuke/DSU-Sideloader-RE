package review3

import android.content.Intent
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import vegabobo.dsusideloader.IPrivilegedService
import vegabobo.dsusideloader.ReviewVolume
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider
import vegabobo.dsusideloader.util.OperationModeUtils

/** Keeps the real ViewModel remount helper and real SdCardRemounter, with controlled IPC. */
class RetryHistoryReviewTest {
    @Test fun cancellingRetryBeforeHandoffMustPreserveEarlierSystemHandoff() = runBlocking {
        val h = HomeWiringHarness()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val unmounts = AtomicInteger()
        val secondUnmountEntered = CountDownLatch(1)
        val releaseSecondUnmount = CountDownLatch(1)
        val mounted = CountDownLatch(1)
        val mountedState = java.util.concurrent.atomic.AtomicBoolean(false)
        PrivilegedProvider.connection.SERVICE = object : IPrivilegedService {
            override val volumes get() = listOf(ReviewVolume("public:179,1", if (mountedState.get()) 2 else 0))
            override fun unmount(id: String) {
                val count = unmounts.incrementAndGet()
                calls += "unmount-$count"
                if (count == 2) {
                    secondUnmountEntered.countDown()
                    check(releaseSecondUnmount.await(5, TimeUnit.SECONDS))
                }
                mountedState.set(false)
            }
            override fun mount(id: String) { calls += "mount"; mountedState.set(true); mounted.countDown() }
            override fun forceStopPackage(name: String) { calls += "forceStop" }
            override fun startActivity(intent: Intent) { calls += "startActivity" }
        }
        // READ_LOGS granted: the actual UI state stays WAITING_USER_CONFIRMATION, with its retry button.
        OperationModeUtils.hook = { true }
        try {
            h.onClickUnmountSdCardAndRetry()
            val id = h.currentId()!!
            withTimeout(3000) { h.retryJob(id).join(); h.submissionJob().join() }
            assertEquals(InstallationStep.WAITING_USER_CONFIRMATION, h.cardStep())
            assertEquals(1, calls.count { it == "startActivity" })
            assertEquals(1, calls.count { it == "forceStop" })
            val previous = h.pendingRecord()
            assertNotNull(previous)
            println("REVIEW history before retry: phase=${previous!!.phase} calls=$calls")

            h.onClickRetryInstallation()
            assertTrue(secondUnmountEntered.await(3, TimeUnit.SECONDS))
            // The retry is registered, but it has not force-stopped the previous system app
            // or called startActivity. Its own reachedSystem flag is still false.
            h.onClickCancelInstallationButton()
            assertTrue("cancel must wait for blocked submission", h.busy())
            releaseSecondUnmount.countDown()
            withTimeout(3000) { while (h.busy()) delay(1) }
            withContext(Dispatchers.IO) { mounted.await(500, TimeUnit.MILLISECONDS) }
            // mount() is invoked before pending-record persistence; wait for that asynchronous
            // remount path before logging its final record (a fixed version need not mount).
            withTimeoutOrNull(1000) { if (calls.contains("mount")) while (h.pendingRecord() != null) delay(1) }
            println("REVIEW cancelled retry: card=${h.cardStep()} calls=$calls pending=${h.pendingRecord()} uncaught=${h.uncaught}")
            assertTrue(h.uncaught.toString(), h.uncaught.isEmpty())
            assertEquals("Cancelling a retry must not automatically remount storage from an earlier unconfirmed system handoff", 0, calls.count { it == "mount" })
            assertNotEquals("Latest request never reached the system does not prove the whole task was cleaned", InstallationStep.ERROR_CANCELED, h.cardStep())
        } finally {
            releaseSecondUnmount.countDown()
            OperationModeUtils.hook = { false }
            h.stopHarness()
            PrivilegedProvider.connection.SERVICE = null
        }
    }
    @Test fun failedRetryPersistenceMustNotResolveEarlierSystemHandoff() = runBlocking {
        val h = HomeWiringHarness()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val mounted = CountDownLatch(1)
        val mountedState = java.util.concurrent.atomic.AtomicBoolean(false)
        PrivilegedProvider.connection.SERVICE = object : IPrivilegedService {
            override val volumes get() = listOf(ReviewVolume("public:179,1", if (mountedState.get()) 2 else 0))
            override fun unmount(id: String) { calls += "unmount"; mountedState.set(false) }
            override fun mount(id: String) { calls += "mount"; mountedState.set(true); mounted.countDown() }
            override fun forceStopPackage(name: String) { calls += "forceStop" }
            override fun startActivity(intent: Intent) { calls += "startActivity" }
        }
        OperationModeUtils.hook = { true }
        try {
            h.onClickUnmountSdCardAndRetry()
            val id = h.currentId()!!
            withTimeout(3000) { h.retryJob(id).join(); h.submissionJob().join() }
            assertEquals(InstallationStep.WAITING_USER_CONFIRMATION, h.cardStep())
            assertNotNull(h.pendingRecord())
            val failOnce = java.util.concurrent.atomic.AtomicBoolean(true)
            // IOException from pending-record persistence, before unmount/forceStop/startActivity.
            h.persistHook = { if (failOnce.getAndSet(false)) throw java.io.IOException("pending record storage unavailable") }
            h.onClickRetryInstallation()
            withTimeout(3000) { while (h.busy()) delay(1) }
            withContext(Dispatchers.IO) { mounted.await(500, TimeUnit.MILLISECONDS) }
            // mount() is invoked before pending-record persistence; wait for that asynchronous
            // remount path before logging its final record (a fixed version need not mount).
            withTimeoutOrNull(1000) { if (calls.contains("mount")) while (h.pendingRecord() != null) delay(1) }
            println("REVIEW failed retry: card=${h.cardStep()} calls=$calls pending=${h.pendingRecord()} uncaught=${h.uncaught}")
            assertTrue(h.uncaught.toString(), h.uncaught.isEmpty())
            assertEquals("A failed new retry cannot prove an earlier system handoff ended", 0, calls.count { it == "mount" })
            assertNotNull("Earlier unconfirmed storage record must remain", h.pendingRecord())
        } finally {
            OperationModeUtils.hook = { false }
            h.stopHarness()
            PrivilegedProvider.connection.SERVICE = null
        }
    }

}
