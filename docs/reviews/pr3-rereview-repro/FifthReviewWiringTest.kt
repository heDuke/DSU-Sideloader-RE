package review3

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.util.OperationMode

/** Runs the extracted production methods, not a hand-written terminal-state model. */
class FifthReviewWiringTest {
    private fun assertCanStartNext(h: HomeWiringHarness, expected: InstallationStep) {
        assertFalse("terminal must release the task", h.busy())
        assertEquals(expected, h.cardStep())
        assertNotNull("next task can start", h.newTask())
    }

    @Test fun cancelAfterSuccessfulAdbScriptWriteStillCancelsAndCleansExactlyOnce() = runBlocking {
        val h = HomeWiringHarness()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = AtomicInteger()
        val dir = Files.createTempDirectory("dsu-fifth-adb-").toFile()
        h.session.mode = OperationMode.ADB
        h.storageManager.appContext.externalDirHook = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            dir
        }
        h.storageManager.onClean = { cleaned.incrementAndGet() }
        try {
            h.onConfirmInstallationSheet()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val job = h.taskJob(h.currentId()!!)
            h.onClickCancelInstallationButton()
            release.countDown()
            withTimeout(3000) { job.join() }
            assertTrue("production script writer completed", dir.resolve("install").isFile)
            assertCanStartNext(h, InstallationStep.ERROR_CANCELED)
            assertEquals(1, cleaned.get())
            assertEquals("cancel does not publish a script path", "", h.session.installationScriptPath)
            println("FIFTH cancel after successful write: ERROR_CANCELED, gate released, cleanup once")
        } finally {
            release.countDown()
            h.stopHarness()
            dir.deleteRecursively()
        }
    }

    @Test fun cancelThenWriteAndCleanupFailuresAreBothReported() = runBlocking {
        val h = HomeWiringHarness()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = AtomicInteger()
        h.session.mode = OperationMode.ADB
        h.storageManager.appContext.externalDirHook = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            null
        }
        h.storageManager.onClean = { cleaned.incrementAndGet(); throw IOException("SAF delete failed") }
        try {
            h.onConfirmInstallationSheet()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val job = h.taskJob(h.currentId()!!)
            h.onClickCancelInstallationButton()
            release.countDown()
            withTimeout(3000) { job.join() }
            val detail = h.cardError()
            assertTrue(detail, detail.contains("Error while cancelling: externalFilesDir cannot be null."))
            assertTrue(detail, detail.contains("Temporary files could not be removed: SAF delete failed"))
            assertCanStartNext(h, InstallationStep.ERROR_CANCELED)
            assertEquals(1, cleaned.get())
            println("FIFTH cancel + write/cleanup failure: both error details retained, gate released, cleanup once")
        } finally {
            release.countDown()
            h.stopHarness()
        }
    }

    @Test fun duplicatePreparationTerminalCallbackDoesNotRepeatCleanup() = runBlocking {
        val h = HomeWiringHarness()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = AtomicInteger()
        h.storageManager.onClean = {
            cleaned.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            val id = h.newTask()!!
            h.preparationStopped(id)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            h.preparationStopped(id)
            assertNull("no next task before cleanup finished", h.newTask())
            release.countDown()
            withTimeout(3000) { while (h.busy() || h.cardStep() != InstallationStep.ERROR_CANCELED) delay(1) }
            assertEquals(1, cleaned.get())
            assertCanStartNext(h, InstallationStep.ERROR_CANCELED)
            println("FIFTH duplicate terminal: cleanup exactly once, no early unlock")
        } finally {
            release.countDown()
            h.stopHarness()
        }
    }

    @Test fun scriptWriteFailureWithoutCancelReleasesGateAsError() = runBlocking {
        val h = HomeWiringHarness()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.session.mode = OperationMode.ADB
        h.storageManager.appContext.externalDirHook = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            null
        }
        try {
            h.onConfirmInstallationSheet()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val id = h.currentId()!!
            val job = h.taskJob(id)
            release.countDown()
            withTimeout(3000) { job.join() }
            assertTrue(h.cardError(), h.cardError().contains("externalFilesDir cannot be null."))
            assertCanStartNext(h, InstallationStep.ERROR)
            println("FIFTH write failure without cancel: ERROR, gate released")
        } finally {
            release.countDown()
            h.stopHarness()
        }
    }
}
