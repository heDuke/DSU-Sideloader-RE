package review3

import android.content.Intent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import vegabobo.dsusideloader.IPrivilegedService
import vegabobo.dsusideloader.ReviewVolume
import vegabobo.dsusideloader.service.PrivilegedProvider

/** Executes extracted production methods with controlled external boundaries, not an Android ViewModel. */
class ThirdReviewWiringTest {
    @Test fun preparationCleanupMustFinishBeforeNewTaskCanCreateFiles() {
        val h = HomeWiringHarness()
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupDone = CountDownLatch(1)
        h.storageManager.onClean = {
            cleanupEntered.countDown()
            releaseCleanup.await(5, TimeUnit.SECONDS)
            h.storageManager.files.clear()
            cleanupDone.countDown()
        }
        try {
            val first = h.newTask()!!
            h.preparationStopped(first)
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS))
            val second = h.newTask()
            h.storageManager.files.add("new-task-image.img")
            releaseCleanup.countDown()
            assertTrue(cleanupDone.await(3, TimeUnit.SECONDS))
            val deleted = "new-task-image.img" !in h.storageManager.files
            println("THIRD PROBE preparation cleanup: newTaskAdmitted=${second != null} newTaskFileDeleted=$deleted")
            assertNull("Gate must remain held until workspace cleanup is done", second)
            assertFalse("Old cleanup must not delete files owned by the next task", deleted)
        } finally {
            releaseCleanup.countDown()
            h.stopHarness()
        }
    }

    @Test fun cancellingBeforeSubmissionPublicationMustNotBorrowNextTaskJob() = runBlocking {
        val h = HomeWiringHarness()
        val bootReadEntered = CountDownLatch(1)
        val releaseBootRead = CountDownLatch(1)
        val started = CountDownLatch(1)
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val first = h.newTask()!!
        h.bootIdHook = {
            bootReadEntered.countDown()
            releaseBootRead.await(5, TimeUnit.SECONDS)
            "7"
        }
        PrivilegedProvider.connection.SERVICE = object : IPrivilegedService {
            override val volumes = emptyList<ReviewVolume>()
            override fun mount(id: String) {}
            override fun unmount(id: String) {}
            override fun forceStopPackage(name: String) { calls += "forceStop" }
            override fun startActivity(intent: Intent) {
                calls += "startActivity-from-old-task=$first currentTask=${h.currentId()}"
                started.countDown()
            }
        }
        val worker = Thread { h.beginSubmission(first) }
        try {
            worker.start()
            assertTrue(bootReadEntered.await(3, TimeUnit.SECONDS))
            // Actual ViewModel cancellation method, while phase=DSU_APP and the step is not published yet.
            h.onClickCancelInstallationButton()
            withTimeout(3000) { while (h.busy()) delay(1) }
            val second = h.newTask()!!
            calls += "cancel-finished-next-task=$second"
            releaseBootRead.countDown()
            worker.join(3000)
            val lateStart = withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) }
            println("THIRD PROBE submission publication: $calls")
            assertFalse("Cancelled old task must not launch a submission using the next task Job", lateStart)
        } finally {
            releaseBootRead.countDown()
            worker.join(3000)
            h.stopHarness()
            PrivilegedProvider.connection.SERVICE = null
        }
    }
}
