package vegabobo.dsusideloader.installer.privileged

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingSdVolumesTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val ids = listOf("public:179,1", "public:8,17")
        assertEquals(ids, PendingSdVolumes.decode(PendingSdVolumes.encode(ids)))
    }

    @Test
    fun decodeIgnoresBlankAndDuplicates() {
        assertEquals(emptyList<String>(), PendingSdVolumes.decode(""))
        assertEquals(listOf("public:1,1"), PendingSdVolumes.decode("public:1,1\n\npublic:1,1\n"))
    }

    @Test
    fun selectsOnlyPublicVolumes() {
        assertEquals(
            listOf("public:179,1"),
            PendingSdVolumes.selectPublic(listOf("private", "emulated;0", "public:179,1", null)),
        )
    }

    @Test
    fun reconcileForgetsVolumesThatAreGone() {
        val (toMount, gone) = PendingSdVolumes.reconcile(
            listOf("public:179,1", "public:8,1"),
            listOf("private", "public:179,1"),
        )
        assertEquals(listOf("public:179,1"), toMount)
        assertEquals(listOf("public:8,1"), gone)
    }
}
