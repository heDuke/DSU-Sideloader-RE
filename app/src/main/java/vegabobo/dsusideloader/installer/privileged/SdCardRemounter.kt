package vegabobo.dsusideloader.installer.privileged

import android.util.Log
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

/**
 * Temporarily unmounts public volumes (SD cards) so gsid does not try to allocate DSU
 * images there, and remounts them afterwards.
 *
 * The volumes to remount are persisted before unmounting, so they are remounted when the
 * installation reaches a terminal state (success, error, cancel, user leaves the result)
 * or, if the process died in between, on the next app start. No fixed delay is used.
 */
class SdCardRemounter(private val dataStore: DataStore<Preferences>) {

    private val tag = this.javaClass.simpleName
    private val mutex = Mutex()

    private suspend fun readPending(): List<String> =
        PendingSdVolumes.decode(
            DataStoreUtils.readStringPref(dataStore, AppPrefs.PENDING_SD_REMOUNT, ""),
        )

    private suspend fun writePending(ids: Collection<String>) =
        DataStoreUtils.updateStringPref(dataStore, AppPrefs.PENDING_SD_REMOUNT, PendingSdVolumes.encode(ids))

    suspend fun hasPending(): Boolean = readPending().isNotEmpty()

    /** @return ids that were unmounted (and are now persisted as pending remount). */
    suspend fun unmountPublicVolumes(service: IPrivilegedService): List<String> = mutex.withLock {
        val ids = PendingSdVolumes.selectPublic(service.volumes.map { it.id })
        if (ids.isEmpty()) return@withLock emptyList()
        val pending = readPending()
        // Persist first: if we die right after unmounting, the next start still remounts.
        writePending(pending + ids)
        val unmounted = ArrayList<String>()
        for (id in ids) {
            try {
                service.unmount(id)
                unmounted += id
                Log.d(tag, "Volume unmounted: $id")
            } catch (e: Exception) {
                Log.e(tag, "Failed to unmount $id", e)
            }
        }
        writePending(pending + unmounted)
        unmounted
    }

    /** Mounts every pending volume that still exists and clears the persisted record. */
    suspend fun remountPending(service: IPrivilegedService, reason: String) = mutex.withLock {
        val pending = readPending()
        if (pending.isEmpty()) return@withLock
        val current = try {
            service.volumes.map { it.id }
        } catch (e: Exception) {
            Log.e(tag, "Unable to list volumes, keeping pending remount record", e)
            return@withLock
        }
        val (toMount, gone) = PendingSdVolumes.reconcile(pending, current)
        gone.forEach { Log.d(tag, "Volume $it no longer present, forgetting it") }
        for (id in toMount) {
            try {
                service.mount(id)
                Log.d(tag, "Volume remounted ($reason): $id")
            } catch (e: Exception) {
                // Likely already mounted or removed; don't retry forever.
                Log.e(tag, "Failed to remount $id ($reason)", e)
            }
        }
        writePending(emptyList())
    }

    /** Fire-and-forget remount; does nothing (and needs no service) when nothing is pending. */
    suspend fun remountPendingIfAny(reason: String) {
        if (!hasPending()) return
        PrivilegedProvider.run(onFail = { Log.e(tag, "Service unavailable, remount postponed") }) {
            remountPending(this, reason)
        }
    }
}
