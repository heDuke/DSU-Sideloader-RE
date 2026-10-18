package vegabobo.dsusideloader.preparation

import android.net.Uri
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.utils.InputStreamStatistics
import vegabobo.dsusideloader.core.StorageManager

class FileUnPacker(
    private val storageManager: StorageManager,
    private val inputFile: Uri,
    private val outputFile: String,
    private val installationJob: Job,
    private val onProgressChange: (Float) -> Unit,
) {

    private val inputFileSize = storageManager.getFilesizeFromUri(inputFile)

    /**
     * Copies until EOF. Throws CancellationException if the job is cancelled, so a partial
     * output is never returned as a complete file; decompressor errors (truncated/corrupt
     * input) propagate as IOException.
     */
    private fun copy(
        inputStr: InputStream,
        outputStr: OutputStream,
        onReadBytes: (Long) -> Unit,
    ) {
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        while (true) {
            if (installationJob.isCancelled) throw CancellationException("Cancelled")
            val n = inputStr.read(buffer)
            if (n < 0) break
            outputStr.write(buffer, 0, n)
            read += n
            onReadBytes(read)
        }
        outputStr.flush()
    }

    fun pack(): Pair<Uri, Long> {
        val finalFile = storageManager.createDocumentFile(outputFile)
        storageManager.openInputStream(inputFile).use { input ->
            GzipCompressorOutputStream(storageManager.openOutputStream(finalFile.uri)).use { output ->
                copy(input, output) { updateProgress(inputFileSize, it) }
            }
        }
        val fileLength = storageManager.getFilesizeFromUri(finalFile.uri)
        return Pair(finalFile.uri, fileLength)
    }

    fun unpack(): Pair<Uri, Long> {
        val name = storageManager.getFilenameFromUri(inputFile)
        if (!name.endsWith("xz") && !name.endsWith("gz") && !name.endsWith("gzip")) {
            throw IllegalArgumentException("File type not supported: $name")
        }
        val finalFile = storageManager.createDocumentFile(outputFile)
        storageManager.openInputStream(inputFile).use { raw ->
            val archive: InputStream = when {
                name.endsWith("xz") -> XZCompressorInputStream(raw, true)
                else -> GzipCompressorInputStream(raw, true)
            }
            archive.use {
                storageManager.openOutputStream(finalFile.uri).use { output ->
                    copy(archive, output) {
                        updateProgress(inputFileSize, (archive as InputStreamStatistics).compressedCount)
                    }
                }
            }
        }
        val fileLength = storageManager.getFilesizeFromUri(finalFile.uri)
        return Pair(finalFile.uri, fileLength)
    }

    private fun updateProgress(fileSize: Long, read: Long) {
        if (fileSize <= 0) return
        onProgressChange((read.toFloat() / fileSize.toFloat()).coerceIn(0F, 1F))
    }
}
