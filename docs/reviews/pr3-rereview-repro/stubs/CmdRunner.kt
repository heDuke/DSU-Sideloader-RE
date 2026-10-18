package vegabobo.dsusideloader.util
// External logcat/libsu transport boundary. Actual LogcatDiagnostic and ViewModel are compiled.
object CmdRunner {
    @Volatile var destroyHook: () -> Unit = {}
    fun run(cmd: String) = ""
    fun runReadEachLine(cmd: String, onReceive: (String) -> Unit) {}
    fun destroy() { destroyHook() }
}
