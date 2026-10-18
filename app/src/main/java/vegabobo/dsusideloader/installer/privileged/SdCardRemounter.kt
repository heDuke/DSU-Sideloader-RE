package vegabobo.dsusideloader.installer.privileged

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import vegabobo.dsusideloader.IPrivilegedService
import vegabobo.dsusideloader.preferences.AppPrefs
import vegabobo.dsusideloader.service.PrivilegedProvider
import vegabobo.dsusideloader.util.DataStoreUtils

/** Pure helpers for the persisted list of temporarily unmounted volumes. */
object PendingSdVolumes {
    private const val SEPARATOR = "\n"
    private const val V2_HEADER = "#v2"

    fun encode(ids: Collection<String>): String =
        ids.filter { it.isNotBlank() }.distinct().joinToString(SEPARATOR)

    fun decode(value: String): List<String> =
        value.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun encodeRecord(record: PendingRemount?): String {
        if (record == null || record.volumes.isEmpty()) return ""
        val sb = StringBuilder(V2_HEADER).append(SEPARATOR)
        sb.append("phase=").append(record.phase.name).append(SEPARATOR)
        record.bootId?.let { sb.append("boot=").append(it).append(SEPARATOR) }
        record.volumes.distinct().forEach { sb.append("vol=").append(it).append(SEPARATOR) }
        record.failures.filterKeys { it in record.volumes }.forEach { (id, reason) ->
            sb.append("fail=").append(id).append('\t').append(reason.replace(SEPARATOR, " ")).append(SEPARATOR)
        }
        return sb.toString()
    }

    /**
     * Decodes a record. The legacy format (one id per line, written by the first P0 round)
     * carries no phase, so it is treated conservatively as [RemountPhase.SYSTEM_UNKNOWN].
     */
    fun decodeRecord(value: String): PendingRemount? {
        val lines = value.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        if (lines.first() != V2_HEADER) {
            val ids = decode(value)
            return if (ids.isEmpty()) null else PendingRemount(ids, RemountPhase.SYSTEM_UNKNOWN, null)
        }
        var phase = RemountPhase.SYSTEM_UNKNOWN
        var boot: String? = null
        val vols = ArrayList<String>()
        val failures = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val key = line.substringBefore('=')
            val v = line.substringAfter('=', "")
            when (key) {
                "phase" -> phase = RemountPhase.values().firstOrNull { it.name == v } ?: RemountPhase.SYSTEM_UNKNOWN
                "boot" -> boot = v.ifEmpty { null }
                "vol" -> if (v.isNotEmpty() && v !in vols) vols += v
                "fail" -> failures[v.substringBefore('\t')] = v.substringAfter('\t', "")
            }
        }
        return if (vols.isEmpty()) null else PendingRemount(vols, phase, boot, failures.filterKeys { it in vols })
    }

    /** Public (removable) volumes, e.g. "public:179,1". */
    fun selectPublic(ids: Collection<String?>): List<String> =
        ids.filterNotNull().filter { it.contains("public") }.distinct()

    /**
     * Splits pending volumes into those still present (to mount) and those that are gone
     * (card removed / id changed) and should just be forgotten.
     */
    fun reconcile(pending: List<String>, current: Collection<String?>): Pair<List<String>, List<String>> {
        val present = current.filterNotNull().toSet()
        return pending.partition { it in present }
    }
}

/** Volume operations of the privileged service (abstracted for tests). */
interface VolumeOps {
    fun volumeIds(): List<String>
    fun mount(id: String)
    fun unmount(id: String)

    /** true/false when the volume state is known, null when it can't be read. */
    fun isMounted(id: String): Boolean? = null
}

fun IPrivilegedService.asVolumeOps(): VolumeOps = object : VolumeOps {
    override fun volumeIds(): List<String> = volumes.mapNotNull { it.id }
    override fun mount(id: String) = this@asVolumeOps.mount(id)
    override fun unmount(id: String) = this@asVolumeOps.unmount(id)
    override fun isMounted(id: String): Boolean? = volumes.firstOrNull { it.id == id }?.let {
        // VolumeInfo.STATE_MOUNTED (2) / STATE_MOUNTED_READ_ONLY (3)
        it.state == 2 || it.state == 3
    }
}

/**
 * Who owns the outcome of the installation the volumes were unmounted for.
 */
enum class RemountPhase {
    /** Unmounted by our live task, nothing handed to the system yet. */
    LOCAL_TASK,

    /**
     * Handed to the system DSU app (or cleanup result unknown): the system may still be
     * waiting for confirmation or installing; we must not remount on our own.
     */
    SYSTEM_UNKNOWN,
}

