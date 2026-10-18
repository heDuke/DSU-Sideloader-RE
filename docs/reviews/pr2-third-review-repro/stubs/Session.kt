package vegabobo.dsusideloader.model
class DSUInstallationSource(var uri: String = "content://review/system.gz", var fileSize: Long = 32L)
class Session {
    val preferences = Preferences()
    val userSelection = Selection()
    var dsuInstallation = DSUInstallationSource()
    fun isRoot() = false
    class Preferences { var isUnmountSdCard = false }
    class Selection { var userSelectedUserdata = 1024L }
}
