package review

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import vegabobo.dsusideloader.IPrivilegedService
import vegabobo.dsusideloader.ReviewVolume
import vegabobo.dsusideloader.installer.privileged.SdCardRemounter
import vegabobo.dsusideloader.installer.root.*

class ReviewRegressionTest {
    private class Backend : DsuBackend {
        var finished = false
        override fun enableDynamicSystemFeature() {}
        override fun isInUse() = false
        override fun isInstalled() = false
        override fun forceStopDsuApp() {}
        override fun startInstallation(slot: String) = true
        override fun createPartition(name: String, size: Long, readOnly: Boolean) = 0
        override fun bytesProcessed() = 0L
        override fun openWriteChannel(partition: String) = object : DsuWriteChannel {
            override val capacity = 64
            override fun register() = true
            override fun submit(data: ByteArray, length: Int) = true
            override fun close() {}
        }
        override fun closePartition() = true
        override fun finishInstallation(): Boolean { finished = true; return true }
        override fun abort() = true
        override fun remove() = true
    }

    private fun sparse(truncatedSecondChunk: Boolean = false): ByteArray {
        val b = ByteBuffer.allocate(28 + 12 + 32 + if (truncatedSecondChunk) 12 else 0)
            .order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0xed26ff3a.toInt()).putShort(1).putShort(0).putShort(28).putShort(12)
        b.putInt(32).putInt(1).putInt(if (truncatedSecondChunk) 2 else 1).putInt(0)
        b.putShort(0xCAC1.toShort()).putShort(0).putInt(1).putInt(44)
        repeat(32) { b.put(7) }
        if (truncatedSecondChunk) {
            // Contradictory chunk metadata followed by an EOF in RAW payload.
            b.putShort(0xCAC1.toShort()).putShort(0).putInt(1).putInt(44)
        }
        return b.array()
    }

    @Test fun defaultDeflatedZipWithValidSparseImageRemainsInstallable() {
        val data = sparse()
        assertEquals(32L, SparseInputStream(data.inputStream().buffered()).unsparseSize)
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("system.img"))
                zip.write(data)
                zip.closeEntry()
            }
        }.toByteArray()
        ZipInputStream(bytes.inputStream()).use { assertEquals(-1L, it.nextEntry.size) }
        val backend = Backend()
        val result = DsuInstallPipeline(backend, 1024, DsuImageSource.ZipPackage { bytes.inputStream() }, { false }).run()
        println("REPRO default deflated sparse ZIP: $result")
        assertEquals("Sparse header provides size; valid input should remain installable", DsuInstallResult.Success, result)
    }

    @Test fun failedRemountRetainsPendingRecordForRetry() = runBlocking {
        val store = object : DataStore<Preferences> {
            override val reviewValues = mutableMapOf("pending_sd_remount" to "public:179,1")
        }
        var mountCalls = 0
        val service = object : IPrivilegedService {
            override val volumes = listOf(ReviewVolume("public:179,1"))
            override fun unmount(id: String) {}
            override fun mount(id: String) { mountCalls++; throw IllegalStateException("temporary binder failure") }
        }
        val remounter = SdCardRemounter(store)
        remounter.remountPending(service, "review failure injection")
        println("REPRO failed remount: calls=$mountCalls persisted=${store.reviewValues}")
        assertTrue("Failed volume must stay pending", remounter.hasPending())
    }

    @Test fun inconsistentSparseChunkTruncatedAtDeclaredLengthMustFail() {
        val data = sparse(truncatedSecondChunk = true)
        val backend = Backend()
        val source = DsuImageSource.SingleImage(DsuImageSource.Image("system", data.size.toLong()) { ByteArrayInputStream(data) })
        val result = DsuInstallPipeline(backend, 1024, source, { false }).run()
        println("REPRO inconsistent sparse with truncated RAW chunk: result=$result finished=${backend.finished}")
        assertTrue("Malformed/truncated sparse must fail even if emitted length matches header", result is DsuInstallResult.Failed)
        assertFalse(backend.finished)
    }
}
