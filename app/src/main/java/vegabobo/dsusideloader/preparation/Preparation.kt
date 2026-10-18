package vegabobo.dsusideloader.preparation

import android.net.Uri
import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Job
import vegabobo.dsusideloader.core.StorageManager
import vegabobo.dsusideloader.model.DSUConstants
import vegabobo.dsusideloader.model.DSUInstallationSource
import vegabobo.dsusideloader.model.Session

class Preparation(
    private val storageManager: StorageManager,
    private val session: Session,
    private val job: Job,
    /** Workspace directory owned by this task (see TaskWorkspaces); nothing else is written. */
    private val taskKey: String,
    private val onStepUpdate: (step: InstallationStep) -> Unit,
    private val onPreparationProgressUpdate: (progress: Float) -> Unit,
    private val onCanceled: () -> Unit,
    private val onPreparationFinished: (preparedDSU: DSUInstallationSource) -> Unit,
) : () -> Unit {

    private val userSelectedImageSize = session.userSelection.userSelectedImageSize
    private val userSelectedFileUri = session.userSelection.selectedFileUri

    override fun invoke() {
        // Only the current task can be preparing (task gate), so leftovers of earlier tasks
        // and of the old flat layout can be removed (best effort).
        val stale = runCatching { storageManager.removeStaleWorkspaces(taskKey) }
        if (stale.getOrNull()?.isNotEmpty() != false) {
            Log.w("Preparation", "Could not remove stale workspace entries: ${stale.getOrNull() ?: stale.exceptionOrNull()}")
        }
        if (session.preferences.useBuiltinInstaller && session.isRoot()) {
            prepareRooted()
            return
        }
        prepareForDSU()
    }

    private fun prepareRooted() {
        val source: DSUInstallationSource = when (getExtension(userSelectedFileUri)) {
            "img" -> {
                DSUInstallationSource.SingleSystemImage(
                    userSelectedFileUri,
                    storageManager.getFilesizeFromUri(userSelectedFileUri),
                )
            }

            "xz", "gz", "gzip" -> {
                val result = extractFile(userSelectedFileUri)
                DSUInstallationSource.SingleSystemImage(result.first, result.second)
            }

            "zip" -> {
                DSUInstallationSource.DsuPackage(userSelectedFileUri)
            }

            else -> {
                throw Exception("Unsupported filetype")
            }
        }
        if (!job.isCancelled) {
            onPreparationFinished(source)
        } else {
            onCanceled()
        }
    }

    private fun prepareForDSU() {
        storageManager.cleanTaskWorkspace(taskKey, true)
        val fileExtension = getExtension(userSelectedFileUri)
        val preparedFilePair =
            when (fileExtension) {
                "xz" -> prepareXz(userSelectedFileUri)
                "img" -> prepareImage(userSelectedFileUri)
                "gz", "gzip" -> prepareGz(userSelectedFileUri)
                "zip" -> prepareZip(userSelectedFileUri)
                else -> throw Exception("Unsupported filetype")
            }

        val preparedUri = preparedFilePair.first
        val preparedFileSize = preparedFilePair.second

        val source = if (fileExtension == "zip") {
            DSUInstallationSource.DsuPackage(preparedUri)
        } else {
            DSUInstallationSource.SingleSystemImage(preparedUri, preparedFileSize)
        }

        onStepUpdate(InstallationStep.WAITING_USER_CONFIRMATION)

        // Intermediate files only (the prepared .gz is kept for the DSU app); a failed delete is
        // not fatal here, the cancel/failure path and the next task clean again.
        runCatching { storageManager.cleanTaskWorkspace(taskKey, job.isCancelled) }
            .onFailure { Log.w("Preparation", "Workspace cleanup failed", it) }

        if (!job.isCancelled) {
            onPreparationFinished(source)
        } else {
            onCanceled()
        }
    }

    private fun prepareZip(zipFile: Uri): Pair<Uri, Long> {
        val uri = getSafeUri(zipFile)
        return Pair(uri, -1)
    }

    private fun prepareXz(xzFile: Uri): Pair<Uri, Long> {
        val outputFile = getFileName(xzFile)
        onStepUpdate(InstallationStep.DECOMPRESSING_XZ)
        val imgFile = FileUnPacker(
            storageManager,
            xzFile,
            outputFile,
            taskKey,
            job,
            onPreparationProgressUpdate,
        ).unpack()
        return prepareImage(imgFile.first)
    }

    private fun prepareImage(imageFile: Uri): Pair<Uri, Long> {
        val outputFile = "${getFileName(imageFile)}.img.gz"
        onStepUpdate(InstallationStep.COMPRESSING_TO_GZ)
        val compressedFilePair = FileUnPacker(
            storageManager,
            imageFile,
            outputFile,
            taskKey,
            job,
            onPreparationProgressUpdate,
        ).pack()
        return Pair(compressedFilePair.first, storageManager.getFilesizeFromUri(imageFile))
    }

    private fun prepareGz(gzFile: Uri): Pair<Uri, Long> {
        val uri = getSafeUri(gzFile)
        if (userSelectedImageSize != DSUConstants.DEFAULT_IMAGE_SIZE) {
            // Advanced override: skips the full scan, so the archive is NOT validated here
            // (a wrong size or corrupt archive only fails later, in the system installer).
            Log.w("Preparation", "Using manual image size $userSelectedImageSize without gzip verification")
            return Pair(uri, userSelectedImageSize)
        }

        // The gzip ISIZE trailer is the size modulo 2^32 (and only of the last member),
        // so it is ambiguous for images >= 4 GiB. Decompress (without writing to disk)
        // and count the exact size; this also rejects truncated/corrupt archives.
        onStepUpdate(InstallationStep.VERIFYING_IMAGE_SIZE)
        val compressedSize = storageManager.getFilesizeFromUri(uri)
        val imageSize = storageManager.openInputStream(uri).use { input ->
            GzipImageSize.countUncompressedBytes(input, { job.isCancelled }) { read ->
                if (compressedSize > 0) {
                    onPreparationProgressUpdate((read.toFloat() / compressedSize).coerceIn(0F, 1F))
                }
            }
        }
        if (imageSize <= 0) {
            throw IOException("Decompressed image is empty")
        }
        return Pair(uri, imageSize)
    }

    private fun extractFile(uri: Uri): Pair<Uri, Long> {
        return extractFile(uri, "system")
    }

    private fun extractFile(uri: Uri, partitionName: String): Pair<Uri, Long> {
        onStepUpdate(InstallationStep.EXTRACTING_FILE)
        return FileUnPacker(
            storageManager,
            uri,
            "$partitionName.img",
            taskKey,
            job,
            onPreparationProgressUpdate,
        ).unpack()
    }

    private fun getSafeUri(uri: Uri): Uri {
        onStepUpdate(InstallationStep.COPYING_FILE)
        return storageManager.getUriSafe(uri, taskKey)
    }

    private fun getFileName(uri: Uri): String {
        return storageManager.getFilenameFromUri(uri)
            .substringBeforeLast(".")
    }

    private fun getExtension(uri: Uri): String {
        return storageManager.getFilenameFromUri(uri)
            .substringAfterLast(".", "")
    }
}
