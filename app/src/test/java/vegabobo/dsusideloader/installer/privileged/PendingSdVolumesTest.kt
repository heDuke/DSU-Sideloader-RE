package vegabobo.dsusideloader.installer.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSdVolumesTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val ids = listOf("public:179,1", "public:8,17")
        assertEquals(ids, PendingSdVolumes.decode(PendingSdVolumes.encode(ids)))
    }

    @Test
    fun decodeIgnoresBlankAndDuplicates() {
        assertEquals(emptyList<String>(), PendingSdVolumes.decode(""))
        assertEquals(listOf("public:1,1"), PendingSdVolumes.decode("public:1,1\n\npublic:1,1\n"))
    }

    @Test
    fun selectsOnlyPublicVolumes() {
        assertEquals(
            listOf("public:179,1"),
            PendingSdVolumes.selectPublic(listOf("private", "emulated;0", "public:179,1", null)),
        )
    }

    @Test
    fun reconcileForgetsVolumesThatAreGone() {
        val (toMount, gone) = PendingSdVolumes.reconcile(
            listOf("public:179,1", "public:8,1"),
            listOf("private", "public:179,1"),
        )
        assertEquals(listOf("public:179,1"), toMount)
        assertEquals(listOf("public:8,1"), gone)
    }
}

class SdCardRemounterTest {

    private class MemoryStore(
        var ids: List<String> = emptyList(),
        var phase: RemountPhase = RemountPhase.LOCAL_TASK,
        var boot: String? = null,
    ) : PendingRemountStore {
        override suspend fun read() = if (ids.isEmpty()) null else PendingRemount(ids, phase, boot)
        override suspend fun write(record: PendingRemount?) {
            ids = record?.volumes.orEmpty()
            record?.let { phase = it.phase; boot = it.bootId }
        }
    }

    private class FakeOps(
        val present: MutableList<String>,
        val failMount: Set<String> = emptySet(),
        val failUnmount: Set<String> = emptySet(),
    ) : VolumeOps {
        val mounted = mutableListOf<String>()
        val unmounted = mutableListOf<String>()
        override fun volumeIds() = present.toList()
        override fun mount(id: String) {
            if (id in failMount) throw IllegalStateException("mount failed")
            mounted += id
        }
        override fun unmount(id: String) {
            if (id in failUnmount) throw IllegalStateException("unmount failed")
            unmounted += id
        }
    }

    @Test
    fun failedRemountIsKeptForRetry() = kotlinx.coroutines.runBlocking {
        // Review P2-4 repro: a failed mount used to clear the record, so it was never retried.
        val store = MemoryStore(listOf("public:179,1", "public:8,1"))
        val ops = FakeOps(mutableListOf("private", "public:179,1", "public:8,1"), failMount = setOf("public:8,1"))
        val remounter = SdCardRemounter(store)

        assertEquals(listOf("public:8,1"), remounter.remountPending(ops, "test"))
        assertEquals(listOf("public:179,1"), ops.mounted)
        assertEquals(listOf("public:8,1"), store.ids)

        // Retry succeeds -> record cleared.
        val ops2 = FakeOps(mutableListOf("public:8,1"))
        assertEquals(emptyList<String>(), remounter.remountPending(ops2, "retry"))
        assertEquals(listOf("public:8,1"), ops2.mounted)
        assertEquals(emptyList<String>(), store.ids)
    }

    @Test
    fun goneVolumesAreForgottenAndListFailureKeepsRecord() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore(listOf("public:1,1"))
        val remounter = SdCardRemounter(store)
        val broken = object : VolumeOps {
            override fun volumeIds(): List<String> = throw IllegalStateException("binder died")
            override fun mount(id: String) = Unit
            override fun unmount(id: String) = Unit
        }
        assertEquals(listOf("public:1,1"), remounter.remountPending(broken, "test"))
        assertEquals(listOf("public:1,1"), store.ids)

