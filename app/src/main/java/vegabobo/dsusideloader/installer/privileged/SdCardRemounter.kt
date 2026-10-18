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

    fun encode(ids: Collection<String>): String =
        ids.filter { it.isNotBlank() }.distinct().joinToString(SEPARATOR)

    fun decode(value: String): List<String> =
        value.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

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
}

fun IPrivilegedService.asVolumeOps(): VolumeOps = object : VolumeOps {
    override fun volumeIds(): List<String> = volumes.mapNotNull { it.id }
    override fun mount(id: String) = this@asVolumeOps.mount(id)
    override fun unmount(id: String) = this@asVolumeOps.unmount(id)
}

/** Persistence of the pending remount record (abstracted for tests). */
interface PendingRemountStore {
    suspend fun read(): List<String>
    suspend fun write(ids: List<String>)
}

class DataStorePendingRemountStore(private val dataStore: DataStore<Preferences>) : PendingRemountStore {
    override suspend fun read(): List<String> =
        PendingSdVolumes.decode(DataStoreUtils.readStringPref(dataStore, AppPrefs.PENDING_SD_REMOUNT, ""))

    override suspend fun write(ids: List<String>) =
        DataStoreUtils.updateStringPref(dataStore, AppPrefs.PENDING_SD_REMOUNT, PendingSdVolumes.encode(ids))
}

/**
 * Temporarily unmounts public volumes (SD cards) so gsid does not try to allocate DSU
 * images there, and remounts them afterwards.
 *
 * The volumes to remount are persisted before unmounting. A volume is removed from the
 * record only after it was remounted successfully (or no longer exists), so failed
 * remounts are retried on the next trigger.
 */
class SdCardRemounter(
    private val store: PendingRemountStore,
    private val log: (String) -> Unit = {},
) {

    private val mutex = Mutex()

    suspend fun hasPending(): Boolean = store.read().isNotEmpty()

    /** @return ids that were unmounted (and are now persisted as pending remount). */
    suspend fun unmountPublicVolumes(ops: VolumeOps): List<String> = mutex.withLock {
        val ids = PendingSdVolumes.selectPublic(ops.volumeIds())
        if (ids.isEmpty()) return@withLock emptyList()
        val pending = store.read()
        // Persist first: if we die right after unmounting, the record still exists.
        store.write(pending + ids)
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
        store.write(pending + unmounted)
        unmounted
    }

    /**
     * Mounts every pending volume that still exists.
     * @return volumes still pending afterwards (remount failed; kept for retry).
     */
    suspend fun remountPending(ops: VolumeOps, reason: String): List<String> = mutex.withLock {
        val pending = store.read()
        if (pending.isEmpty()) return@withLock emptyList()
        val current = try {
            ops.volumeIds()
        } catch (e: Exception) {
            log("Unable to list volumes, keeping pending remount record: $e")
            return@withLock pending
        }
        val (toMount, gone) = PendingSdVolumes.reconcile(pending, current)
        gone.forEach { log("Volume $it no longer present, forgetting it") }
        val failed = ArrayList<String>()
        for (id in toMount) {
            try {
                ops.mount(id)
                log("Volume remounted ($reason): $id")
            } catch (e: Exception) {
                log("Failed to remount $id ($reason), keeping it for retry: $e")
                failed += id
            }
        }
        store.write(failed)
        failed
    }

    /** Fire-and-forget remount; does nothing (and needs no service) when nothing is pending. */
    suspend fun remountPendingIfAny(reason: String) {
        if (!hasPending()) return
        PrivilegedProvider.run(onFail = { log("Service unavailable, remount postponed") }) {
            remountPending(asVolumeOps(), reason)
        }
    }
}
