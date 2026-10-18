package vegabobo.dsusideloader.loader

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Copies the exact bytes that will be prepared; caller owns both streams and the output. */
object ImageIntegrity {
    fun copyVerified(input: InputStream, output: OutputStream, expectedSha256: String, cancelled: () -> Boolean): Long {
        val expected = expectedSha256.trim().lowercase(Locale.ROOT)
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            if (cancelled()) throw CancellationException("Image verification cancelled")
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total = Math.addExact(total, count.toLong())
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        if (cancelled()) throw CancellationException("Image verification cancelled")
        if (total == 0L) throw IOException("Image is empty")
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (actual != expected) throw IOException("SHA-256 mismatch: expected $expected, found $actual")
        return total
    }
}
