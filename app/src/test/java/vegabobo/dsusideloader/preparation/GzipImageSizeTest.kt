package vegabobo.dsusideloader.preparation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GzipImageSizeTest {

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /** gzip of [size] zero bytes, generated in a streaming way. */
    private fun gzipZeros(size: Long): ByteArray {
        val bos = ByteArrayOutputStream()
        object : GZIPOutputStream(bos, 1 shl 16) {
            init { def.setLevel(Deflater.BEST_SPEED) }
        }.use { out ->
            val chunk = ByteArray(1 shl 20)
            var left = size
            while (left > 0) {
                val n = minOf(left, chunk.size.toLong()).toInt()
                out.write(chunk, 0, n)
                left -= n
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun countsSmallFile() {
        val data = ByteArray(123_457) { (it % 251).toByte() }
        assertEquals(123_457L, GzipImageSize.countUncompressedBytes(ByteArrayInputStream(gzip(data))))
    }

    @Test
    fun countsConcatenatedMembers() {
        val a = gzip(ByteArray(1000) { 1 })
        val b = gzip(ByteArray(2345) { 2 })
        assertEquals(3345L, GzipImageSize.countUncompressedBytes(ByteArrayInputStream(a + b)))
        // ISIZE of the file only describes the last member.
        assertEquals(2345L, GzipImageSize.readIsize(a + b))
    }

    @Test
    fun isizeIsModulo2Pow32() {
        val isize = GzipImageSize.readIsize(byteArrayOf(0x10, 0, 0, 0))
        assertEquals(16L, isize)
        assertTrue(GzipImageSize.matchesIsize(16L, isize))
        assertTrue(GzipImageSize.matchesIsize(16L + (1L shl 32), isize))
        assertFalse(GzipImageSize.matchesIsize(17L, isize))
        assertEquals(0xFFFFFFFFL, GzipImageSize.readIsize(byteArrayOf(-1, -1, -1, -1)))
    }

    @Test(expected = IOException::class)
    fun truncatedFileThrows() {
        val gz = gzip(ByteArray(200_000) { (it * 31).toByte() })
        GzipImageSize.countUncompressedBytes(ByteArrayInputStream(gz.copyOf(gz.size / 2)))
    }

    @Test(expected = IOException::class)
    fun missingTrailerThrows() {
        val gz = gzip(ByteArray(10_000) { 3 })
        GzipImageSize.countUncompressedBytes(ByteArrayInputStream(gz.copyOf(gz.size - 4)))
    }

    @Test(expected = IOException::class)
    fun corruptCrcThrows() {
        val gz = gzip(ByteArray(10_000) { 3 })
        gz[gz.size - 6] = (gz[gz.size - 6].toInt() xor 0xFF).toByte()
        GzipImageSize.countUncompressedBytes(ByteArrayInputStream(gz))
    }

    @Test(expected = CancellationException::class)
    fun cancellationStops() {
        val gz = gzip(ByteArray(1_000_000))
        GzipImageSize.countUncompressedBytes(ByteArrayInputStream(gz), isCancelled = { true })
    }

    @Test
    fun countsFilesLargerThan4GiB() {
        // ISIZE wraps around for >= 4 GiB; the counted size must not.
        val size = (1L shl 32) + 4096L
        val gz = gzipZeros(size)
        assertEquals(4096L, GzipImageSize.readIsize(gz))
        var lastProgress = 0L
        val counted = GzipImageSize.countUncompressedBytes(ByteArrayInputStream(gz)) { lastProgress = it }
        assertEquals(size, counted)
        assertEquals(gz.size.toLong(), lastProgress)
    }
}
