package android.content
class Intent {
    var flags = 0
    var action: String? = null
    var data: Any? = null
    fun setClassName(packageName: String, className: String): Intent = this
    fun putExtra(name: String, value: Long): Intent = this
    companion object { const val FLAG_ACTIVITY_NEW_TASK = 0x10000000 }
}
