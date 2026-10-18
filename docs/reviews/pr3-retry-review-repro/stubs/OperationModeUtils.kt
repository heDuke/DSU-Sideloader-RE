package vegabobo.dsusideloader.util
enum class OperationMode { ADB, SHIZUKU }
object OperationModeUtils {
    @Volatile var hook: () -> Boolean = { false }
    fun isReadLogsPermissionGranted(context: Any) = hook()
}
