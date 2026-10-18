package vegabobo.dsusideloader.review

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vegabobo.dsusideloader.installer.privileged.AppStartDecision
import vegabobo.dsusideloader.installer.privileged.DsuSubmission
import vegabobo.dsusideloader.installer.privileged.DsuSubmissionStep
import vegabobo.dsusideloader.installer.privileged.DsuSubmitTarget
import vegabobo.dsusideloader.installer.privileged.PendingRemount
import vegabobo.dsusideloader.installer.privileged.PendingRemountStore
import vegabobo.dsusideloader.installer.privileged.RemountPhase
import vegabobo.dsusideloader.installer.privileged.RemountPolicy
import vegabobo.dsusideloader.installer.privileged.SdCardRemounter
import vegabobo.dsusideloader.installer.privileged.SystemDsuState
import vegabobo.dsusideloader.installer.privileged.VolumeOps
import vegabobo.dsusideloader.installer.task.InstallTaskGate
import vegabobo.dsusideloader.installer.task.TaskSteps

/**
 * Port of the second-review probes (docs/reviews/pr2-rereview-repro/ReReviewRegressionTest.kt
 * on docs/grok-handoff, reviewed head 0ef87b30) to the project's real interfaces.
 * RR3/RR4 call the production classes exactly like the probes. RR1/RR2 use the production
 * step launcher / submission instead of the reviewer's model of the old ViewModel boundary
 * and the old detached handler. All four failed at 0ef87b30.
 */
class ReReviewRegressionTest {
    private class Store(var record: PendingRemount?) : PendingRemountStore {
        override suspend fun read() = record
        override suspend fun write(record: PendingRemount?) {
            this.record = record
        }
    }

    @Test
    fun observedInstallingStateMustBlockRemountEvenAfterBootCountChanges() {
        val record = PendingRemount(listOf("public:179,1"), RemountPhase.SYSTEM_UNKNOWN, "7")
        val decision = RemountPolicy.onAppStart(record, "8", SystemDsuState.INSTALLING)
        assertTrue(decision is AppStartDecision.Prompt)
    }

    @Test
    fun unknownMountStateMustNotDiscardPendingRecord() = runBlocking {
        val store = Store(PendingRemount(listOf("public:179,1"), RemountPhase.SYSTEM_UNKNOWN, "7"))
        val ops = object : VolumeOps {
            override fun volumeIds() = listOf("public:179,1")
            override fun mount(id: String) {}
            override fun unmount(id: String) {}
            override fun isMounted(id: String): Boolean? = throw IllegalStateException("status query temporarily unavailable")
        }
        SdCardRemounter(store, verifyAttempts = 1).remountPending(ops, "probe")
        assertNotNull(store.record)
    }

    @Test
    fun cancellationDuringInitialPreferenceReadMustReleaseGate() = runBlocking {
        val gate = InstallTaskGate()
        val id = gate.begin()!!
        val token = Job()
        val readEntered = CompletableDeferred<Unit>()
        val neverReadyPreference = CompletableDeferred<Boolean>()
        var terminal = false
        val worker = TaskSteps.launch(
            CoroutineScope(SupervisorJob()),
            Dispatchers.Default + token,
            onUnsettled = {
                terminal = true
                gate.finish(id)
            },
        ) {
            readEntered.complete(Unit)
            neverReadyPreference.await() // readBoolPref() is now inside the step
        }
        readEntered.await()
        gate.requestCancel()
        token.cancel()
        worker.join()
        assertTrue(terminal)
        assertFalse(gate.isBusy())
    }

    @Test
    fun dsuAppRequestMustNotSubmitAfterCancellationHasFinished() = runBlocking {
        val gate = InstallTaskGate()
        val id = gate.begin(InstallTaskGate.Phase.DSU_APP)!!
        val token = Job()
        val readEntered = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val target = object : DsuSubmitTarget {
            override val volumeOps = object : VolumeOps {
                override fun volumeIds() = listOf("public:179,1")
                override fun unmount(id: String) {
                    calls += "unmount"
                }
                override fun mount(id: String) {}
            }
            override fun prepareDsuApp() {
                calls += "forceStop"
            }
            override fun startDsuInstall() {
                calls += "startActivity-after-cancel=${!gate.isCurrent(id)}"
            }
        }
        val store = object : PendingRemountStore {
            var record: PendingRemount? = null
            override suspend fun read(): PendingRemount? {
                readEntered.complete(Unit)
                releaseRead.await()
                return record
            }
            override suspend fun write(record: PendingRemount?) {
                this.record = record
            }
        }
        val submission = DsuSubmission(SdCardRemounter(store), unmountSdCard = true, bootId = "7", connect = { target })
        val job = TaskSteps.launch(CoroutineScope(SupervisorJob()), Dispatchers.IO + token, CoroutineStart.LAZY, onUnsettled = {}) {
            submission.submit()
        }
        val step = DsuSubmissionStep(id, submission, job)
        job.start()
        try {
            withTimeout(3000) { readEntered.await() }
            // ViewModel cancel order: mark cancel, cancel the task Job, stop and await the
            // submission, stop the DSU app, then finish the task.
            gate.requestCancel()
            token.cancel()
            assertFalse(step.cancelAndJoin())
            calls += "forceStop"
            gate.finish(id)
            calls += "cancel-finished"
            releaseRead.complete(Unit)
            delay(300)
            assertFalse(calls.toString(), calls.any { it.startsWith("startActivity") || it == "unmount" })
        } finally {
            releaseRead.complete(Unit)
        }
    }
}
