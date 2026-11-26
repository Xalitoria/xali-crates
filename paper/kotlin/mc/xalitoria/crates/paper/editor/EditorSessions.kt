package mc.xalitoria.crates.paper.editor

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Token store for direct HTTP web editor sessions (`/xcrates web` and `/xcrates share` when `editor.enabled` is true).
 * Tokens are stored only as SHA-256 hashes and capped at [MAX_SESSIONS].
 */
internal class EditorSessions(private val minutes: () -> Int, private val now: () -> Long = System::currentTimeMillis) {
    data class Session(
        val owner: String,
        var expires: Long,
        val shared: Boolean = false,
        val readOnly: Boolean = false,
        val autoReload: Boolean = false,
        val initialCrate: String? = null,
        val ownerIp: String? = null,
        val ownerUuid: UUID? = null,
    )

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    fun create(
        owner: String,
        shared: Boolean = false,
        readOnly: Boolean = false,
        autoReload: Boolean = false,
        initialCrate: String? = null,
        ownerIp: String? = null,
        ownerUuid: UUID? = null,
    ): String {
        purge()
        while (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.entries.minByOrNull { it.value.expires }?.key ?: break
            sessions.remove(oldest)
        }
        val bytes = ByteArray(24).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        sessions[hash(token)] = Session(
            owner = owner,
            expires = now() + minutes().coerceAtLeast(1) * 60_000L,
            shared = shared,
            readOnly = readOnly,
            autoReload = autoReload,
            initialCrate = initialCrate,
            ownerIp = EditorGuard.normalizeIp(ownerIp),
            ownerUuid = ownerUuid,
        )
        return token
    }

    fun session(token: String?): Session? {
        if (token.isNullOrEmpty()) return null
        val key = hash(token)
        val session = sessions[key] ?: return null
        if (session.expires < now()) {
            sessions.remove(key)
            return null
        }
        return session
    }

    fun clearMatching(owner: String? = null, shared: Boolean? = null): Int {
        var removed = 0
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val s = it.next().value
            if ((shared == null || s.shared == shared) && (owner == null || s.owner == owner)) {
                it.remove()
                removed++
            }
        }
        return removed
    }

    fun clear() = sessions.clear()

    private fun purge() {
        val time = now()
        sessions.values.removeIf { it.expires < time }
    }

    private fun hash(token: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    companion object {
        const val MAX_SESSIONS = 64
    }
}