data class PendingRemount(
    val volumes: List<String>,
    val phase: RemountPhase,
    /** Settings.Global.BOOT_COUNT when the volumes were unmounted (null if unknown). */
    val bootId: String?,
    /** Last remount failure per volume (kept until the volume is remounted or gone). */
    val failures: Map<String, String> = emptyMap(),
)

/** gsid state as far as it can be queried (needs MANAGE_DYNAMIC_SYSTEM, i.e. root). */
enum class SystemDsuState { INSTALLING, IDLE, UNKNOWN }

sealed class AppStartDecision {
    object Nothing : AppStartDecision()
    data class Remount(val reason: String) : AppStartDecision()

    /** Ask the user to finish/cancel in the system DSU UI, then restore storage explicitly. */
    data class Prompt(val systemState: SystemDsuState) : AppStartDecision()
}

object RemountPolicy {
    /**
     * Decision when the app starts (no task of this process is running). Precedence:
     *  1. gsid reports an installation in progress -> never auto-remount (prompt; restore is
     *     refused while it is still installing), even if the device rebooted: a new
     *     installation may have been started after the reboot.
     *  2. gsid state unknown (no root, query failed) -> keep the user prompt.
     *  3. gsid idle: auto-remount only if the device rebooted since the unmount (DSU
     *     installation sessions don't survive a reboot) or our own task never handed
     *     anything to the system. Otherwise (SYSTEM_UNKNOWN, same boot) the system DSU app
     *     may still be waiting for confirmation -> prompt.
     */
    fun onAppStart(record: PendingRemount?, currentBootId: String?, state: SystemDsuState): AppStartDecision {
        if (record == null || record.volumes.isEmpty()) return AppStartDecision.Nothing
        when (state) {
            SystemDsuState.INSTALLING, SystemDsuState.UNKNOWN -> return AppStartDecision.Prompt(state)
            SystemDsuState.IDLE -> Unit
        }
        if (record.bootId != null && currentBootId != null && record.bootId != currentBootId) {
            return AppStartDecision.Remount("device rebooted since unmount and gsid is idle")
        }
        if (record.phase == RemountPhase.LOCAL_TASK) {
            return AppStartDecision.Remount("previous task never reached the system and gsid is idle")
        }
        return AppStartDecision.Prompt(state)
    }
}

/** Persistence of the pending remount record (abstracted for tests). */
interface PendingRemountStore {
    suspend fun read(): PendingRemount?
    suspend fun write(record: PendingRemount?)
}

class DataStorePendingRemountStore(private val dataStore: DataStore<Preferences>) : PendingRemountStore {
    override suspend fun read(): PendingRemount? =
        PendingSdVolumes.decodeRecord(DataStoreUtils.readStringPref(dataStore, AppPrefs.PENDING_SD_REMOUNT, ""))

    override suspend fun write(record: PendingRemount?) =
        DataStoreUtils.updateStringPref(dataStore, AppPrefs.PENDING_SD_REMOUNT, PendingSdVolumes.encodeRecord(record))
}

/**
 * Temporarily unmounts public volumes (SD cards) so gsid does not try to allocate DSU
 * images there, and remounts them afterwards.
 *
 * The record is persisted before unmounting and tied to the task phase ([RemountPhase]).
 * A volume is removed from the record only after it was remounted (or no longer exists),
 * so failed remounts are retried on the next trigger.
 */
