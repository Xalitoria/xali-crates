package mc.xalitoria.crates.paper.editor

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for the web editor relay: AES-256-GCM with a 12-byte IV prepended to the ciphertext + tag.
 * Associated data is `xc1:<session>:<direction>` (`q` for request, `a` for answer), domain-separated from XaliMenu.
 * Matches `crates/editor/web/src/relay.js`.
 */
object RelayCrypto {
    private val random = SecureRandom()
    private const val IV = 12
    const val CODE_LENGTH = 12
    val CODE_PATTERN = Regex("[A-Za-z0-9_-]{$CODE_LENGTH}")
    private const val PBKDF2_ITERATIONS = 100_000
    private val PBKDF2_SALT = "xalicrates:session:v1".toByteArray(Charsets.UTF_8)

    data class Derived(val sessionId: String, val key: SecretKeySpec)

    fun newCode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(9).also(random::nextBytes))

    fun derive(code: String): Derived {
        val spec = PBEKeySpec(code.toCharArray(), PBKDF2_SALT, PBKDF2_ITERATIONS, 48 * 8)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.copyOfRange(0, 16))
        return Derived(sessionId, SecretKeySpec(raw.copyOfRange(16, 48), "AES"))
    }

    private fun cipher(mode: Int, key: SecretKeySpec, iv: ByteArray, session: String, direction: String) =
        Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(mode, key, GCMParameterSpec(128, iv))
            it.updateAAD("xc1:$session:$direction".toByteArray(Charsets.UTF_8))
        }

    fun seal(key: SecretKeySpec, session: String, direction: String, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV).also(random::nextBytes)
        return iv + cipher(Cipher.ENCRYPT_MODE, key, iv, session, direction).doFinal(plain)
    }

    fun open(key: SecretKeySpec, session: String, direction: String, bytes: ByteArray): ByteArray? {
        if (bytes.size < IV + 16) return null
        return try {
            cipher(Cipher.DECRYPT_MODE, key, bytes.copyOfRange(0, IV), session, direction).doFinal(bytes, IV, bytes.size - IV)
        } catch (_: Exception) {
            null
        }
    }
}
