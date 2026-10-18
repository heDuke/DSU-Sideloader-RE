package vegabobo.dsusideloader.installer.root

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vegabobo.dsusideloader.preparation.InstallationStep

class DsuInstallPipelineTest {

    /** Records every call; individual steps can be made to fail. */
    private class FakeBackend : DsuBackend {
        val calls = mutableListOf<String>()
        val written = linkedMapOf<String, ByteArrayOutputStream>()
        var inUse = false
        var installed = false
        var startOk = true
        var createResult: (String) -> Int = { DsuInstallPipeline.INSTALL_OK }
        var createBlock: (String) -> Unit = {}
        var closeOk: (String) -> Boolean = { true }
        var registerOk = true
        var submitOk: (Int) -> Boolean = { true }
        var finishOk = true
        var channelCapacity = 16
        var submitCount = 0
        var openChannels = 0
        private var current: String? = null

        override fun enableDynamicSystemFeature() { calls += "setDynProp" }
        override fun isInUse() = inUse
        override fun isInstalled() = installed
        override fun forceStopDsuApp() { calls += "forceStop" }
        override fun startInstallation(slot: String): Boolean {
            calls += "start:$slot"
            return startOk
        }
        override fun createPartition(name: String, size: Long, readOnly: Boolean): Int {
            calls += "create:$name:$size:$readOnly"
            createBlock(name)
            current = name
            written[name] = ByteArrayOutputStream()
            return createResult(name)
        }
        override fun bytesProcessed() = 0L
        override fun openWriteChannel(partition: String): DsuWriteChannel {
            openChannels++
            return object : DsuWriteChannel {
                override val capacity = channelCapacity
                override fun register(): Boolean {
                    calls += "setAshmem:$partition"
                    return registerOk
                }
                override fun submit(data: ByteArray, length: Int): Boolean {
                    submitCount++
                    if (!submitOk(submitCount)) return false
                    written[partition]!!.write(data, 0, length)
                    return true
                }
                override fun close() { openChannels-- }
            }
        }
        override fun closePartition(): Boolean {
            calls += "close:$current"
            return closeOk(current!!)
        }
        override fun finishInstallation(): Boolean {
            calls += "finish"
            return finishOk
        }
        override fun abort(): Boolean {
            calls += "abort"
            return true
        }
        override fun remove(): Boolean {
            calls += "remove"
            return true
        }
    }

    private val payload = ByteArray(100) { it.toByte() }

