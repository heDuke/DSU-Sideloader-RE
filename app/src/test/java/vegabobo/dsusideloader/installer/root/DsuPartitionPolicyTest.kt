package vegabobo.dsusideloader.installer.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vegabobo.dsusideloader.installer.root.DsuPartitionPolicy.Decision

class DsuPartitionPolicyTest {

    private fun assertInstall(entry: String, partition: String) =
        assertEquals(Decision.Install(partition), DsuPartitionPolicy.decide(entry))

    private fun assertSkip(entry: String, isDirectory: Boolean = false) {
        val d = DsuPartitionPolicy.decide(entry, isDirectory)
        assertTrue("expected Skip for $entry but was $d", d is Decision.Skip)
    }

    private fun assertReject(entry: String) {
        val d = DsuPartitionPolicy.decide(entry)
        assertTrue("expected Reject for $entry but was $d", d is Decision.Reject)
    }

    @Test
    fun partitionNameIsFileNameWithoutImgSuffix() {
        // Regression: substringAfterLast(".") used to yield "img" for every entry.
        assertInstall("system.img", "system")
        assertInstall("system_ext.img", "system_ext")
        assertInstall("product.img", "product")
        assertInstall("vendor.img", "vendor")
        assertInstall("./system.img", "system")
    }

    @Test
    fun unsupportedButKnownImagesAreSkipped() {
        listOf("vbmeta", "boot", "userdata", "dtbo", "super_empty", "system_other", "scratch")
            .forEach { assertSkip("$it.img") }
    }

    @Test
    fun nonImageEntriesAndDirectoriesAreSkipped() {
        assertSkip("META-INF/MANIFEST.MF")
        assertSkip("README.txt")
        assertSkip("system.img.sig")
        assertSkip("images/", isDirectory = true)
    }

    @Test
    fun illegalPathsAreRejected() {
        assertReject("../system.img")
        assertReject("a/../../system.img")
        assertReject("/system.img")
        assertReject("..\\system.img")
        assertReject("images/system.img")
        assertReject("")
        assertReject("sys\u0000tem.img")
    }

    @Test
    fun invalidOrUnknownPartitionNamesAreRejected() {
        assertReject("System.img")
        assertReject("sys tem.img")
        assertReject(".img")
        assertReject("my_custom_partition.img")
    }

    @Test
    fun duplicatePartitionsAreRejected() {
        val tracker = DsuPartitionPolicy.Tracker()
        assertEquals(Decision.Install("system"), tracker.decide("system.img"))
        assertEquals(Decision.Install("product"), tracker.decide("product.img"))
        assertTrue(tracker.decide("./system.img") is Decision.Reject)
        // Skipped entries don't count as duplicates.
        assertTrue(tracker.decide("vbmeta.img") is Decision.Skip)
        assertTrue(tracker.decide("vbmeta.img") is Decision.Skip)
    }
}
