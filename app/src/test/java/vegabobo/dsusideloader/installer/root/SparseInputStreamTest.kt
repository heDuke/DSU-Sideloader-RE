package vegabobo.dsusideloader.installer.root

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SparseInputStreamTest {

    /** A sparse chunk; [totalSize] defaults to the consistent value, [data] is written as-is. */
    data class Chunk(val type: Int, val blocks: Int, val data: ByteArray = ByteArray(0), val totalSize: Int? = null)

    companion object {
        const val RAW = 0xCAC1
        const val FILL = 0xCAC2
        const val DONTCARE = 0xCAC3

        fun sparse(blockSize: Int, totalBlocks: Int, chunks: List<Chunk>, totalChunks: Int = chunks.size): ByteArray {
            val out = ByteArrayOutputStream()
            val hdr = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
            hdr.putInt(0xed26ff3a.toInt()).putShort(1).putShort(0).putShort(28).putShort(12)
            hdr.putInt(blockSize).putInt(totalBlocks).putInt(totalChunks).putInt(0)
            out.write(hdr.array())
            for (c in chunks) {
                val consistent = 12 + when (c.type) {
                    RAW -> c.blocks * blockSize
                    FILL -> 4
                    else -> 0
                }
                val ch = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                ch.putShort(c.type.toShort()).putShort(0).putInt(c.blocks).putInt(c.totalSize ?: consistent)
                out.write(ch.array())
                out.write(c.data)
            }
            return out.toByteArray()
        }

        fun raw(blocks: Int, bs: Int, value: Byte = 1) = Chunk(RAW, blocks, ByteArray(blocks * bs) { value })
    }

    private fun readAll(img: ByteArray): ByteArray {
        val sis = SparseInputStream(BufferedInputStream(ByteArrayInputStream(img)))
        val out = ByteArrayOutputStream()
        val buf = ByteArray(5) // odd size to cross chunk boundaries
        while (true) {
            val n = sis.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun assertRejected(img: ByteArray) {
        try {
            readAll(img)
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun validImageWithRawFillDontCare() {
        val bs = 8
        val fill = Chunk(FILL, 1, byteArrayOf(1, 2, 3, 4))
        val img = sparse(bs, 4, listOf(raw(2, bs, 7), fill, Chunk(DONTCARE, 1)))
        val data = readAll(img)
        assertEquals(32, data.size)
        assertArrayEquals(ByteArray(16) { 7 }, data.copyOfRange(0, 16))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 1, 2, 3, 4), data.copyOfRange(16, 24))
        assertArrayEquals(ByteArray(8), data.copyOfRange(24, 32))
    }

    @Test
    fun zeroLengthChunkDoesNotEndStreamEarly() {
        val bs = 4
        val img = sparse(bs, 2, listOf(raw(1, bs), Chunk(DONTCARE, 0), raw(1, bs, 2)))
        assertEquals(8, readAll(img).size)
    }

    @Test
    fun contradictoryChunkSizeAndTruncationRejected() {
        // Review P2-5 repro: header 2 blocks, chunk claims 4 blocks but total_size and data
        // only cover 2. Used to produce exactly 2 blocks and look complete.
        val bs = 8
        val chunk = Chunk(RAW, 4, ByteArray(2 * bs) { 1 }, totalSize = 12 + 2 * bs)
        assertRejected(sparse(bs, 2, listOf(chunk)))
    }

    @Test
    fun totalSizeMismatchRejected() {
        val bs = 8
        assertRejected(sparse(bs, 2, listOf(raw(2, bs).copy(totalSize = 12 + 3 * bs))))
        assertRejected(sparse(bs, 1, listOf(Chunk(DONTCARE, 1, totalSize = 16))))
        assertRejected(sparse(bs, 1, listOf(Chunk(FILL, 1, byteArrayOf(0, 0, 0, 0), totalSize = 12))))
    }

    @Test
    fun chunksCoveringFewerBlocksThanHeaderRejected() {
        val bs = 8
        assertRejected(sparse(bs, 3, listOf(raw(2, bs))))
    }

    @Test
    fun chunksCoveringMoreBlocksThanHeaderRejected() {
        val bs = 8
        assertRejected(sparse(bs, 1, listOf(raw(2, bs))))
    }

    @Test
    fun truncatedRawDataRejected() {
        val bs = 8
        val img = sparse(bs, 2, listOf(raw(2, bs)))
        assertRejected(img.copyOf(img.size - 3))
    }

    @Test
    fun missingChunksRejected() {
        val bs = 8
        // header declares 2 chunks but only one is present
        assertRejected(sparse(bs, 2, listOf(raw(2, bs)), totalChunks = 2))
    }

    @Test
    fun truncatedFillValueRejected() {
        val bs = 8
        val img = sparse(bs, 1, listOf(Chunk(FILL, 1, byteArrayOf(9, 9, 9, 9))))
        assertRejected(img.copyOf(img.size - 2))
    }

    @Test
    fun illegalBlockSizeRejected() {
        try {
            SparseInputStream(BufferedInputStream(ByteArrayInputStream(sparse(0, 0, emptyList()))))
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("block size"))
        }
    }

    @Test
    fun unsignedHeaderFields() {
        // total_blks >= 2^31 must not become negative.
        val img = sparse(4096, -1, emptyList())
        val sis = SparseInputStream(BufferedInputStream(ByteArrayInputStream(img)))
        assertEquals(4096L * 0xFFFFFFFFL, sis.unsparseSize)
    }

    @Test
    fun nonSparsePassThrough() {
        val data = ByteArray(100) { it.toByte() }
        assertArrayEquals(data, readAll(data))
    }
}