class SdCardRemounter(
    private val store: PendingRemountStore,
    private val log: (String) -> Unit = {},
    private val verifyAttempts: Int = 6,
    private val verifyDelayMs: Long = 500,
) {

    private val mutex = Mutex()

    suspend fun hasPending(): Boolean = store.read()?.volumes?.isNotEmpty() == true

    suspend fun pending(): PendingRemount? = store.read()

    /** @return ids that were unmounted (and are now persisted as pending remount). */
    suspend fun unmountPublicVolumes(ops: VolumeOps, bootId: String? = null): List<String> = mutex.withLock {
        val ids = PendingSdVolumes.selectPublic(ops.volumeIds())
        if (ids.isEmpty()) return@withLock emptyList()
        val previous = store.read()
        val before = previous?.volumes.orEmpty()
        fun record(vols: List<String>) = PendingRemount(
            (before + vols).distinct(),
            // Older unconfirmed volumes stay unconfirmed.
            previous?.phase ?: RemountPhase.LOCAL_TASK,
            bootId ?: previous?.bootId,
        )
        // Persist first: if we die right after unmounting, the record still exists.
        store.write(record(ids))
        val unmounted = ArrayList<String>()
        for (id in ids) {
            try {
                ops.unmount(id)
                unmounted += id
                log("Volume unmounted: $id")
            } catch (e: Exception) {
                log("Failed to unmount $id: $e")
            }
        }
        val final = record(unmounted)
        store.write(if (final.volumes.isEmpty()) null else final)
        unmounted
    }

    /** The task handed the installation to the system: the outcome is no longer ours. */
    suspend fun markHandedToSystem() = mutex.withLock {
        val r = store.read() ?: return@withLock
        store.write(r.copy(phase = RemountPhase.SYSTEM_UNKNOWN))
    }

    /**
     * Mounts every pending volume that still exists. Call only on a confirmed terminal
     * outcome, an [AppStartDecision.Remount] or an explicit user "restore storage" action.
     * @return volumes still pending afterwards (remount failed; kept for retry).
     */
    suspend fun remountPending(ops: VolumeOps, reason: String): List<String> = mutex.withLock {
        val record = store.read() ?: return@withLock emptyList()
        val pending = record.volumes
        if (pending.isEmpty()) return@withLock emptyList()
        val current = try {
            ops.volumeIds()
        } catch (e: Exception) {
            log("Unable to list volumes, keeping pending remount record: $e")
            return@withLock pending
        }
        val (toMount, gone) = PendingSdVolumes.reconcile(pending, current)
        gone.forEach { log("Volume $it no longer present, forgetting it") }
        val failures = LinkedHashMap<String, String>()
        for (id in toMount) {
            val error = mountAndVerify(ops, id)
            if (error == null) {
                log("Volume remounted ($reason): $id")
            } else {
                log("Failed to remount $id ($reason), keeping it for retry: $error")
                failures[id] = error
            }
        }
        val failed = failures.keys.toList()
        store.write(if (failed.isEmpty()) null else record.copy(volumes = failed, failures = failures))
        failed
    }

    /** @return null when the volume is (confirmed or assumed) mounted, else the failure reason. */
    private suspend fun mountAndVerify(ops: VolumeOps, id: String): String? {
        if (runCatching { ops.isMounted(id) }.getOrNull() == true) return null
        try {
            ops.mount(id)
        } catch (e: Exception) {
            return e.toString()
        }
        repeat(verifyAttempts) { attempt ->
            when (runCatching { ops.isMounted(id) }.getOrNull()) {
                true -> return null
                // State not readable: the mount call succeeded, accept it but note it.
                null -> {
                    log("Mounted state of $id can't be verified, trusting mount() result")
                    return null
                }
                false -> if (attempt < verifyAttempts - 1) kotlinx.coroutines.delay(verifyDelayMs)
            }
        }
        return "mount() returned but volume is still not mounted"
    }

    /** Drops pending volumes that are confirmed mounted or confirmed gone, without mounting. */
    suspend fun pruneResolved(ops: VolumeOps) = mutex.withLock {
        val record = store.read() ?: return@withLock
        val present = runCatching { ops.volumeIds() }.getOrNull() ?: return@withLock
        val keep = record.volumes.filter { id ->
            id in present && runCatching { ops.isMounted(id) }.getOrNull() != true
        }
        if (keep.size == record.volumes.size) return@withLock
        log("Already mounted or gone, clearing record: ${record.volumes - keep.toSet()}")
        store.write(if (keep.isEmpty()) null else record.copy(volumes = keep, failures = record.failures.filterKeys { it in keep }))
    }

    /** Last failure reasons of the pending volumes (for display). */
    suspend fun failureReasons(): Map<String, String> = store.read()?.failures.orEmpty()

    /**
     * App start: decides with [RemountPolicy] and remounts only when that is safe.
     * @return the decision (a [AppStartDecision.Prompt] must be shown to the user).
     */
    suspend fun onAppStart(ops: VolumeOps?, currentBootId: String?, state: SystemDsuState): AppStartDecision {
        // Volumes confirmed mounted (or confirmed gone) only need their stale record cleared;
        // no mount() is issued for them.
        if (ops != null) pruneResolved(ops)
        val decision = RemountPolicy.onAppStart(store.read(), currentBootId, state)
        log("App start remount decision: $decision")
        if (decision is AppStartDecision.Remount) {
            if (ops == null) return AppStartDecision.Prompt(state)
            val failed = remountPending(ops, decision.reason)
            if (failed.isNotEmpty()) return AppStartDecision.Prompt(state)
        }
        return decision
    }

    /** Fire-and-forget remount for confirmed terminal outcomes; no-op when nothing is pending. */
    suspend fun remountPendingIfAny(reason: String) {
        if (!hasPending()) return
        PrivilegedProvider.run(onFail = { log("Service unavailable, remount postponed") }) {
            remountPending(asVolumeOps(), reason)
        }
    }
}
