package sparrow.vault

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VaultTest {
    private val fast = KdfParams.FAST_FOR_TESTS
    private fun tmp(): Path = Files.createTempDirectory("sparrow").resolve("v.sparrow")
    private val sample = VaultData(
        entries = listOf(
            Entry(title = "Mail", username = "me", password = "p@ss ёжик 🔑", url = "https://mail.example", tags = listOf("work")),
            Entry(kind = Kind.NOTE, title = "Note", notes = "line1\nline2"),
            Entry(kind = Kind.CARD, title = "Visa", cardNumber = "4111111111111111", cvv = "123"),
        ),
    )

    @Test fun roundTripKeepsAllData() {
        val p = tmp()
        VaultSession.create(p, "correct horse".toCharArray(), fast, sample).close()
        val s = VaultSession.open(p, "correct horse".toCharArray())
        assertEquals(sample, s.data)
    }

    @Test fun wrongPasswordIsRejected() {
        val p = tmp()
        VaultSession.create(p, "correct horse".toCharArray(), fast, sample).close()
        assertFailsWith<WrongPasswordException> { VaultSession.open(p, "wrong".toCharArray()) }
    }

    @Test fun fileDoesNotContainPlaintext() {
        val p = tmp()
        VaultSession.create(p, "correct horse".toCharArray(), fast, sample).close()
        val raw = String(Files.readAllBytes(p), Charsets.ISO_8859_1)
        assertFalse("mail.example" in raw || "4111111111111111" in raw)
    }

    @Test fun tamperingWithCiphertextOrHeaderIsDetected() {
        val p = tmp()
        VaultSession.create(p, "correct horse".toCharArray(), fast, sample).close()
        val orig = Files.readAllBytes(p)
        for (pos in listOf(orig.size - 1, 20)) { // last byte of tag, and a byte inside the header
            val bad = orig.copyOf().also { it[pos] = (it[pos].toInt() xor 1).toByte() }
            Files.write(p, bad)
            assertTrue(runCatching { VaultSession.open(p, "correct horse".toCharArray()) }.isFailure, "pos=$pos")
        }
    }

    @Test fun saveChangesNonceAndKeepsBackup() {
        val p = tmp()
        val s = VaultSession.create(p, "correct horse".toCharArray(), fast, sample)
        val a = Files.readAllBytes(p)
        s.save()
        assertNotEquals(a.toList(), Files.readAllBytes(p).toList())
        assertTrue(Files.exists(p.resolveSibling("v.sparrow.bak")))
        s.close()
    }

    @Test fun changePasswordWorks() {
        val p = tmp()
        val s = VaultSession.create(p, "old password".toCharArray(), fast, sample)
        s.changePassword("new password".toCharArray(), fast)
        s.close()
        assertFailsWith<WrongPasswordException> { VaultSession.open(p, "old password".toCharArray()) }
        assertEquals(sample, VaultSession.open(p, "new password".toCharArray()).data)
    }

    @Test fun refusesToOverwriteAndRejectsForeignFiles() {
        val p = tmp()
        VaultSession.create(p, "pw pw pw pw".toCharArray(), fast).close()
        assertFailsWith<FormatException> { VaultSession.create(p, "x".toCharArray(), fast) }
        Files.write(p, ByteArray(100) { 7 })
        assertFailsWith<FormatException> { VaultSession.open(p, "pw".toCharArray()) }
    }

    @Test fun generatorRespectsOptions() {
        val rnd = SecureRandom()
        repeat(200) {
            val s = PasswordGenerator.generate(PasswordGenerator.Options(length = 16, noAmbiguous = true), rnd)
            assertEquals(16, s.length)
            assertTrue(s.any { it.isLowerCase() } && s.any { it.isUpperCase() } && s.any { it.isDigit() } && s.any { !it.isLetterOrDigit() })
            assertTrue(s.none { it in "Il1O0o" })
        }
        val digits = PasswordGenerator.generate(PasswordGenerator.Options(8, lower = false, upper = false, symbols = false))
        assertTrue(digits.all { it.isDigit() })
        assertFailsWith<IllegalArgumentException> { PasswordGenerator.generate(PasswordGenerator.Options(8, false, false, false, false)) }
        assertFailsWith<IllegalArgumentException> { PasswordGenerator.generate(PasswordGenerator.Options(2)) }
    }

    @Test fun importsBitwardenJson() {
        val json = """{"encrypted":false,"folders":[{"id":"f1","name":"Work"}],"items":[
          {"type":1,"name":"GitHub","folderId":"f1","notes":null,"login":{"username":"me","password":"pw","uris":[{"uri":"https://github.com"}]}},
          {"type":2,"name":"Memo","notes":"secret text"},
          {"type":3,"name":"Visa","card":{"cardholderName":"I V","number":"4111","expMonth":"12","expYear":"2030","code":"999"}},
          {"type":4,"name":"Identity"}]}"""
        val e = Importers.detect(json)
        assertEquals(3, e.size)
        assertEquals(listOf("Work"), e[0].tags)
        assertEquals("https://github.com", e[0].url)
        assertEquals(Kind.NOTE, e[1].kind)
        assertEquals("12/2030", e[2].expiry)
    }

    @Test fun importsChromeCsvWithQuotesCommasAndNewlines() {
        val csv = "name,url,username,password,note\r\n" +
            "\"Site, Inc\",https://a.example,u1,\"pa\"\"ss,1\",\"line1\nline2\"\r\n" +
            "b.example,https://b.example,u2,pw2,\r\n,,,,\r\n"
        val e = Importers.detect("\uFEFF" + csv)
        assertEquals(2, e.size)
        assertEquals("Site, Inc", e[0].title)
        assertEquals("pa\"ss,1", e[0].password)
        assertEquals("line1\nline2", e[0].notes)
        assertEquals("b.example", e[1].title)
    }

    @Test fun badImportsGiveClearErrors() {
        assertFailsWith<FormatException> { Importers.detect("{not json") }
        assertFailsWith<FormatException> { Importers.detect("a,b\n1,2") }
        assertFailsWith<FormatException> { Importers.detect("""{"encrypted":true,"items":[]}""") }
    }
}
