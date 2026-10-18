package vegabobo.dsusideloader.loader

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallPlannerTest {
    private val source = ImageSource("content://images/system.img", "system.img")
    private val options = InstallOptions(2 * InstallPlanner.GIB)
    private val shizuku = LoaderEnvironment(33, "SHIZUKU", false, false)

    @Test fun shellDelegatesToSystemRatherThanClaimingDirectPermission() {
        assertEquals(InstallerBackend.SYSTEM_DSU, InstallPlanner.create(source, options, shizuku).backend)
    }

    @Test fun adbNeedsUserCommand() {
        assertEquals(InstallerBackend.ADB_ASSISTED, InstallPlanner.create(source, options, shizuku.copy(operationMode = "ADB", adb = true)).backend)
    }

    @Test fun explicitRootNeverFallsBack() {
        assertTrue(runCatching { InstallPlanner.create(source, options.copy(backend = BackendPreference.ROOT_DIRECT), shizuku) }.isFailure)
        assertTrue(runCatching { InstallPlanner.create(source, options.copy(backend = BackendPreference.ROOT_DIRECT), shizuku.copy(sdk = 29, root = true)) }.isFailure)
    }

    @Test fun rootChoiceIsFrozen() {
        val plan = InstallPlanner.create(source, options.copy(backend = BackendPreference.ROOT_DIRECT), shizuku.copy(operationMode = "ROOT", root = true))
        assertEquals(InstallerBackend.ROOT_DIRECT, plan.backend)
        assertTrue(runCatching { InstallPlanner.assertEnvironment(plan, shizuku) }.isFailure)
    }

    @Test fun systemCanBeSelectedOnRootDevice() {
        val plan = InstallPlanner.create(source, options.copy(backend = BackendPreference.SYSTEM_DSU, preferRoot = true), shizuku.copy(root = true))
        assertEquals(InstallerBackend.SYSTEM_DSU, plan.backend)
    }

    @Test fun changingModeAfterPlanningIsRejected() {
        val plan = InstallPlanner.create(source, options, shizuku)
        InstallPlanner.assertEnvironment(plan, shizuku)
        assertTrue(runCatching { InstallPlanner.assertEnvironment(plan, shizuku.copy(operationMode = "ADB", adb = true)) }.isFailure)
    }

    @Test fun zipOnAndroid10AndUnknownFormatsAreRejected() {
        assertTrue(runCatching { InstallPlanner.create(source.copy(name = "package.zip"), options, shizuku.copy(sdk = 29)) }.isFailure)
        assertTrue(runCatching { InstallPlanner.create(source.copy(name = "boot.bin"), options, shizuku) }.isFailure)
    }

    @Test fun formatsAreCaseInsensitive() {
        for ((name, format) in listOf("a.IMG" to ImageFormat.RAW, "a.GZIP" to ImageFormat.GZIP, "a.XZ" to ImageFormat.XZ, "a.ZIP" to ImageFormat.DSU_ZIP)) {
            assertEquals(format, InstallPlanner.create(source.copy(name = name), options, shizuku).format)
        }
    }

    @Test fun malformedHashesAreRejectedRatherThanTruncated() {
        for (hash in listOf("a".repeat(63), "a".repeat(65), "z".repeat(64))) {
            assertTrue(runCatching { InstallPlanner.create(source.copy(expectedSha256 = hash), options, shizuku) }.isFailure)
        }
        assertEquals("a".repeat(64), InstallPlanner.create(source.copy(expectedSha256 = " A" + "A".repeat(63) + " "), options, shizuku).source.expectedSha256)
    }

    @Test fun sizesCannotOverflowOrBecomeZero() {
        for (size in listOf("0GB", "-1GB", "99999999999999999999999GB", "8589934592GB")) {
            assertTrue(runCatching { InstallPlanner.userdataBytes(size) }.isFailure)
        }
        assertEquals(2 * InstallPlanner.GIB, InstallPlanner.userdataBytes(""))
        assertEquals(8 * InstallPlanner.GIB, InstallPlanner.userdataBytes("8GB"))
        assertEquals(-1L, InstallPlanner.imageBytes(""))
        assertTrue(runCatching { InstallPlanner.imageBytes("9223372036854775808b") }.isFailure)
        assertTrue(runCatching { InstallPlanner.imageBytes("0b") }.isFailure)
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun verifiedCopyMatchesExactSourceBytes() {
        val bytes = ByteArray(700_003) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), ImageIntegrity.copyVerified(ByteArrayInputStream(bytes), out, hash(bytes)) { false })
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test fun mismatchAndEmptyImageNeverPassVerification() {
        assertTrue(runCatching { ImageIntegrity.copyVerified(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), "0".repeat(64)) { false } }.exceptionOrNull() is IOException)
        assertTrue(runCatching { ImageIntegrity.copyVerified(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), hash(byteArrayOf())) { false } }.exceptionOrNull() is IOException)
    }

    @Test fun cancellationStopsBeforeAnotherWrite() {
        val bytes = ByteArray(900_000)
        val out = ByteArrayOutputStream()
        var checks = 0
        val result = runCatching { ImageIntegrity.copyVerified(ByteArrayInputStream(bytes), out, hash(bytes)) { ++checks > 1 } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(256 * 1024, out.size())
    }

    @Test fun outputFailurePropagates() {
        val bytes = byteArrayOf(1, 2)
        val out = object : java.io.OutputStream() { override fun write(b: Int) { throw IOException("disk full") } }
        assertEquals("disk full", runCatching { ImageIntegrity.copyVerified(ByteArrayInputStream(bytes), out, hash(bytes)) { false } }.exceptionOrNull()?.message)
    }
}
