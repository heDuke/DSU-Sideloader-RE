package vegabobo.dsusideloader.util
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
object DataStoreUtils {
    suspend fun readStringPref(dataStore: DataStore<Preferences>, key: String, default: String): String =
        dataStore.reviewValues[key] ?: default
    suspend fun updateStringPref(dataStore: DataStore<Preferences>, key: String, value: String) {
        dataStore.reviewValues[key] = value
    }
}
