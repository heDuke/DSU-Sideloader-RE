package vegabobo.dsusideloader.util
// External logcat/libsu transport boundary. Actual LogcatDiagnostic and ViewModel are compiled.
object CmdRunner {
    @Volatile var destroyHook: () -> Unit = {}
    @Volatile var linesHook: (String, (String) -> Unit) -> Unit = { _, _ -> }
    fun run(cmd: String) = ""
    fun runReadEachLine(cmd: String, onReceive: (String) -> Unit) { linesHook(cmd, onReceive) }
    fun destroy() { destroyHook() }
}
