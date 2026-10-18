package vegabobo.dsusideloader.installer.privileged

import org.junit.Assert.assertEquals
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

    private class MemoryStore(var ids: List<String> = emptyList()) : PendingRemountStore {
        override suspend fun read() = ids
        override suspend fun write(ids: List<String>) {
            this.ids = ids
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
}