        assertEquals(emptyList<String>(), remounter.remountPending(FakeOps(mutableListOf("private")), "test"))
        assertEquals(emptyList<String>(), store.ids)
    }

    @Test
    fun unmountPersistsOnlySuccessfullyUnmountedPublicVolumes() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val ops = FakeOps(mutableListOf("private", "public:1,1", "public:2,1"), failUnmount = setOf("public:2,1"))
        val result = SdCardRemounter(store).unmountPublicVolumes(ops)
        assertEquals(listOf("public:1,1"), result)
        assertEquals(listOf("public:1,1"), store.ids)
    }

    // --- Review R3: unmount record tied to the task phase, no auto remount when unknown ---

    @Test
    fun submitThenBackThenReopenDoesNotAutoMount() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val ops = FakeOps(mutableListOf("private", "public:179,1"))
        // Task: unmount, hand over to the system DSU app, user leaves (no terminal outcome).
        SdCardRemounter(store).run {
            unmountPublicVolumes(ops, bootId = "7")
            markHandedToSystem()
        }
        assertEquals(RemountPhase.SYSTEM_UNKNOWN, store.phase)

        // App reopened (new remounter instance, same boot), with and without a gsid probe.
        for (state in SystemDsuState.values()) {
            val d = SdCardRemounter(store).onAppStart(ops, "7", state)
            assertEquals(AppStartDecision.Prompt(state), d)
        }
        assertEquals(emptyList<String>(), ops.mounted)
        assertEquals(listOf("public:179,1"), store.ids)
    }

    @Test
    fun appStartRemountsOnlyWhenProvablySafe() = kotlinx.coroutines.runBlocking {
        val ops = FakeOps(mutableListOf("public:1,1"))
        // Reboot since unmount: DSU installation sessions do not survive a reboot.
        val rebooted = MemoryStore(listOf("public:1,1"), RemountPhase.SYSTEM_UNKNOWN, "7")
        assertTrue(SdCardRemounter(rebooted).onAppStart(ops, "8", SystemDsuState.UNKNOWN) is AppStartDecision.Remount)
        assertEquals(listOf("public:1,1"), ops.mounted)
        assertEquals(emptyList<String>(), rebooted.ids)

        // Never handed to the system: remount only when gsid confirms idle.
        val local = MemoryStore(listOf("public:1,1"), RemountPhase.LOCAL_TASK, "7")
        val ops2 = FakeOps(mutableListOf("public:1,1"))
        assertEquals(
            AppStartDecision.Prompt(SystemDsuState.UNKNOWN),
            SdCardRemounter(local).onAppStart(ops2, "7", SystemDsuState.UNKNOWN),
        )
        assertEquals(
            AppStartDecision.Prompt(SystemDsuState.INSTALLING),
            SdCardRemounter(local).onAppStart(ops2, "7", SystemDsuState.INSTALLING),
        )
        assertEquals(emptyList<String>(), ops2.mounted)
        assertTrue(SdCardRemounter(local).onAppStart(ops2, "7", SystemDsuState.IDLE) is AppStartDecision.Remount)
        assertEquals(listOf("public:1,1"), ops2.mounted)

        // Unknown boot id never counts as a reboot.
        val noBoot = MemoryStore(listOf("public:1,1"), RemountPhase.SYSTEM_UNKNOWN, null)
        assertTrue(SdCardRemounter(noBoot).onAppStart(ops, "8", SystemDsuState.IDLE) is AppStartDecision.Prompt)
    }

    @Test
    fun legacyRecordIsTreatedAsUnknownAndV2RoundTrips() {
        val legacy = PendingSdVolumes.decodeRecord("public:179,1\npublic:8,1\n")!!
        assertEquals(RemountPhase.SYSTEM_UNKNOWN, legacy.phase)
        assertEquals(listOf("public:179,1", "public:8,1"), legacy.volumes)
        val r = PendingRemount(listOf("public:1,1"), RemountPhase.LOCAL_TASK, "12")
        assertEquals(r, PendingSdVolumes.decodeRecord(PendingSdVolumes.encodeRecord(r)))
        assertEquals("", PendingSdVolumes.encodeRecord(null))
        assertEquals(null, PendingSdVolumes.decodeRecord(""))
    }
}
