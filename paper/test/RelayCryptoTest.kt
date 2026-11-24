package mc.xalitoria.crates.paper

import mc.xalitoria.crates.paper.editor.RelayCrypto
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayCryptoTest {

    @Test
    fun deriveIsDeterministicAndMatchesCodeFormat() {
        val code = RelayCrypto.newCode()
        assertTrue(RelayCrypto.CODE_PATTERN.matches(code), "Invalid code: $code")
        val a = RelayCrypto.derive(code)
        val b = RelayCrypto.derive(code)
        assertEquals(22, a.sessionId.length)
        assertEquals(a.sessionId, b.sessionId)
        assertArrayEquals(a.key.encoded, b.key.encoded)

        val other = RelayCrypto.derive(RelayCrypto.newCode())
        assertNotEquals(a.sessionId, other.sessionId)
    }

    @Test
    fun sealAndOpenRoundTripAndRejectTampering() {
        val derived = RelayCrypto.derive("AbCdEf0123_-")
        val plain = """{"rid":"1","method":"GET","path":"/info"}""".toByteArray()
        val sealed = RelayCrypto.seal(derived.key, derived.sessionId, "q", plain)

        val opened = RelayCrypto.open(derived.key, derived.sessionId, "q", sealed)
        assertArrayEquals(plain, opened)

        // Wrong direction rejected (AAD mismatch)
        assertNull(RelayCrypto.open(derived.key, derived.sessionId, "a", sealed))
        // Wrong session rejected
        assertNull(RelayCrypto.open(derived.key, "other-session", "q", sealed))
        // Tampered ciphertext rejected
        val tampered = sealed.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0xFF).toByte() }
        assertNull(RelayCrypto.open(derived.key, derived.sessionId, "q", tampered))
    }
}
