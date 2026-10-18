package vegabobo.dsusideloader.model
class Session {
    val preferences = Preferences()
    val userSelection = Selection()
    val dsuInstallation = Source()
    class Preferences { var isUnmountSdCard = true }
    class Selection { var userSelectedUserdata = 1024L }
    class Source { var uri = "content://review/system.gz"; var fileSize = 32L }
}
