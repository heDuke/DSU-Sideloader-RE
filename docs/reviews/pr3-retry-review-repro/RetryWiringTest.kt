package review3

import android.content.Intent
import com.topjohnwu.superuser.Shell
import java.nio.file.Files
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
import vegabobo.dsusideloader.util.OperationMode

/**
 * Retry entries through the extracted production methods (onClickRetryInstallation,
 * onClickUnmountSdCardAndRetry, onClickSetSeLinuxPermissive, createRetryEntry and the
 * installation/cancel methods they reach), with the production RetryEntry/TaskSteps/gate.
 * Framework boundaries are substituted; NOT an Android ViewModel/lifecycle/Binder test.
 */
class RetryWiringTest {
    private fun assertNoUncaught(h: HomeWiringHarness) {
        Thread.sleep(50)
        assertTrue("no unhandled coroutine exception: ${h.uncaught}", h.uncaught.isEmpty())
    }

    private fun waitIdle(h: HomeWiringHarness) {
        val deadline = System.currentTimeMillis() + 5000
        while (h.busy() && System.currentTimeMillis() < deadline) Thread.sleep(2)
    }

    private fun assertCanStartNext(h: HomeWiringHarness, expected: InstallationStep) {
        waitIdle(h)
        // The card is updated right after the gate is released.
        val deadline = System.currentTimeMillis() + 3000
        while (h.cardStep() != expected && System.currentTimeMillis() < deadline) Thread.sleep(2)
        assertFalse("terminal must release the task", h.busy())
        assertEquals(expected, h.cardStep())
        assertNotNull("next task can start", h.newTask())
    }

    private class Service : IPrivilegedService {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val running = AtomicInteger()
        val maxParallel = AtomicInteger()
        @Volatile var startGate: CountDownLatch? = null
        override val volumes = listOf(ReviewVolume("public:179,1"))
        override fun mount(id: String) { calls += "mount" }
        override fun unmount(id: String) { calls += "unmount" }
        override fun forceStopPackage(name: String) { calls += "forceStop" }
        override fun startActivity(intent: Intent) {
            maxParallel.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            try {
                startGate?.await(5, TimeUnit.SECONDS)
                calls += "startActivity"
        } finally {
                running.decrementAndGet()
            }
        }
    }

    @Test fun adbRetryWriteFailureRunsOffCallerThreadAndEndsOnce() {
        val h = HomeWiringHarness()
        val ioThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        h.session.mode = OperationMode.ADB
        h.storageManager.appContext.externalDirHook = { ioThread.set(Thread.currentThread()); null }
        try {
            h.onClickRetryInstallation()
            assertCanStartNext(h, InstallationStep.ERROR)
            assertNotNull(ioThread.get())
            assertNotSame("script write must not run on the tapping thread", Thread.currentThread(), ioThread.get())
            assertTrue(h.cardError(), h.cardError().contains("externalFilesDir"))
            println("RETRY adb write failure: ERROR, off caller thread=${ioThread.get().name}, gate released")
            assertNoUncaught(h)
        } finally {
            h.stopHarness()
        }
    }

    @Test fun repeatedRetryTapsStartOneTaskAndOneWrite() {
        val h = HomeWiringHarness()
        val writes = AtomicInteger()
        val release = CountDownLatch(1)
        val dir = Files.createTempDirectory("dsu-retry-adb-").toFile()
        h.session.mode = OperationMode.ADB
        h.storageManager.appContext.externalDirHook = {
            writes.incrementAndGet()
            check(release.await(5, TimeUnit.SECONDS))
            dir
        }
        try {
            h.onClickRetryInstallation()
            val first = h.currentId()
            repeat(5) { h.onClickRetryInstallation(); h.onClickUnmountSdCardAndRetry() }
            assertEquals(first, h.currentId())
            release.countDown()
            assertCanStartNext(h, InstallationStep.REQUIRES_ADB_CMD_TO_CONTINUE)
            assertEquals(1, writes.get())
            println("RETRY repeated taps: one task, writes=${writes.get()}, ADB card, gate released")
            assertNoUncaught(h)
        } finally {
            release.countDown()
            h.stopHarness()
            dir.deleteRecursively()
        }
    }

