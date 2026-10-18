package vegabobo.dsusideloader
data class ReviewVolume(val id: String)
interface IPrivilegedService {
    val volumes: List<ReviewVolume>
    fun unmount(id: String)
    fun mount(id: String)
}
