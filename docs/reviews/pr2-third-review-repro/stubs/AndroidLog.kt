package android.util
object Log {
    fun w(tag: String, message: String): Int = 0
    fun d(tag: String, message: String): Int = 0
    fun e(tag: String, message: String, error: Throwable? = null): Int = 0
}