    @Test fun cancelDuringSeLinuxRemedyCleansUpBeforeRelease() = runBlocking {
        val h = HomeWiringHarness()
        val cleanEntered = CountDownLatch(1)
        val releaseClean = CountDownLatch(1)
        val cleaned = AtomicInteger()
        val shellThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        Shell.hook = { cmd -> shellThread.set(Thread.currentThread()); assertEquals("setenforce 0", cmd); Shell.Result(true) }
        h.storageManager.onClean = {
            cleanEntered.countDown()
            check(releaseClean.await(5, TimeUnit.SECONDS))
            cleaned.incrementAndGet()
        }
        try {
            h.onClickSetSeLinuxPermissive()
            val taskJob = h.taskJob(h.currentId()!!)
            withTimeout(3000) { while (shellThread.get() == null) delay(2) }
            assertNotSame(Thread.currentThread(), shellThread.get())
            h.onClickCancelInstallationButton() // during the 5 s delay
            assertTrue(cleanEntered.await(3, TimeUnit.SECONDS))
            assertNull("locked until the cleanup finished", h.newTask())
            releaseClean.countDown()
            withTimeout(3000) { taskJob.join() }
            assertCanStartNext(h, InstallationStep.ERROR_CANCELED)
            assertEquals(1, cleaned.get())
            println("RETRY SELinux remedy cancelled: ERROR_CANCELED after cleanup (once), gate released")
            assertNoUncaught(h)
        } finally {
            releaseClean.countDown()
            Shell.hook = { Shell.Result(true) }
            h.stopHarness()
        }
    }

    @Test fun dsuAppRetryNeverSubmitsInParallelAndCancelKeepsOrdering() = runBlocking {
        val h = HomeWiringHarness()
        val service = Service()
        PrivilegedProvider.connection.SERVICE = service
        try {
            // First request (new task) and repeated taps while it is in flight.
            service.startGate = CountDownLatch(1)
            h.onClickUnmountSdCardAndRetry()
            val id = h.currentId()!!
            withTimeout(3000) { while (service.running.get() == 0) delay(2) }
            assertTrue("unmount flag set inside the step", h.session.preferences.isUnmountSdCard)
            repeat(5) { h.onClickRetryInstallation() }
            service.startGate!!.countDown()
            withTimeout(3000) { while (h.cardStep() != InstallationStep.INSTALL_SUBMITTED_UNCONFIRMED) delay(2) }
            assertEquals(1, service.calls.count { it == "startActivity" })

            // "Try again" within the same task, cancelled while it reads its parameters.
            val bootEntered = CountDownLatch(1)
            val releaseBoot = CountDownLatch(1)
            h.bootIdHook = { bootEntered.countDown(); check(releaseBoot.await(5, TimeUnit.SECONDS)); "7" }
            service.startGate = null
            h.onClickRetryInstallation()
            assertTrue(bootEntered.await(3, TimeUnit.SECONDS))
            assertEquals(id, h.currentId())
            h.onClickRetryInstallation() // ignored: retry in flight
            val stopsBeforeCancel = service.calls.count { it == "forceStop" } // prepareDsuApp also force-stops
            h.onClickCancelInstallationButton()
            delay(100)
            assertTrue("cancel path waits for the retry step", h.busy())
            assertEquals(
                "no stop of the DSU app by the cancel path before the retry stopped",
                stopsBeforeCancel,
                service.calls.count { it == "forceStop" },
            )
            releaseBoot.countDown()
            waitIdle(h)
            assertEquals("no new request after the cancel", 1, service.calls.count { it == "startActivity" })
            assertEquals(1, service.maxParallel.get())
            assertEquals(service.calls.toString(), stopsBeforeCancel + 1, service.calls.count { it == "forceStop" })
            assertCanStartNext(h, InstallationStep.CANCEL_REQUESTED_CLEANUP_UNVERIFIED)
            println("RETRY dsu app: calls=${service.calls} maxParallel=${service.maxParallel.get()} card=CANCEL_REQUESTED_CLEANUP_UNVERIFIED")
            assertNoUncaught(h)
        } finally {
            PrivilegedProvider.connection.SERVICE = null
            h.stopHarness()
        }
    }

    @Test fun retryReadFailureBeforeDsuAppIsAnError() {
        val h = HomeWiringHarness()
        h.bootIdHook = { throw SecurityException("Settings.Global read failed") }
        try {
            h.onClickRetryInstallation()
            assertCanStartNext(h, InstallationStep.ERROR)
            assertTrue(h.cardError(), h.cardError().contains("Settings.Global read failed"))
            println("RETRY parameter read failure: ERROR, gate released")
            assertNoUncaught(h)
        } finally {
            h.stopHarness()
        }
    }
}
