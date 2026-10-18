package vegabobo.dsusideloader.preparation

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Determines the uncompressed size of a gzip image.
 *
 * The gzip ISIZE trailer only stores the uncompressed size modulo 2^32 (and, for
 * multi-member files, only for the last member), so it can't tell a 1 GiB image from a
 * 5 GiB one. The only reliable way without user input is to decompress and count, which
 * also detects truncated or corrupt archives (CRC/ISIZE are verified per member).
 */
object GzipImageSize {

    const val ISIZE_MODULUS = 1L shl 32

    /** Reads the little endian ISIZE trailer (last 4 bytes of a gzip member). */
    fun readIsize(trailer: ByteArray): Long {
        require(trailer.size >= 4) { "ISIZE trailer needs 4 bytes" }
        val o = trailer.size - 4
        return (trailer[o].toLong() and 0xFF) or
            ((trailer[o + 1].toLong() and 0xFF) shl 8) or
            ((trailer[o + 2].toLong() and 0xFF) shl 16) or
            ((trailer[o + 3].toLong() and 0xFF) shl 24)
    }

    /** True if [size] is compatible with an ISIZE value (size mod 2^32 == isize). */
    fun matchesIsize(size: Long, isize: Long): Boolean = size >= 0 && size % ISIZE_MODULUS == isize

    /**
     * Decompresses [input] (all concatenated members) and returns the exact number of
     * uncompressed bytes. Throws IOException for truncated/corrupt data and
     * CancellationException when [isCancelled] becomes true. [onCompressedBytesRead]
     * receives the number of compressed bytes consumed so far (for progress).
     */
    fun countUncompressedBytes(
        input: InputStream,
        isCancelled: () -> Boolean = { false },
        onCompressedBytesRead: (Long) -> Unit = {},
    ): Long {
        val counting = CountingInputStream(input)
        GzipCompressorInputStream(BufferedInputStream(counting), true).use { gz ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            var lastReported = 0L
            while (true) {
                if (isCancelled()) throw CancellationException("gzip size calculation cancelled")
                val n = gz.read(buffer)
                if (n < 0) break
                total += n
                if (counting.count - lastReported >= 8L * 1024 * 1024) {
                    lastReported = counting.count
                    onCompressedBytesRead(lastReported)
                }
            }
            onCompressedBytesRead(counting.count)
            return total
        }
    }

    class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun skip(n: Long): Long {
            val s = super.skip(n)
            if (s > 0) count += s
            return s
        }

        override fun markSupported() = false
    }
}
