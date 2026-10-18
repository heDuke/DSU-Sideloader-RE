package vegabobo.dsusideloader
import android.content.Intent
data class ReviewVolume(val id: String, val state: Int = 0)
interface IPrivilegedService {
    val volumes: List<ReviewVolume>
    val isInstalled: Boolean get() = false
    fun abort(): Boolean = true
    fun remove(): Boolean = true
    val uid: Int get() = 0
    fun unmount(id: String)
    fun mount(id: String)
    fun setDynProp() {}
    fun forceStopPackage(name: String) {}
    fun startActivity(intent: Intent) {}
}
