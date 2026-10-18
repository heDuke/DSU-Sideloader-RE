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
        var failures: Map<String, String> = emptyMap()
        override suspend fun read() = if (ids.isEmpty()) null else PendingRemount(ids, phase, boot, failures)
        override suspend fun write(record: PendingRemount?) {
            ids = record?.volumes.orEmpty()
            failures = record?.failures.orEmpty()
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

        // Known state: mounted once mount() succeeded (unknown state is never success, RR4).
        override fun isMounted(id: String) = id in mounted
    }

    @Test
    fun failedRemountIsKeptForRetry() = kotlinx.coroutines.runBlocking {
        // Review P2-4 repro: a failed mount used to clear the record, so it was never retried.
        val store = MemoryStore(listOf("public:179,1", "public:8,1"))
        val ops = FakeOps(mutableListOf("private", "public:179,1", "public:8,1"), failMount = setOf("public:8,1"))
        val remounter = SdCardRemounter(store, verifyDelayMs = 1)

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
        val remounter = SdCardRemounter(store, verifyDelayMs = 1)
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
        val result = SdCardRemounter(store, verifyDelayMs = 1).unmountPublicVolumes(ops)
        assertEquals(listOf("public:1,1"), result)
        assertEquals(listOf("public:1,1"), store.ids)
    }

    // --- Review R3: unmount record tied to the task phase, no auto remount when unknown ---

    @Test
    fun submitThenBackThenReopenDoesNotAutoMount() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore()
        val ops = FakeOps(mutableListOf("private", "public:179,1"))
        // Task: unmount, hand over to the system DSU app, user leaves (no terminal outcome).
        SdCardRemounter(store, verifyDelayMs = 1).run {
            unmountPublicVolumes(ops, bootId = "7")
            markHandedToSystem()
        }
        assertEquals(RemountPhase.SYSTEM_UNKNOWN, store.phase)

        // App reopened (new remounter instance, same boot), with and without a gsid probe.
        for (state in SystemDsuState.values()) {
            val d = SdCardRemounter(store, verifyDelayMs = 1).onAppStart(ops, "7", state)
            assertEquals(AppStartDecision.Prompt(state), d)
        }
        assertEquals(emptyList<String>(), ops.mounted)
        assertEquals(listOf("public:179,1"), store.ids)
    }

    @Test
    fun appStartRemountsOnlyWhenProvablySafe() = kotlinx.coroutines.runBlocking {
        // Review RR3: every phase x boot x gsid-state combination.
        for (phase in RemountPhase.values()) {
            for (boot in listOf("7", "8", null)) {
                for (state in SystemDsuState.values()) {
                    val record = PendingRemount(listOf("public:1,1"), phase, "7")
                    val d = RemountPolicy.onAppStart(record, boot, state)
                    val rebooted = boot == "8"
                    val expectAuto = state == SystemDsuState.IDLE && (rebooted || phase == RemountPhase.LOCAL_TASK)
                    assertEquals("$phase boot=$boot $state -> $d", expectAuto, d is AppStartDecision.Remount)
                    if (!expectAuto) assertEquals(AppStartDecision.Prompt(state), d)
                }
            }
        }
        // Unknown recorded boot id never counts as a reboot.
        val noBoot = PendingRemount(listOf("public:1,1"), RemountPhase.SYSTEM_UNKNOWN, null)
        assertTrue(RemountPolicy.onAppStart(noBoot, "8", SystemDsuState.IDLE) is AppStartDecision.Prompt)
        assertEquals(AppStartDecision.Nothing, RemountPolicy.onAppStart(null, "8", SystemDsuState.IDLE))
    }

    @Test
    fun rebootPlusInstallingOrUnknownNeverMounts() = kotlinx.coroutines.runBlocking {
        for (state in listOf(SystemDsuState.INSTALLING, SystemDsuState.UNKNOWN)) {
            val store = MemoryStore(listOf("public:1,1"), RemountPhase.SYSTEM_UNKNOWN, "7")
            val ops = FakeOps(mutableListOf("public:1,1"))
            assertEquals(AppStartDecision.Prompt(state), SdCardRemounter(store, verifyDelayMs = 1).onAppStart(ops, "8", state))
            assertEquals(emptyList<String>(), ops.mounted)
            assertEquals(listOf("public:1,1"), store.ids)
        }
        // Reboot + idle -> remounted.
        val store = MemoryStore(listOf("public:1,1"), RemountPhase.SYSTEM_UNKNOWN, "7")
        val ops = StatefulOps(listOf("public:1,1"))
        assertTrue(SdCardRemounter(store, verifyDelayMs = 1).onAppStart(ops, "8", SystemDsuState.IDLE) is AppStartDecision.Remount)
        assertEquals(listOf("public:1,1"), ops.mountCalls)
        assertEquals(emptyList<String>(), store.ids)
    }

    @Test
    fun appStartClearsConfirmedMountedOrGoneVolumesWithoutMounting() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore(listOf("public:1,1", "public:2,1", "public:3,1"), RemountPhase.SYSTEM_UNKNOWN, "7")
        val ops = StatefulOps(listOf("public:1,1", "public:2,1")).apply { state["public:1,1"] = true }
        val d = SdCardRemounter(store, verifyDelayMs = 1).onAppStart(ops, "8", SystemDsuState.INSTALLING)
        assertEquals(AppStartDecision.Prompt(SystemDsuState.INSTALLING), d)
        assertEquals(emptyList<String>(), ops.mountCalls)
        assertEquals(listOf("public:2,1"), store.ids) // 1 mounted, 3 gone -> cleared
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

    // --- Review R4 follow-up: failure reasons + mounted-state verification ---

    /** Ops that report mount state; [ineffective] volumes stay unmounted after mount(). */
    private class StatefulOps(
        val present: List<String>,
        val failMount: Set<String> = emptySet(),
        val ineffective: Set<String> = emptySet(),
    ) : VolumeOps {
        val state = present.associateWith { false }.toMutableMap()
        val mountCalls = mutableListOf<String>()
        override fun volumeIds() = present
        override fun mount(id: String) {
            mountCalls += id
            if (id in failMount) throw IllegalStateException("vold busy")
            if (id !in ineffective) state[id] = true
        }
        override fun unmount(id: String) {
            state[id] = false
        }
        override fun isMounted(id: String) = state[id]
    }

    @Test
    fun partialSuccessKeepsOnlyFailedVolumesWithReasons() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore(listOf("public:1,1", "public:2,1", "public:3,1", "public:4,1"))
        val ops = StatefulOps(
            listOf("public:1,1", "public:2,1", "public:3,1"),
            failMount = setOf("public:2,1"),
            ineffective = setOf("public:3,1"),
        )
        val remounter = SdCardRemounter(store, verifyAttempts = 2, verifyDelayMs = 1)
        val failed = remounter.remountPending(ops, "test")
        assertEquals(listOf("public:2,1", "public:3,1"), failed)
        assertEquals(failed, store.ids) // 1 remounted, 4 gone -> forgotten
        assertTrue(store.failures["public:2,1"]!!.contains("vold busy"))
        assertTrue(store.failures["public:3,1"]!!.contains("still not mounted"))
        assertEquals(store.failures, remounter.failureReasons())

        // Later success clears the record (and the reasons).
        val ops2 = StatefulOps(listOf("public:2,1", "public:3,1"))
        assertEquals(emptyList<String>(), remounter.remountPending(ops2, "retry"))
        assertEquals(emptyList<String>(), store.ids)
        assertEquals(emptyMap<String, String>(), store.failures)
    }

    @Test
    fun alreadyMountedVolumeIsConfirmedWithoutMountCall() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore(listOf("public:1,1"))
        val ops = StatefulOps(listOf("public:1,1")).apply { state["public:1,1"] = true }
        assertEquals(emptyList<String>(), SdCardRemounter(store, verifyDelayMs = 1).remountPending(ops, "test"))
        assertEquals(emptyList<String>(), ops.mountCalls)
        assertEquals(emptyList<String>(), store.ids)
    }

    @Test
    fun failureReasonsRoundTripInRecord() {
        val r = PendingRemount(
            listOf("public:1,1", "public:2,1"),
            RemountPhase.SYSTEM_UNKNOWN,
            "3",
            mapOf("public:2,1" to "vold busy\nline2"),
        )
        val back = PendingSdVolumes.decodeRecord(PendingSdVolumes.encodeRecord(r))!!
        assertEquals(mapOf("public:2,1" to "vold busy line2"), back.failures)
        assertEquals(r.volumes, back.volumes)
    }

    // --- Review RR4: unknown mount state keeps the record ---

    private class UnknownStateOps(val query: (String) -> Boolean?) : VolumeOps {
        val mountCalls = mutableListOf<String>()
        override fun volumeIds() = listOf("public:179,1")
        override fun mount(id: String) {
            mountCalls += id
        }
        override fun unmount(id: String) = Unit
        override fun isMounted(id: String) = query(id)
    }

    @Test
    fun unknownStateAfterMountKeepsRecord() = kotlinx.coroutines.runBlocking {
        val cases = mapOf<String, (String) -> Boolean?>(
            "state not available" to { null },
            "state query failed" to { throw IllegalStateException("status query temporarily unavailable") },
        )
        for ((reason, query) in cases) {
            val store = MemoryStore(listOf("public:179,1"), RemountPhase.SYSTEM_UNKNOWN, "7")
            val ops = UnknownStateOps(query)
            val remaining = SdCardRemounter(store, verifyAttempts = 3, verifyDelayMs = 1).remountPending(ops, "t")
            assertEquals(listOf("public:179,1"), remaining)
            assertEquals(listOf("public:179,1"), store.ids)
            assertTrue(store.failures["public:179,1"]!!.contains("could not be confirmed ($reason"))
            assertEquals(listOf("public:179,1"), ops.mountCalls)
        }
    }

    @Test
    fun verifyTimeoutKeepsRecordAndLaterConfirmationClearsIt() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore(listOf("public:179,1"))
        var mounted = false
        val ops = UnknownStateOps { mounted }
        val remounter = SdCardRemounter(store, verifyAttempts = 6, verifyDelayMs = 5)
        val t0 = System.nanoTime()
        assertEquals(listOf("public:179,1"), remounter.remountPending(ops, "t"))
        assertTrue("polls the whole window", (System.nanoTime() - t0) / 1_000_000 >= 25)
        assertTrue(store.failures["public:179,1"]!!.contains("still not mounted after 30 ms"))

        // Mount completes late: the next attempt confirms it without another mount() call.
        mounted = true
        assertEquals(emptyList<String>(), remounter.remountPending(ops, "retry"))
        assertEquals(listOf("public:179,1"), ops.mountCalls)
        assertEquals(emptyList<String>(), store.ids)
    }

    @Test
    fun stateBecomingMountedWithinWindowIsSuccess() = kotlinx.coroutines.runBlocking {
        val store = MemoryStore(listOf("public:179,1"))
        var queries = 0
        val ops = UnknownStateOps { if (++queries >= 4) true else if (queries == 2) null else false }
        assertEquals(emptyList<String>(), SdCardRemounter(store, verifyAttempts = 6, verifyDelayMs = 1).remountPending(ops, "t"))
        assertEquals(emptyList<String>(), store.ids)
    }

    @Test
    fun defaultVerificationWindowIsThreeSeconds() {
        // Guard the documented window (6 x 500 ms) used by the app.
        val r = SdCardRemounter(MemoryStore())
        val f = SdCardRemounter::class.java.getDeclaredField("verifyAttempts").apply { isAccessible = true }
        val d = SdCardRemounter::class.java.getDeclaredField("verifyDelayMs").apply { isAccessible = true }
        assertEquals(3000L, (f.get(r) as Int) * (d.get(r) as Long))
    }
}
