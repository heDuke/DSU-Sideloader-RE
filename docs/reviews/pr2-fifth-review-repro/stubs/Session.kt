package vegabobo.dsusideloader.model
class DSUInstallationSource(var uri: String = "content://review/system.gz", var fileSize: Long = 32L)
class Session {
    val preferences = InstallationPreferences()
    val userSelection = Selection()
    var dsuInstallation = DSUInstallationSource()
    var mode = vegabobo.dsusideloader.util.OperationMode.SHIZUKU
    var installationScriptPath = ""
    fun getOperationMode() = mode
    fun getInstallationParameters() = Triple(1024L, "file:///review/system.gz", 32L)
    fun isRoot() = false
    class Selection { var userSelectedUserdata = 1024L }
}

class InstallationPreferences(var isUnmountSdCard: Boolean = false, var useBuiltinInstaller: Boolean = false)
object DSUConstants { const val DEFAULT_IMAGE_SIZE = -1L }
