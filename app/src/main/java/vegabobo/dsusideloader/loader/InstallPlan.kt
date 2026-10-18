package vegabobo.dsusideloader.loader

import java.util.Locale

enum class InstallerBackend { SYSTEM_DSU, ROOT_DIRECT, ADB_ASSISTED }
enum class BackendPreference { AUTO, SYSTEM_DSU, ROOT_DIRECT }
enum class ImageFormat { RAW, GZIP, XZ, DSU_ZIP }

/** Observed capabilities, not permissions inferred from a Shizuku/system label. */
data class LoaderEnvironment(val sdk: Int, val operationMode: String, val root: Boolean, val adb: Boolean)

data class ImageSource(val uri: String, val name: String, val expectedSha256: String = "")

data class InstallOptions(
    val userdataBytes: Long,
    val imageSizeOverride: Long = -1,
    val backend: BackendPreference = BackendPreference.AUTO,
    val preferRoot: Boolean = false,
)

/** Frozen before preparation; a retry must keep the same backend and parameters. */
data class InstallPlan(
    val source: ImageSource,
    val format: ImageFormat,
    val options: InstallOptions,
    val backend: InstallerBackend,
    val environment: LoaderEnvironment,
)

object InstallPlanner {
    const val GIB = 1024L * 1024L * 1024L

    fun create(source: ImageSource, options: InstallOptions, environment: LoaderEnvironment): InstallPlan {
        require(source.uri.isNotBlank()) { "No image selected" }
        require(options.userdataBytes >= GIB) { "Userdata must be at least 1 GiB" }
        require(options.imageSizeOverride == -1L || options.imageSizeOverride > 0) { "Invalid image size" }
        val hash = source.expectedSha256.trim().lowercase(Locale.ROOT)
        require(hash.isEmpty() || hash.matches(Regex("[0-9a-f]{64}"))) { "SHA-256 must contain 64 hexadecimal characters" }
        val format = when (source.name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "img" -> ImageFormat.RAW
            "gz", "gzip" -> ImageFormat.GZIP
            "xz" -> ImageFormat.XZ
            "zip" -> ImageFormat.DSU_ZIP
            else -> throw IllegalArgumentException("Unsupported image format")
        }
        require(environment.sdk >= 29) { "Android 10 or later is required" }
        require(format != ImageFormat.DSU_ZIP || environment.sdk >= 30) { "DSU ZIP requires Android 11 or later" }
        val rootAvailable = environment.root && environment.sdk >= 30
        val backend = when (options.backend) {
            BackendPreference.ROOT_DIRECT -> {
                require(rootAvailable) { "Direct installation requires root and Android 11 or later" }
                InstallerBackend.ROOT_DIRECT
            }
            BackendPreference.AUTO -> when {
                options.preferRoot && rootAvailable -> InstallerBackend.ROOT_DIRECT
                environment.adb -> InstallerBackend.ADB_ASSISTED
                else -> InstallerBackend.SYSTEM_DSU
            }
            BackendPreference.SYSTEM_DSU -> if (environment.adb) InstallerBackend.ADB_ASSISTED else InstallerBackend.SYSTEM_DSU
        }
        return InstallPlan(source.copy(expectedSha256 = hash), format, options, backend, environment)
    }

    fun assertEnvironment(plan: InstallPlan, current: LoaderEnvironment) {
        require(plan.environment == current) { "Installation permissions changed; select the image again" }
    }

    fun userdataBytes(input: String): Long {
        val digits = input.trim().removeSuffix("GB").trim()
        if (digits.isEmpty()) return 2 * GIB
        val gib = digits.toLongOrNull()
        require(gib != null && gib in 1..(Long.MAX_VALUE / GIB)) { "Invalid userdata size" }
        return Math.multiplyExact(gib, GIB)
    }

    fun imageBytes(input: String): Long {
        val digits = input.trim().removeSuffix("b").trim()
        if (digits.isEmpty()) return -1
        return digits.toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("Invalid image size")
    }
}
