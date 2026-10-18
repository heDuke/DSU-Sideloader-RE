package vegabobo.dsusideloader.loader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCatalogTest {
    @Test fun metadataRoundTripPreservesUnicodeAndSeparators() {
        val image = SavedImage("content://provider/a:1", "测试\tGSI\n.img", "a".repeat(64), 8, BackendPreference.SYSTEM_DSU, true, "备注\nwith\ttabs")
        assertEquals(listOf(image), LibraryCatalog.parse(LibraryCatalog.serialize(listOf(image))))
    }

    @Test fun upsertReplacesBySourceAndLimitsCatalog() {
        val images = (1..60).map { SavedImage("content://p/$it", "$it.img") }
        val updated = LibraryCatalog.upsert(images, images[3].copy(favorite = true))
        assertEquals(50, updated.size)
        assertEquals(1, updated.count { it.uri == images[3].uri })
        assertTrue(updated.first().favorite)
    }

    @Test fun unsupportedOrCorruptDataIsReportedWithoutOverwritingIt() {
        for (value in listOf("v9\n", "v1\nbad record", LibraryCatalog.serialize(listOf(SavedImage("file:///a", "a.img"))), LibraryCatalog.serialize(listOf(SavedImage("content://p/a", "a.img", userdataGiB = 0))))) {
            assertTrue(runCatching { LibraryCatalog.parse(value) }.isFailure)
        }
        assertEquals(emptyList<SavedImage>(), LibraryCatalog.parse(""))
    }
}
