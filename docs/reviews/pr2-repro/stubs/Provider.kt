package vegabobo.dsusideloader.service
import kotlinx.coroutines.runBlocking
import vegabobo.dsusideloader.IPrivilegedService
object PrivilegedProvider {
    var service: IPrivilegedService? = null
    fun run(onFail: () -> Unit = {}, onConnected: suspend IPrivilegedService.() -> Unit) {
        runBlocking { service?.let { onConnected(it) } ?: onFail() }
    }
}
