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

    /**
     * @param catalog optional central-directory listing (cheap for seekable local files).
     * It supplies sizes for streamed entries whose local header has no size (data
     * descriptor). The streamed data is still checked against the actual bytes read and
     * ZipInputStream's CRC/size verification.
     */
    class ZipPackage(
        val catalog: (() -> List<ZipCatalogEntry>?)? = null,
        val open: () -> InputStream,
    ) : DsuImageSource()
}

/** One entry of a ZIP central directory. [size] is the uncompressed size, -1 if unknown. */
data class ZipCatalogEntry(val name: String, val isDirectory: Boolean, val size: Long)

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

/**
 * Result of best-effort cleanup after a started installation failed or was cancelled.
 * [stillInstalled] is isInstalled() queried after remove(); null if it could not be queried.
 */
data class CleanupResult(
    val aborted: Boolean,
    val removed: Boolean,
    val error: String? = null,
    val stillInstalled: Boolean? = null,
) {
    /**
     * Cleanup is complete only if remove() succeeded and the system confirms no DSU image is
     * left. abort() returning false alone is not a failure (nothing may have been in progress).
     */
    val succeeded: Boolean get() = removed && error == null && stillInstalled == false

    fun describe(): String =
        "abort=$aborted, remove=$removed, stillInstalled=${stillInstalled ?: "unknown"}" +
            (error?.let { ", error=$it" } ?: "")
}

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
    private val zipStreamFactory: (InputStream) -> ZipInputStream = { ZipInputStream(it) },
) {

    interface Listener {
        fun onCreatePartition(partition: String) {}
        fun onStep(step: InstallationStep) {}
        fun onProgress(progress: Float, partition: String) {}

        /** An image present in the source that is not installed via DSU (e.g. boot, vbmeta). */
        fun onSkipped(image: String, reason: String) {}

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

    /** Result of checking all image names before anything is allocated. */
    private class Plan(val install: Set<String>)

    /** Images whose names were not known up front get their skip reported while streaming. */
    private var plan: Plan? = null

    private fun names(): List<Pair<String, Boolean>>? = when (source) {
        is DsuImageSource.SingleImage -> listOf("${source.image.partition}.img" to false)
        is DsuImageSource.MultipleImages -> source.images.map { "${it.partition}.img" to false }
        is DsuImageSource.ZipPackage -> null
    }

    /**
     * Applies the partition policy to every image before startInstallation/userdata allocation:
     * unknown partitions or duplicates fail the whole install, exactly one system image is
     * required, and skipped images are reported. Sources without a listing (streamed URL ZIP)
     * can't be checked up front; the same rules are then enforced while streaming, before
     * finishInstallation.
     */
    private fun preflight(entries: List<Pair<String, Boolean>>): Plan {
        val tracker = DsuPartitionPolicy.Tracker()
        val install = LinkedHashSet<String>()
        for ((name, isDir) in entries) {
            when (val d = tracker.decide(name, isDir)) {
                is DsuPartitionPolicy.Decision.Install -> install += d.partition
                is DsuPartitionPolicy.Decision.Skip -> reportSkip(name, d.reason)
                is DsuPartitionPolicy.Decision.Reject -> fail(InstallationStep.ERROR, d.reason)
            }
        }
        requireSystem(install)
        log("Preflight: installing $install")
        return Plan(install)
    }

    private fun reportSkip(name: String, reason: String) {
        log("Skipping $name: $reason")
        // Directories and non-image files are not worth a user-visible summary.
        if (name.endsWith(".img")) listener.onSkipped(name, reason)
    }

    private fun requireSystem(install: Set<String>) {
        if ("system" !in install) {
            fail(InstallationStep.ERROR, "Exactly one system image is required, none found (images: $install)")
        }
    }

    /** After streaming: the installed set must match the preflight plan and contain system. */
    private fun verifyInstalledSet(installed: Set<String>) {
        requireSystem(installed)
        val expected = plan?.install ?: return
        if (installed != expected) {
            fail(InstallationStep.ERROR, "Package content differs from its directory: installed $installed, expected $expected")
        }
    }

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
            val catalog = (source as? DsuImageSource.ZipPackage)?.let { loadCatalog(it) }
            val listed = names() ?: catalog?.map { it.name to it.isDirectory }
            plan = listed?.let { preflight(it) }
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
                is DsuImageSource.ZipPackage -> installZip(source.open, catalog)
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
        val stillInstalled = try {
            backend.isInstalled()
        } catch (e: Exception) {
            error = (error?.let { "$it; " } ?: "") + "isInstalled: $e"
            null
        }
        val result = CleanupResult(aborted, removed, error, stillInstalled)
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
        val installed = LinkedHashSet<String>()
        for (image in images) {
            checkCancelled()
            when (val decision = tracker.decide("${image.partition}.img")) {
                is DsuPartitionPolicy.Decision.Install -> {
                    image.open().use { installImage(decision.partition, image.sizeHint, it) }
                    installed += decision.partition
                }
                // Already reported by the preflight.
                is DsuPartitionPolicy.Decision.Skip -> Unit
                is DsuPartitionPolicy.Decision.Reject -> fail(InstallationStep.ERROR, decision.reason)
            }
        }
        verifyInstalledSet(installed)
    }

    private fun loadCatalog(source: DsuImageSource.ZipPackage): List<ZipCatalogEntry>? =
        try {
            source.catalog?.invoke()
        } catch (e: Exception) {
            log("ZIP central directory unavailable, streaming only: $e")
            null
        }

    private fun installZip(open: () -> InputStream, catalog: List<ZipCatalogEntry>?) {
        // use{} on the ZipInputStream itself so its Inflater is released deterministically
        // (closing it also closes the underlying stream), on success and on every failure.
        zipStreamFactory(open()).use { zis ->
            val tracker = DsuPartitionPolicy.Tracker()
            val installed = LinkedHashSet<String>()
            while (true) {
                checkCancelled()
                val entry = zis.nextEntry ?: break
                when (val decision = tracker.decide(entry.name, entry.isDirectory)) {
                    is DsuPartitionPolicy.Decision.Install -> {
                        // entry.size is -1 for streamed (data descriptor) entries. Use the
                        // central directory size if available, else pass -1 through:
                        // installImage detects the format first (sparse header provides the
                        // size) and only rejects raw images whose size is still unknown.
                        val sizeHint = if (entry.size >= 0) entry.size else catalogSize(catalog, entry.name)
                        log("Installing: ${entry.name} (zip size: ${entry.size}, hint: $sizeHint)")
                        installImage(
                            decision.partition,
                            sizeHint,
                            NonClosingInputStream(zis),
                            unknownRawSizeMessage = "${entry.name} is a raw image stored as a streamed ZIP " +
                                "entry without a recorded uncompressed size; this is not supported. " +
                                "Repack the ZIP with sizes in the local headers, or use a sparse image.",
                        ) {
                            // Read the rest of the entry so ZipInputStream verifies CRC-32 and
                            // sizes (data descriptor) before the partition is closed.
                            drainEntry(zis, entry.name)
                        }
                        installed += decision.partition
                    }
                    is DsuPartitionPolicy.Decision.Skip ->
                        if (plan == null) reportSkip(entry.name, decision.reason) else log("Skipping ${entry.name}")
                    is DsuPartitionPolicy.Decision.Reject -> fail(InstallationStep.ERROR, decision.reason)
                }
            }
            verifyInstalledSet(installed)
        }
    }

    private fun catalogSize(catalog: List<ZipCatalogEntry>?, name: String): Long {
        val matches = catalog?.filter { it.name == name && !it.isDirectory } ?: return -1
        return if (matches.size == 1) matches[0].size else -1
    }

    private fun drainEntry(zis: ZipInputStream, name: String) {
        val buf = ByteArray(8192)
        var extra = 0L
        while (true) {
            val n = zis.read(buf)
            if (n < 0) break
            extra += n
        }
        if (extra > 0) log("$name: ignored $extra trailing bytes after the image data")
    }

    private fun installImage(
        partition: String,
        sizeHint: Long,
        input: InputStream,
        unknownRawSizeMessage: String? = null,
        beforeClose: () -> Unit = {},
    ) {
        checkCancelled()
        // Detect the format first: a sparse header carries the (validated) unsparse size.
        val sis = SparseInputStream(BufferedInputStream(input))
        val isSparse = sis.unsparseSize != -1L
        val partitionSize = if (isSparse) sis.unsparseSize else sizeHint
        if (partitionSize <= 0) {
            if (!isSparse && sizeHint < 0) {
                fail(
                    InstallationStep.ERROR,
                    unknownRawSizeMessage ?: "Unknown uncompressed size for raw image $partition",
                )
            }
            fail(InstallationStep.ERROR, "Invalid size for $partition: $partitionSize")
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
        beforeClose()
        closePartition(partition)
        log("Partition $partition installed, readOnly: true, partitionSize: $partitionSize")
    }

    /** Lets image readers consume a zip entry without closing the whole zip stream. */
    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }
}
