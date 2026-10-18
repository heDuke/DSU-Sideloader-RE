package review2

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import vegabobo.dsusideloader.IPrivilegedService
import vegabobo.dsusideloader.ReviewVolume
import vegabobo.dsusideloader.installer.privileged.*
import vegabobo.dsusideloader.installer.task.InstallTaskGate
import vegabobo.dsusideloader.model.Session
import vegabobo.dsusideloader.service.PrivilegedProvider

class ReReviewRegressionTest {
    private class Store(var record: PendingRemount?) : PendingRemountStore {
        override suspend fun read() = record
        override suspend fun write(record: PendingRemount?) { this.record = record }
    }

    @Test fun observedInstallingStateMustBlockRemountEvenAfterBootCountChanges() {
        val record = PendingRemount(listOf("public:179,1"), RemountPhase.SYSTEM_UNKNOWN, "7")
        val decision = RemountPolicy.onAppStart(record, "8", SystemDsuState.INSTALLING)
        println("PROBE reboot plus observed active install: $decision")
        assertTrue("A changed boot counter must not override known current installation activity", decision is AppStartDecision.Prompt)
    }

    @Test fun unknownMountStateMustNotDiscardPendingRecord() = runBlocking {
        val store = Store(PendingRemount(listOf("public:179,1"), RemountPhase.SYSTEM_UNKNOWN, "7"))
        val ops = object : VolumeOps {
            override fun volumeIds() = listOf("public:179,1")
            override fun mount(id: String) {}
            override fun unmount(id: String) {}
            override fun isMounted(id: String): Boolean? = throw IllegalStateException("status query temporarily unavailable")
        }
        val remaining = SdCardRemounter(store, verifyAttempts = 1).remountPending(ops, "probe")
        println("PROBE unknown mounted state: remaining=$remaining record=${store.record}")
        assertNotNull("Unknown mount completion must retain pending recovery responsibility", store.record)
    }

    @Test fun cancellationDuringInitialPreferenceReadMustReleaseGate() = runBlocking {
        // Exact placement from HomeViewModel: preference suspension precedes try/catch.
        // This probes coroutine semantics with the production gate; it does not instantiate Android ViewModel.
        val gate = InstallTaskGate()
        val id = gate.begin()!!
        val token = Job()
        val readEntered = CompletableDeferred<Unit>()
        val neverReadyPreference = CompletableDeferred<Boolean>()
        var terminal = false
        val worker = launch(Dispatchers.Default + token) {
            readEntered.complete(Unit)
            neverReadyPreference.await() // readBoolPref() outside the try block in the ViewModel
            try {
                yield()
            } catch (e: CancellationException) {
                terminal = true
                gate.finish(id)
            }
        }
        readEntered.await()
        gate.requestCancel()
        token.cancel()
        worker.join()
        println("PROBE cancel at initial preferences: terminal=$terminal busy=${gate.isBusy()}")
        assertFalse("Cancelled preparation must reach a terminal outcome and release the gate", gate.isBusy())
    }

    @Test fun dsuAppRequestMustNotSubmitAfterCancellationHasFinished() = runBlocking {
        // Executes actual DsuInstallationHandler and actual PrivilegedProvider against controlled I/O.
        val gate = InstallTaskGate()
        val id = gate.begin(InstallTaskGate.Phase.DSU_APP)!!
        val token = Job()
        val readEntered = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val submitted = CountDownLatch(1)
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val service = object : IPrivilegedService {
            override val volumes = listOf(ReviewVolume("public:179,1"))
            override fun unmount(id: String) { calls += "unmount" }
            override fun mount(id: String) {}
            override fun forceStopPackage(name: String) { calls += "forceStop" }
            override fun startActivity(intent: android.content.Intent) {
                calls += "startActivity-after-cancel=${!gate.isCurrent(id)}"
                submitted.countDown()
            }
        }
        val store = object : PendingRemountStore {
            var record: PendingRemount? = null
            override suspend fun read(): PendingRemount? {
                readEntered.complete(Unit)
                releaseRead.await()
                return record
            }
            override suspend fun write(record: PendingRemount?) { this.record = record }
        }
        PrivilegedProvider.connection.SERVICE = service
        try {
            DsuInstallationHandler(Session(), SdCardRemounter(store)).startInstallation()
            withTimeout(3000) { readEntered.await() }
            // Same ordering as ViewModel's non-root cancel: mark cancel, cancel preparation/log token,
            // stop DSU app, then finish the task with unknown cleanup state.
            gate.requestCancel()
            token.cancel()
            service.forceStopPackage("com.android.dynsystem")
            gate.finish(id)
            calls += "cancel-finished"
            releaseRead.complete(Unit)
            val actuallySubmitted = withContext(Dispatchers.IO) { submitted.await(3, TimeUnit.SECONDS) }
            println("PROBE late DSU submission: $calls")
            assertFalse("Actual handler must not open the DSU prompt after its task was cancelled", actuallySubmitted)
        } finally {
            releaseRead.complete(Unit)
            PrivilegedProvider.connection.SERVICE = null
        }
    }
}
