package vegabobo.dsusideloader.loader

import java.util.Base64

data class SavedImage(
    val uri: String,
    val name: String,
    val sha256: String = "",
    val userdataGiB: Long = 2,
    val backend: BackendPreference = BackendPreference.AUTO,
    val favorite: Boolean = false,
    val note: String = "",
)

/** Versioned metadata only: no file contents, grants, shell commands or credentials. */
object LibraryCatalog {
    const val LIMIT = 50
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private fun encode(value: String) = encoder.encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String) = String(decoder.decode(value), Charsets.UTF_8)

    fun serialize(images: List<SavedImage>): String = "v1\n" + images.take(LIMIT).joinToString("\n") {
        listOf(encode(it.uri), encode(it.name), encode(it.sha256), it.userdataGiB.toString(), it.backend.name, it.favorite.toString(), encode(it.note)).joinToString("\t")
    }

    fun parse(value: String): List<SavedImage> {
        if (value.isEmpty()) return emptyList()
        require(value.length <= 512 * 1024) { "Image library is too large" }
        val lines = value.lines()
        require(lines.first() == "v1") { "Unsupported image library version" }
        return lines.drop(1).filter { it.isNotBlank() }.take(LIMIT).map { line ->
            val p = line.split('\t')
            require(p.size == 7) { "Invalid image library record" }
            val image = SavedImage(decode(p[0]), decode(p[1]), decode(p[2]), p[3].toLong(), BackendPreference.valueOf(p[4]), p[5].toBooleanStrict(), decode(p[6]))
            require(image.uri.startsWith("content://")) { "Library source must be a document URI" }
            require(image.userdataGiB in 1..(Long.MAX_VALUE / InstallPlanner.GIB)) { "Invalid saved userdata size" }
            require(image.sha256.isEmpty() || image.sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid saved SHA-256" }
            image
        }.distinctBy { it.uri }
    }

    fun upsert(images: List<SavedImage>, image: SavedImage): List<SavedImage> =
        (listOf(image) + images.filterNot { it.uri == image.uri }).take(LIMIT)
}
