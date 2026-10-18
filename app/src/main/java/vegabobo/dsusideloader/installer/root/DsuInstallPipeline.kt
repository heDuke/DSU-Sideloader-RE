package vegabobo.dsusideloader.installer.root

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import vegabobo.dsusideloader.preparation.InstallationStep

/**
 * Operations the pipeline needs from IDynamicSystemService (plus app specific helpers).
 * The Android implementation lives in [DSUInstaller]; tests provide fakes.
 */
interface DsuBackend {
    fun enableDynamicSystemFeature()
    fun isInUse(): Boolean
    fun isInstalled(): Boolean
    fun forceStopDsuApp()
    fun startInstallation(slot: String): Boolean
    fun createPartition(name: String, size: Long, readOnly: Boolean): Int
    fun bytesProcessed(): Long
    fun openWriteChannel(partition: String): DsuWriteChannel
    fun closePartition(): Boolean
    fun finishInstallation(): Boolean
    fun abort(): Boolean
    fun remove(): Boolean
}

/** Shared memory channel used to submit image data to gsid. */
interface DsuWriteChannel : AutoCloseable {
    val capacity: Int

    /** Registers the shared memory with gsid (IDynamicSystemService.setAshmem). */
    fun register(): Boolean

    /** Copies [length] bytes into shared memory and calls submitFromAshmem. */
    fun submit(data: ByteArray, length: Int): Boolean
}

/** What the root installer has to write, besides userdata. */
sealed class DsuImageSource {
    class Image(
        val partition: String,
        /** Uncompressed size if known, a value <= 0 otherwise (sparse header may still provide it). */
        val sizeHint: Long,
        val open: () -> InputStream,
    )

    class SingleImage(val image: Image) : DsuImageSource()
    class MultipleImages(val images: List<Image>) : DsuImageSource()
    class ZipPackage(val open: () -> InputStream) : DsuImageSource()
}

/** Terminal result of a root installation. Exactly one is produced per run. */
sealed class DsuInstallResult {
    object Success : DsuInstallResult() {
        override fun toString() = "Success"
    }

    data class Failed(
        val step: InstallationStep,
        val info: String,
        val cleanup: CleanupResult?,
    ) : DsuInstallResult()

    data class Cancelled(val cleanup: CleanupResult?) : DsuInstallResult()
}

/** Result of best-effort cleanup after a started installation failed or was cancelled. */
data class CleanupResult(val aborted: Boolean, val removed: Boolean, val error: String? = null)

/**
 * Root/builtin DSU installation flow, modelled after AOSP InstallationAsyncTask.
 *
 * Every IDynamicSystemService call result is checked; the first failure stops the flow,
 * runs cleanup (abort + remove) once, and is returned as [DsuInstallResult.Failed].
 * finishInstallation is only called when every previous step succeeded, and
 * [DsuInstallResult.Success] is only returned when finishInstallation returned true.
 */