    private fun single(data: ByteArray = payload, size: Long = data.size.toLong()) =
        DsuImageSource.SingleImage(DsuImageSource.Image("system", size) { ByteArrayInputStream(data) })

    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = true): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().also { it.update(data) }.value
                }
                zos.putNextEntry(e)
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun run(
        backend: FakeBackend,
        source: DsuImageSource = single(),
        userdata: Long = 1024,
        cancelled: () -> Boolean = { false },
    ) = DsuInstallPipeline(backend, userdata, source, cancelled, progressPollIntervalMs = 5).run()

    @Test
    fun successCallsFinishOnlyAfterAllSteps() {
        val b = FakeBackend()
        val result = run(b)
        assertEquals(DsuInstallResult.Success, result)
        assertEquals(
            listOf(
                "setDynProp", "forceStop", "start:dsu",
                "create:userdata:1024:false", "close:userdata",
                "create:system:100:true", "setAshmem:system", "close:system",
                "finish",
            ),
            b.calls,
        )
        assertTrue(payload.contentEquals(b.written["system"]!!.toByteArray()))
        assertEquals(0, b.openChannels)
    }

    @Test
    fun alreadyRunningOrInstalledDoNotStart() {
        val b1 = FakeBackend().apply { inUse = true }
        val r1 = run(b1) as DsuInstallResult.Failed
        assertEquals(InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS, r1.step)
        assertNull(r1.cleanup)
        val b2 = FakeBackend().apply { installed = true }
        val r2 = run(b2) as DsuInstallResult.Failed
        assertEquals(InstallationStep.ERROR_REQUIRES_DISCARD_DSU, r2.step)
        assertFalse(b1.calls.any { it.startsWith("start") } || b2.calls.any { it.startsWith("start") })
    }

    private fun assertFailedWithCleanup(b: FakeBackend, result: DsuInstallResult, step: InstallationStep) {
        assertTrue("expected Failed, got $result", result is DsuInstallResult.Failed)
        result as DsuInstallResult.Failed
        assertEquals(step, result.step)
        assertFalse("finish must not be called: ${b.calls}", b.calls.contains("finish"))
        assertEquals(listOf("abort", "remove"), b.calls.takeLast(2))
        assertEquals(CleanupResult(aborted = true, removed = true), result.cleanup)
        assertEquals(0, b.openChannels)
    }

    @Test
    fun startInstallationFailureStopsWithoutCleanup() {
        val b = FakeBackend().apply { startOk = false }
        val r = run(b) as DsuInstallResult.Failed
        assertEquals(InstallationStep.ERROR, r.step)
        assertNull(r.cleanup)
        assertFalse(b.calls.any { it.startsWith("create") || it == "finish" })
    }

    @Test
    fun userdataCreateFailureStops() {
        val b = FakeBackend().apply { createResult = { if (it == "userdata") 2 else 0 } }
        val r = run(b)
        assertFailedWithCleanup(b, r, InstallationStep.ERROR_CREATE_PARTITION)
        assertFalse(b.calls.any { it.startsWith("create:system") })
    }

    @Test
    fun userdataCloseFailureStops() {
        val b = FakeBackend().apply { closeOk = { it != "userdata" } }
        assertFailedWithCleanup(b, run(b), InstallationStep.ERROR_CREATE_PARTITION)
        assertFalse(b.calls.any { it.startsWith("create:system") })
    }

    @Test
    fun imageCreateFailureStops() {
        val b = FakeBackend().apply { createResult = { if (it == "system") 1 else 0 } }
        assertFailedWithCleanup(b, run(b), InstallationStep.ERROR_CREATE_PARTITION)
        assertFalse(b.calls.contains("setAshmem:system"))
    }

    @Test
    fun createPartitionExceptionStops() {
        val b = FakeBackend().apply { createBlock = { if (it == "system") throw IllegalStateException("binder died") } }
        assertFailedWithCleanup(b, run(b), InstallationStep.ERROR_CREATE_PARTITION)
    }

    @Test
    fun setAshmemFailureStops() {
        val b = FakeBackend().apply { registerOk = false }
        assertFailedWithCleanup(b, run(b), InstallationStep.ERROR)
        assertEquals(0, b.submitCount)
    }

    @Test
    fun submitFailureStops() {
        val b = FakeBackend().apply { submitOk = { it < 3 } }
        assertFailedWithCleanup(b, run(b), InstallationStep.ERROR)
        assertEquals(3, b.submitCount)
        assertFalse(b.calls.contains("close:system"))
    }

    @Test
    fun imageCloseFailureStops() {
        val b = FakeBackend().apply { closeOk = { it != "system" } }
        assertFailedWithCleanup(b, run(b), InstallationStep.ERROR_CREATE_PARTITION)
    }

    @Test
    fun finishFailureIsNotSuccess() {
        val b = FakeBackend().apply { finishOk = false }
        val r = run(b) as DsuInstallResult.Failed
        assertEquals(InstallationStep.ERROR, r.step)
        assertEquals(listOf("finish", "abort", "remove"), b.calls.takeLast(3))
    }

    @Test
    fun truncatedImageFails() {
        val b = FakeBackend()
        assertFailedWithCleanup(b, run(b, single(payload, size = 200)), InstallationStep.ERROR)
        assertFalse(b.calls.contains("close:system"))
    }

    @Test
    fun imageLargerThanDeclaredFails() {
        val b = FakeBackend()
        assertFailedWithCleanup(b, run(b, single(payload, size = 50)), InstallationStep.ERROR)
    }

    @Test
    fun unknownImageSizeFailsBeforeCreatingPartition() {
        val b = FakeBackend()
        assertFailedWithCleanup(b, run(b, single(payload, size = -1)), InstallationStep.ERROR)
        assertFalse(b.calls.any { it.startsWith("create:system") })
    }

    @Test
    fun openStreamFailureIsReported() {
        val b = FakeBackend()
        val src = DsuImageSource.SingleImage(
            DsuImageSource.Image("system", 100) { throw java.io.FileNotFoundException("gone") },
        )
        assertFailedWithCleanup(b, run(b, src), InstallationStep.ERROR)
    }

    @Test
    fun invalidUserdataSizeFailsBeforeStart() {
        val b = FakeBackend()
        val r = run(b, userdata = 0) as DsuInstallResult.Failed
        assertEquals(InstallationStep.ERROR, r.step)
        assertFalse(b.calls.any { it.startsWith("start") })
    }

    @Test
    fun cancellationDuringWriteCleansUpAndNeverFinishes() {
        val b = FakeBackend()
        val cancelled = AtomicBoolean(false)
        b.submitOk = { if (it == 2) cancelled.set(true); true }
        val r = run(b, cancelled = { cancelled.get() })
        assertTrue(r is DsuInstallResult.Cancelled)
        assertEquals(CleanupResult(true, true), (r as DsuInstallResult.Cancelled).cleanup)
        assertFalse(b.calls.contains("finish"))
        assertEquals(0, b.openChannels)
    }

    @Test
    fun cancellationDuringCreateAbortsLikeAosp() {
        val b = FakeBackend()
        val cancelled = AtomicBoolean(false)
        b.createBlock = {
            if (it == "userdata") {
                cancelled.set(true)
                Thread.sleep(50)
            }
        }
        val r = run(b, cancelled = { cancelled.get() })
        assertTrue(r is DsuInstallResult.Cancelled)
        assertFalse(b.calls.any { it.startsWith("create:system") })
        assertFalse(b.calls.contains("finish"))
        // abort while creating + cleanup abort/remove
        assertTrue(b.calls.count { it == "abort" } >= 1)
        assertEquals("remove", b.calls.last())
    }

    @Test
    fun zipInstallsOnlyAllowedPartitionsWithCorrectNames() {
        val b = FakeBackend()
        val data = zip(
            "system.img" to payload,
            "vbmeta.img" to ByteArray(10),
            "product.img" to ByteArray(30) { 7 },
            "META-INF/info.txt" to ByteArray(3),
        )
        val r = run(b, DsuImageSource.ZipPackage { ByteArrayInputStream(data) })
        assertEquals(DsuInstallResult.Success, r)
        assertEquals(listOf("userdata", "system", "product"), b.written.keys.toList())
        assertTrue(payload.contentEquals(b.written["system"]!!.toByteArray()))
        assertEquals(30, b.written["product"]!!.size())
    }

    @Test
    fun zipWithDuplicatePartitionFails() {
        val b = FakeBackend()
        val data = zip("system.img" to payload, "./system.img" to payload)
        assertFailedWithCleanup(b, run(b, DsuImageSource.ZipPackage { ByteArrayInputStream(data) }), InstallationStep.ERROR)
    }

    @Test
    fun zipWithTraversalPathFails() {
        val b = FakeBackend()
        val data = zip("../system.img" to payload)
        assertFailedWithCleanup(b, run(b, DsuImageSource.ZipPackage { ByteArrayInputStream(data) }), InstallationStep.ERROR)
        assertFalse(b.calls.any { it.startsWith("create:system") || it.startsWith("create:..") })
    }

    @Test
    fun zipWithoutInstallableImageFails() {
        val b = FakeBackend()
        val data = zip("vbmeta.img" to payload)
        assertFailedWithCleanup(b, run(b, DsuImageSource.ZipPackage { ByteArrayInputStream(data) }), InstallationStep.ERROR)
    }

    @Test
    fun zipRawEntryWithUnknownSizeFails() {
        // DEFLATED entries written by ZipOutputStream use a data descriptor: size unknown while
        // streaming. A raw image has no other size source, so it must be rejected.
        val b = FakeBackend()
        val data = zip("system.img" to payload, stored = false)
        assertFailedWithCleanup(b, run(b, DsuImageSource.ZipPackage { ByteArrayInputStream(data) }), InstallationStep.ERROR)
        assertFalse(b.calls.any { it.startsWith("create:system") })
    }

    @Test
    fun zipSparseEntryWithUnknownSizeSucceeds() {
        // Regression (review P1-1): a sparse image carries its own size in the header, so a
        // DEFLATED entry with a data descriptor (size -1) is valid, as on master.
        val b = FakeBackend()
        val img = sparseImage(blockSize = 8, rawBlocks = 2, dontCareBlocks = 3)
        val data = zip("system.img" to img, stored = false)
        val r = run(b, DsuImageSource.ZipPackage { ByteArrayInputStream(data) })
        assertEquals(DsuInstallResult.Success, r)
        assertTrue(b.calls.contains("create:system:40:true"))
        assertEquals(40, b.written["system"]!!.size())
    }

    private class TrackingZipInputStream(input: InputStream) : java.util.zip.ZipInputStream(input) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun runZipTracking(b: FakeBackend, data: ByteArray): Pair<DsuInstallResult, TrackingZipInputStream> {
        var zis: TrackingZipInputStream? = null
        val r = DsuInstallPipeline(
            b,
            1024,
            DsuImageSource.ZipPackage { ByteArrayInputStream(data) },
            { false },
            progressPollIntervalMs = 5,
            zipStreamFactory = { TrackingZipInputStream(it).also { s -> zis = s } },
        ).run()
        return r to zis!!
    }

    @Test
    fun zipInputStreamClosedOnSuccess() {
        // Review P2-6: the ZipInputStream (and its Inflater) must be closed, not only the raw stream.
        val (r, zis) = runZipTracking(FakeBackend(), zip("system.img" to payload))
        assertEquals(DsuInstallResult.Success, r)
        assertTrue(zis.closed)
    }

    @Test
    fun zipInputStreamClosedOnFailure() {
        val b = FakeBackend().apply { submitOk = { false } }
        val (r, zis) = runZipTracking(b, zip("system.img" to payload))
        assertTrue(r is DsuInstallResult.Failed)
        assertTrue(zis.closed)
        val (r2, zis2) = runZipTracking(FakeBackend(), zip("../system.img" to payload))
        assertTrue(r2 is DsuInstallResult.Failed)
        assertTrue(zis2.closed)
    }

    @Test
    fun truncatedZipFails() {
        val b = FakeBackend()
        val data = zip("system.img" to ByteArray(1000) { 1 })
        val cut = data.copyOf(500)
        assertFailedWithCleanup(b, run(b, DsuImageSource.ZipPackage { ByteArrayInputStream(cut) }), InstallationStep.ERROR)
    }

    private fun sparseImage(blockSize: Int, rawBlocks: Int, dontCareBlocks: Int): ByteArray {
        val buf = ByteBuffer.allocate(28 + 12 + rawBlocks * blockSize + 12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0xed26ff3a.toInt()).putShort(1).putShort(0).putShort(28).putShort(12)
        buf.putInt(blockSize).putInt(rawBlocks + dontCareBlocks).putInt(2).putInt(0)
        buf.putShort(0xCAC1.toShort()).putShort(0).putInt(rawBlocks).putInt(12 + rawBlocks * blockSize)
        repeat(rawBlocks * blockSize) { buf.put(5) }
        buf.putShort(0xCAC3.toShort()).putShort(0).putInt(dontCareBlocks).putInt(12)
        return buf.array()
    }

    @Test
    fun sparseImageUsesUnsparseSize() {
        val b = FakeBackend()
        val img = sparseImage(blockSize = 8, rawBlocks = 2, dontCareBlocks = 3)
        val src = DsuImageSource.SingleImage(DsuImageSource.Image("system", img.size.toLong()) { ByteArrayInputStream(img) })
        assertEquals(DsuInstallResult.Success, run(b, src))
        assertTrue(b.calls.contains("create:system:40:true"))
        assertEquals(40, b.written["system"]!!.size())
    }

    @Test
    fun truncatedSparseImageFails() {
        val b = FakeBackend()
        val img = sparseImage(blockSize = 8, rawBlocks = 4, dontCareBlocks = 0)
        val cut = img.copyOf(28 + 12 + 10)
        val src = DsuImageSource.SingleImage(DsuImageSource.Image("system", -1) { ByteArrayInputStream(cut) })
        assertFailedWithCleanup(b, run(b, src), InstallationStep.ERROR)
    }

    @Test
    fun corruptSparseWithContradictoryMetadataFails() {
        // Review P2-5 repro: header says 2 blocks, the RAW chunk claims 4 blocks, total_size
        // and data only cover 2 blocks. Bytes written == unsparse size, yet it is corrupt.
        val b = FakeBackend()
        val bs = 8
        val chunk = SparseInputStreamTest.Chunk(
            SparseInputStreamTest.RAW,
            4,
            ByteArray(2 * bs) { 1 },
            totalSize = 12 + 2 * bs,
        )
        val img = SparseInputStreamTest.sparse(bs, 2, listOf(chunk))
        val src = DsuImageSource.SingleImage(DsuImageSource.Image("system", -1) { ByteArrayInputStream(img) })
        assertFailedWithCleanup(b, run(b, src), InstallationStep.ERROR)
        assertFalse(b.calls.contains("close:system"))
    }

    @Test
    fun sparseCoveringFewerBlocksThanHeaderFails() {
        val b = FakeBackend()
        val img = SparseInputStreamTest.sparse(8, 3, listOf(SparseInputStreamTest.raw(2, 8)))
        val src = DsuImageSource.SingleImage(DsuImageSource.Image("system", -1) { ByteArrayInputStream(img) })
        assertFailedWithCleanup(b, run(b, src), InstallationStep.ERROR)
    }

    @Test
    fun multipleImagesRespectPolicy() {
        val b = FakeBackend()
        fun img(name: String) = DsuImageSource.Image(name, payload.size.toLong()) { ByteArrayInputStream(payload) as InputStream }
        val ok = run(b, DsuImageSource.MultipleImages(listOf(img("system"), img("boot"), img("vendor"))))
        assertEquals(DsuInstallResult.Success, ok)
        assertEquals(listOf("userdata", "system", "vendor"), b.written.keys.toList())

        val b2 = FakeBackend()
        val dup = run(b2, DsuImageSource.MultipleImages(listOf(img("system"), img("system"))))
        assertFailedWithCleanup(b2, dup, InstallationStep.ERROR)
    }
}
