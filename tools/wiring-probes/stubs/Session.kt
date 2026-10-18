package vegabobo.dsusideloader.model
class DSUInstallationSource(var uri: String = "", var fileSize: Long = 32L)
class Session {
    var installPlan: vegabobo.dsusideloader.loader.InstallPlan? = null
    val preferences = InstallationPreferences()
    val userSelection = Selection()
    // Harnesses start with a valid prepared source unless the probe explicitly clears it.
    var dsuInstallation = DSUInstallationSource(uri = "content://review/system.gz")
    var mode = vegabobo.dsusideloader.util.OperationMode.SHIZUKU
    var installationScriptPath = ""
    fun getOperationMode() = mode
    fun getInstallationParameters() = Triple(1024L, "file:///review/system.gz", 32L)
    var rootMode = false
    fun isRoot() = rootMode
    class Selection { var userSelectedUserdata = 1024L }
}

class InstallationPreferences(var isUnmountSdCard: Boolean = false, var useBuiltinInstaller: Boolean = false)
object DSUConstants { const val DEFAULT_IMAGE_SIZE = -1L }
