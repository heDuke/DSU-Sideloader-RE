package vegabobo.dsusideloader.ui.screen.settings

import vegabobo.dsusideloader.preferences.AppPrefs

enum class DialogSheetState {
    NONE,
    BUILT_IN_INSTALLER,
    DISABLE_STORAGE_CHECK,
}

data class SettingsUiState(
    val themeMode: String = "system",
    val preferences: HashMap<String, Boolean> = hashMapOf(
        AppPrefs.USE_BUILTIN_INSTALLER to false,
        AppPrefs.KEEP_SCREEN_ON to false,
        AppPrefs.UMOUNT_SD to false,
        AppPrefs.DISABLE_STORAGE_CHECK to false,
        AppPrefs.FULL_LOGCAT_LOGGING to false,
        AppPrefs.FIXED_BRAND_COLORS to false,
    ),
    val dialogSheetDisplay: DialogSheetState = DialogSheetState.NONE,
    val isRoot: Boolean = false,
    val isDevOptEnabled: Boolean = false,
)
