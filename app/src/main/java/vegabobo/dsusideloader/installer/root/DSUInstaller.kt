package vegabobo.dsusideloader.installer.root

import android.app.Application
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.util.Log
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import kotlinx.coroutines.Job
import org.apache.commons.compress.archivers.zip.ZipFile
import org.lsposed.hiddenapibypass.HiddenApiBypass
import vegabobo.dsusideloader.model.DSUInstallationSource
import vegabobo.dsusideloader.model.Type
import vegabobo.dsusideloader.preparation.InstallationStep
import vegabobo.dsusideloader.service.PrivilegedProvider

/**
 * DSU Installer implementation using Android APIs
 * Based on InstallationAsyncTask from DynamicSystemInstallationService
 * DynamicSystemInstallationService/src/com/android/dynsystem/InstallationAsyncTask.java
 *
 * Calling APIs directly to install images are fast, because we can apply images directly
 * instead of preparing a file exclusively to install via DSU system-app
 * also, having access to APIs make everything more flexible.
 *
 * Unfortunately, this implementation has a downside, it requires "MANAGE_DYNAMIC_SYSTEM"
 * and this permission has a protection level of "signature".
 *
 * That's why this installation way requires root.
 *
 * The installation flow itself (result checks, cancellation, cleanup) lives in
 * [DsuInstallPipeline]; this class only adapts Android/privileged service APIs to it.
 * [onFinished] is called exactly once per run with the full terminal result, including the
 * cleanup result, and only after cleanup has returned.
 */
class DSUInstaller(
    private val application: Application,
    private val userdataSize: Long,
    private val dsuInstallation: DSUInstallationSource,
    private var installationJob: Job = Job(),
    private val onInstallationProgressUpdate: (progress: Float, partition: String) -> Unit,
    private val onCreatePartition: (partition: String) -> Unit,
    private val onInstallationStepUpdate: (step: InstallationStep) -> Unit,
    private val onFinished: (result: DsuInstallResult) -> Unit,
    private val onImageSkipped: (image: String) -> Unit = {},
) : () -> Unit, DynamicSystemImpl() {

    private val tag = this.javaClass.simpleName

    object Constants {
        const val DEFAULT_SLOT = DsuInstallPipeline.DEFAULT_SLOT
        const val SHARED_MEM_SIZE: Int = 524288
    }

    private fun getFdDup(sharedMemory: SharedMemory): ParcelFileDescriptor {
        return HiddenApiBypass.invoke(
            sharedMemory.javaClass,
            sharedMemory,
            "getFdDup",
        ) as ParcelFileDescriptor
    }

    /** SharedMemory + mapping + dup'd fd, all released by [close]. */
    private inner class AshmemWriteChannel(partition: String) : DsuWriteChannel {
        private val sharedMemory: SharedMemory =
            SharedMemory.create("dsu_buffer_$partition", Constants.SHARED_MEM_SIZE)
        private var buffer: ByteBuffer? = null
        private var fd: ParcelFileDescriptor? = null

        init {
            try {
                buffer = sharedMemory.mapReadWrite()
            } catch (e: Exception) {
                sharedMemory.close()
                throw e
            }
        }

        override val capacity: Int = sharedMemory.size

        override fun register(): Boolean {
            val dup = getFdDup(sharedMemory)
            fd = dup
            return setAshmem(dup, sharedMemory.size.toLong())
        }

        override fun submit(data: ByteArray, length: Int): Boolean {
            val buf = buffer ?: return false
            buf.position(0)
            buf.put(data, 0, length)
            return submitFromAshmem(length.toLong())
        }

        override fun close() {
            buffer?.let { runCatching { SharedMemory.unmap(it) } }
            buffer = null
            fd?.let { runCatching { it.close() } }
            fd = null
            runCatching { sharedMemory.close() }
        }
    }

    private val backend = object : DsuBackend {
        override fun enableDynamicSystemFeature() = PrivilegedProvider.getService().setDynProp()
        override fun isInUse() = this@DSUInstaller.isInUse
        override fun isInstalled() = this@DSUInstaller.isInstalled
        override fun forceStopDsuApp() = this@DSUInstaller.forceStopDSU()
        override fun startInstallation(slot: String) = this@DSUInstaller.startInstallation(slot)
        override fun createPartition(name: String, size: Long, readOnly: Boolean) =
            this@DSUInstaller.createPartition(name, size, readOnly)

        override fun bytesProcessed() = installationProgress.bytes_processed
        override fun openWriteChannel(partition: String): DsuWriteChannel =
            AshmemWriteChannel(partition)

        override fun closePartition() = this@DSUInstaller.closePartition()
        override fun finishInstallation() = this@DSUInstaller.finishInstallation()
        override fun abort() = this@DSUInstaller.abort()
        override fun remove() = this@DSUInstaller.remove()
    }

    private fun openInputStream(uri: Uri): InputStream {
        return application.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Unable to open $uri")
    }

    /** Reads the ZIP central directory of a seekable local document (no decompression). */
    private fun readZipCatalog(uri: Uri): List<ZipCatalogEntry>? {
        val pfd = application.contentResolver.openFileDescriptor(uri, "r") ?: return null
        pfd.use {
            FileInputStream(it.fileDescriptor).use { fis ->
                ZipFile(fis.channel).use { zip ->
                    return zip.entries.toList().map { e ->
                        ZipCatalogEntry(e.name, e.isDirectory, e.size)
                    }
                }
            }
        }
    }

    private fun buildSource(): DsuImageSource? {
        return when (dsuInstallation.type) {
            Type.SINGLE_SYSTEM_IMAGE -> DsuImageSource.SingleImage(
                DsuImageSource.Image("system", dsuInstallation.fileSize) {
                    openInputStream(dsuInstallation.uri)
                },
            )

            Type.MULTIPLE_IMAGES -> DsuImageSource.MultipleImages(
                dsuInstallation.images.map { image ->
                    DsuImageSource.Image(image.partitionName, image.fileSize) {
                        openInputStream(image.uri)
                    }
                },
            )

            Type.DSU_PACKAGE -> DsuImageSource.ZipPackage(
                catalog = { readZipCatalog(dsuInstallation.uri) },
            ) { openInputStream(dsuInstallation.uri) }
            Type.URL -> DsuImageSource.ZipPackage { URL(dsuInstallation.uri.toString()).openStream() }
            Type.NONE -> null
        }
    }

    fun install(): DsuInstallResult {
        val source = buildSource()
            ?: return DsuInstallResult.Failed(InstallationStep.ERROR, "No installation source", null)
        return DsuInstallPipeline(
            backend = backend,
            userdataSize = userdataSize,
            source = source,
            isCancelled = { installationJob.isCancelled },
            listener = object : DsuInstallPipeline.Listener {
                override fun onCreatePartition(partition: String) = this@DSUInstaller.onCreatePartition(partition)
                override fun onSkipped(image: String, reason: String) = onImageSkipped(image)
                override fun onStep(step: InstallationStep) = onInstallationStepUpdate(step)
                override fun onProgress(progress: Float, partition: String) =
                    onInstallationProgressUpdate(progress, partition)
            },
            log = { Log.d(tag, it) },
        ).run()
    }

    override fun invoke() {
        val result = install()
        Log.d(tag, "Installation finished: $result")
        onFinished(result)
    }
}
