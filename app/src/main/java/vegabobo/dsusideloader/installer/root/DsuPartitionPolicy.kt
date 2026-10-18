package vegabobo.dsusideloader.installer.root

/**
 * Decides which images of a DSU package (or of a multi-image selection) can be installed.
 *
 * Pure Kotlin, no Android dependencies, so it can be unit tested on the JVM.
 *
 * Rules:
 *  - Only root-level `<partition>.img` entries are considered images. Other files
 *    (metadata, directories, non-img files) are skipped.
 *  - Paths that are absolute, contain `..`, backslashes, NUL or nested directories are
 *    rejected: they are never mapped to a partition name.
 *  - Partitions that DSU must never write (vbmeta, boot, userdata, ...) are skipped,
 *    matching AOSP InstallationAsyncTask behaviour for DSU packages.
 *  - Remaining partitions must be in [ALLOWED_PARTITIONS], otherwise they are rejected.
 *  - A partition may appear only once; duplicates are rejected.
 */
object DsuPartitionPolicy {

    /** Partitions that a DSU image may provide. */
    val ALLOWED_PARTITIONS: Set<String> = setOf(
        "system",
        "system_ext",
        "product",
        "vendor",
        "odm",
        "vendor_dlkm",
        "odm_dlkm",
        "system_dlkm",
    )

    /**
     * Images that may legitimately be present in a DSU package but must not be installed
     * (AOSP InstallationAsyncTask UNSUPPORTED_PARTITIONS plus related boot/verification images).
     */
    val SKIPPED_PARTITIONS: Set<String> = setOf(
        "vbmeta",
        "vbmeta_system",
        "vbmeta_vendor",
        "boot",
        "init_boot",
        "vendor_boot",
        "recovery",
        "userdata",
        "dtbo",
        "super",
        "super_empty",
        "system_other",
        "scratch",
    )

    private val PARTITION_NAME_REGEX = Regex("^[a-z0-9_]+$")

    sealed class Decision {
        data class Install(val partition: String) : Decision()
        data class Skip(val reason: String) : Decision()
        data class Reject(val reason: String) : Decision()
    }

    /**
     * Normalises a zip entry or file name and returns the decision for it,
     * without considering duplicates (see [Tracker]).
     */
    fun decide(entryName: String, isDirectory: Boolean = false): Decision {
        if (isDirectory) return Decision.Skip("directory")
        if (entryName.isEmpty()) return Decision.Reject("empty entry name")
        if (entryName.contains('\u0000')) return Decision.Reject("NUL in entry name")
        if (entryName.contains('\\')) return Decision.Reject("backslash in entry name: $entryName")
        if (entryName.startsWith("/")) return Decision.Reject("absolute path: $entryName")
        val segments = entryName.split('/')
        if (segments.any { it == ".." }) return Decision.Reject("path traversal: $entryName")

        // Drop "." segments and empty segments produced by "a//b" or "./system.img".
        val normalized = segments.filter { it.isNotEmpty() && it != "." }
        if (normalized.isEmpty()) return Decision.Reject("invalid entry name: $entryName")
        val fileName = normalized.last()
        if (!fileName.endsWith(".img")) return Decision.Skip("not an image: $entryName")
        if (normalized.size > 1) {
            return Decision.Reject("image inside a directory is not supported: $entryName")
        }

        val partition = fileName.removeSuffix(".img")
        if (!PARTITION_NAME_REGEX.matches(partition)) {
            return Decision.Reject("invalid partition name: $partition")
        }
        if (SKIPPED_PARTITIONS.contains(partition)) {
            return Decision.Skip("partition not installable via DSU: $partition")
        }
        if (!ALLOWED_PARTITIONS.contains(partition)) {
            return Decision.Reject("unsupported partition: $partition")
        }
        return Decision.Install(partition)
    }

    /** Same as [decide] but also rejects partitions that were already accepted. */
    class Tracker {
        private val seen = HashSet<String>()

        fun decide(entryName: String, isDirectory: Boolean = false): Decision {
            val decision = DsuPartitionPolicy.decide(entryName, isDirectory)
            if (decision is Decision.Install && !seen.add(decision.partition)) {
                return Decision.Reject("duplicate partition: ${decision.partition}")
            }
            return decision
        }
    }
}