class DsuInstallPipeline(
    private val backend: DsuBackend,
    private val userdataSize: Long,
    private val source: DsuImageSource,
    private val isCancelled: () -> Boolean,
    private val listener: Listener = Listener.NONE,
    private val log: (String) -> Unit = {},
    private val progressPollIntervalMs: Long = 100,
) {

    interface Listener {
        fun onCreatePartition(partition: String) {}
        fun onStep(step: InstallationStep) {}
        fun onProgress(progress: Float, partition: String) {}

        companion object {
            val NONE = object : Listener {}
        }
    }

    companion object {
        const val DEFAULT_SLOT = "dsu"
        const val INSTALL_OK = 0 // IGsiService.INSTALL_OK
        const val MIN_PROGRESS_TO_PUBLISH = (1 shl 27).toLong()
    }

    private class StepFailure(val step: InstallationStep, val info: String) : Exception(info)
    private class CancelledSignal : Exception("cancelled")

    private var started = false

    fun run(): DsuInstallResult {
        try {
            backend.enableDynamicSystemFeature()
            if (backend.isInUse()) {
                return DsuInstallResult.Failed(InstallationStep.ERROR_ALREADY_RUNNING_DYN_OS, "", null)
            }
            if (backend.isInstalled()) {
                return DsuInstallResult.Failed(InstallationStep.ERROR_REQUIRES_DISCARD_DSU, "", null)
            }
            if (userdataSize <= 0) {
                return DsuInstallResult.Failed(
                    InstallationStep.ERROR,
                    "Invalid userdata size: $userdataSize",
                    null,
                )
            }
            checkCancelled()
            backend.forceStopDsuApp()
            if (!backend.startInstallation(DEFAULT_SLOT)) {
                fail(InstallationStep.ERROR, "startInstallation($DEFAULT_SLOT) failed")
            }
            started = true
            installWritablePartition("userdata", userdataSize)
            when (source) {
                is DsuImageSource.SingleImage -> installImages(listOf(source.image))
                is DsuImageSource.MultipleImages -> installImages(source.images)
                is DsuImageSource.ZipPackage -> installZip(source.open)
            }
            checkCancelled()
            if (!backend.finishInstallation()) {
                fail(InstallationStep.ERROR, "finishInstallation failed")
            }
            log("Installation finished successfully.")
            return DsuInstallResult.Success
        } catch (e: CancelledSignal) {
            log("Installation cancelled")
            return DsuInstallResult.Cancelled(cleanupIfStarted())
        } catch (e: StepFailure) {
            log("Installation failed at ${e.step}: ${e.info}")
            return DsuInstallResult.Failed(e.step, e.info, cleanupIfStarted())
        } catch (e: Exception) {
            log("Installation failed with exception: $e")
            return DsuInstallResult.Failed(
                InstallationStep.ERROR,
                e.message ?: e.javaClass.simpleName,
                cleanupIfStarted(),
            )
        }
    }

    private fun fail(step: InstallationStep, info: String): Nothing = throw StepFailure(step, info)

    private fun checkCancelled() {
        if (isCancelled()) throw CancelledSignal()
    }

    private fun cleanupIfStarted(): CleanupResult? {
        if (!started) return null
        var aborted = false
        var removed = false
        var error: String? = null
        try {
            aborted = backend.abort()
        } catch (e: Exception) {
            error = "abort: $e"
        }
        try {
            removed = backend.remove()
        } catch (e: Exception) {
            error = (error?.let { "$it; " } ?: "") + "remove: $e"
        }
        val result = CleanupResult(aborted, removed, error)
        log("Cleanup: $result")
        return result
    }

    private fun publishProgress(done: Long, total: Long, partition: String) {
        val progress = if (total > 0 && done > 0) done.toFloat() / total.toFloat() else 0F
        listener.onProgress(progress.coerceIn(0F, 1F), partition)
    }

    /**
     * createPartition blocks while gsid allocates (and zero fills writable partitions),
     * so run it on a worker thread, publish progress and abort on cancellation like AOSP.
     */
    private fun createPartition(partition: String, size: Long, readOnly: Boolean) {
        var result: Int? = null
        var error: Throwable? = null
        val worker = Thread({
            try {
                result = backend.createPartition(partition, size, readOnly)
            } catch (t: Throwable) {
                error = t
            }
        }, "dsu-create-$partition")
        worker.start()
        publishProgress(0L, size, partition)
        var abortRequested = false
        var prevPublished = 0L
        while (worker.isAlive) {
            if (!abortRequested && isCancelled()) {
                abortRequested = true
                log("Cancel requested while creating $partition, aborting")
                runCatching { backend.abort() }
            }
            if (!readOnly) {
                val processed = runCatching { backend.bytesProcessed() }.getOrDefault(0L)
                if (processed > prevPublished + MIN_PROGRESS_TO_PUBLISH) {
                    prevPublished = processed
                    publishProgress(processed, size, partition)
                }
            }
            worker.join(progressPollIntervalMs)
        }
        if (abortRequested || isCancelled()) throw CancelledSignal()
        error?.let { fail(InstallationStep.ERROR_CREATE_PARTITION, "$partition: $it") }
        val code = result
        if (code != INSTALL_OK) {
            fail(InstallationStep.ERROR_CREATE_PARTITION, "$partition (error code: $code)")
        }
    }

    private fun closePartition(partition: String) {
        if (!backend.closePartition()) {
            fail(InstallationStep.ERROR_CREATE_PARTITION, "$partition: closePartition failed")
        }
    }

    private fun installWritablePartition(partition: String, size: Long) {
        checkCancelled()
        createPartition(partition, size, readOnly = false)
        closePartition(partition)
        publishProgress(size, size, partition)
        log("Partition $partition installed, readOnly: false, partitionSize: $size")
    }

    private fun installImages(images: List<DsuImageSource.Image>) {
        val tracker = DsuPartitionPolicy.Tracker()
        var installed = 0
        for (image in images) {
            checkCancelled()
            when (val decision = tracker.decide("${image.partition}.img")) {
                is DsuPartitionPolicy.Decision.Install -> {
                    image.open().use { installImage(decision.partition, image.sizeHint, it) }
                    installed++
                }
                is DsuPartitionPolicy.Decision.Skip -> log("Skipping ${image.partition}: ${decision.reason}")
                is DsuPartitionPolicy.Decision.Reject -> fail(InstallationStep.ERROR, decision.reason)
            }
        }
        if (installed == 0) fail(InstallationStep.ERROR, "No installable image found")
    }

    private fun installZip(open: () -> InputStream) {
        open().use { raw ->
            val zis = ZipInputStream(raw)
            val tracker = DsuPartitionPolicy.Tracker()
            var installed = 0
            while (true) {
                checkCancelled()
                val entry = zis.nextEntry ?: break
                when (val decision = tracker.decide(entry.name, entry.isDirectory)) {
                    is DsuPartitionPolicy.Decision.Install -> {
                        // entry.size is -1 for streamed (data descriptor) entries. Pass it
                        // through: installImage reads the sparse header first and only rejects
                        // raw images whose size is still unknown.
                        log("Installing: ${entry.name} (zip size: ${entry.size})")
                        installImage(decision.partition, entry.size, NonClosingInputStream(zis))
                        installed++
                    }
                    is DsuPartitionPolicy.Decision.Skip -> log("Skipping ${entry.name}: ${decision.reason}")
                    is DsuPartitionPolicy.Decision.Reject -> fail(InstallationStep.ERROR, decision.reason)
                }
            }
            if (installed == 0) fail(InstallationStep.ERROR, "No installable image found in package")
        }
    }

    private fun installImage(partition: String, sizeHint: Long, input: InputStream) {
        checkCancelled()
        val sis = SparseInputStream(BufferedInputStream(input))
        val partitionSize = if (sis.unsparseSize != -1L) sis.unsparseSize else sizeHint
        if (partitionSize <= 0) {
            fail(InstallationStep.ERROR, "Unknown or invalid size for $partition: $partitionSize")
        }
        listener.onCreatePartition(partition)
        createPartition(partition, partitionSize, readOnly = true)
        listener.onStep(InstallationStep.INSTALLING_ROOTED)

        var installedSize = 0L
        backend.openWriteChannel(partition).use { channel ->
            if (!channel.register()) {
                fail(InstallationStep.ERROR, "setAshmem failed for $partition")
            }
            publishProgress(0L, partitionSize, partition)
            val readBuffer = ByteArray(channel.capacity)
            while (true) {
                val n = sis.read(readBuffer, 0, readBuffer.size)
                if (n < 0) break
                if (n == 0) continue
                checkCancelled()
                if (installedSize + n > partitionSize) {
                    fail(
                        InstallationStep.ERROR,
                        "$partition image is larger than expected ($partitionSize bytes)",
                    )
                }
                if (!channel.submit(readBuffer, n)) {
                    fail(InstallationStep.ERROR, "submitFromAshmem failed for $partition")
                }
                installedSize += n
                publishProgress(installedSize, partitionSize, partition)
            }
        }
        if (installedSize != partitionSize) {
            fail(
                InstallationStep.ERROR,
                "$partition image is truncated: wrote $installedSize of $partitionSize bytes",
            )
        }
        closePartition(partition)
        log("Partition $partition installed, readOnly: true, partitionSize: $partitionSize")
    }

    /** Lets image readers consume a zip entry without closing the whole zip stream. */
    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }
}
