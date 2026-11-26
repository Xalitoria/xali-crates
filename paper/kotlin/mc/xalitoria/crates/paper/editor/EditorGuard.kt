package mc.xalitoria.crates.paper.editor

import mc.xalitoria.crates.paper.data.config.MainConfig.VerifyIp
import mc.xalitoria.crates.paper.data.model.CrateActionType
import mc.xalitoria.crates.paper.data.model.CrateConfig
import java.net.InetAddress

/**
 * Pure security checks for the XaliCrates web editor: IP normalization/matching, save authorization policy,
 * and scanning crate edits for privileged console commands (`commands`, `COMMAND` actions, key click commands).
 */
internal object EditorGuard {
    private val NUMERIC_IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")
    private val NUMERIC_IPV6 = Regex("[0-9a-fA-F:]+(?:\\.\\d{1,3}){3}|[0-9a-fA-F:]+")

    sealed interface SaveDecision {
        data object Allow : SaveDecision
        data object ReadOnly : SaveDecision
        data object PermissionRevoked : SaveDecision
        data class Hold(val reason: String, val code: String) : SaveDecision
    }

    /** Parses [raw] only when it is a numeric IPv4/IPv6 literal (never triggers DNS resolution). */
    fun parseIp(raw: String?): InetAddress? {
        val cleaned = raw?.trim()
            ?.removePrefix("[")?.removeSuffix("]")
            ?.substringBefore('%')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        if (!NUMERIC_IPV4.matches(cleaned) && !(cleaned.contains(':') && NUMERIC_IPV6.matches(cleaned))) return null
        return runCatching { InetAddress.getByName(cleaned) }.getOrNull()
    }

    /** Canonical string form of a numeric IP literal (e.g. `::ffff:1.2.3.4` -> `1.2.3.4`), or null if invalid. */
    fun normalizeIp(raw: String?): String? = parseIp(raw)?.hostAddress

    /** Whether [raw] is a loopback address (`127.0.0.1`, `::1`, `::ffff:127.0.0.1`, etc.). */
    fun isLoopback(raw: String?): Boolean = parseIp(raw)?.isLoopbackAddress == true

    /** Whether [a] and [b] refer to the same host (including IPv4-mapped IPv6 and loopback equivalence). */
    fun ipsMatch(a: String?, b: String?): Boolean {
        val left = parseIp(a) ?: return false
        val right = parseIp(b) ?: return false
        if (left.isLoopbackAddress && right.isLoopbackAddress) return true
        return left == right
    }

    /**
     * Determines the caller's IP for a direct HTTP request. Forwarding headers (`CF-Connecting-IP`, `X-Forwarded-For`)
     * are honored only when [trustProxy] is enabled AND the direct socket [remoteIp] is loopback.
     */
    fun resolveClientIp(remoteIp: String?, cfConnectingIp: String?, forwardedFor: String?, trustProxy: Boolean): String? {
        if (trustProxy && isLoopback(remoteIp)) {
            normalizeIp(cfConnectingIp)?.let { return it }
            forwardedFor?.split(',')?.firstOrNull()?.let(::normalizeIp)?.let { return it }
        }
        return normalizeIp(remoteIp)
    }

    /**
     * Decides whether a save request may write directly to disk, must be held in [PendingEdits], or must be refused.
     */
    fun checkSave(
        policy: VerifyIp,
        shared: Boolean,
        readOnly: Boolean,
        ownerIp: String?,
        ownerStillAllowed: Boolean,
        clientIp: String?,
        onlineAdminIps: Collection<String>,
    ): SaveDecision {
        if (readOnly) return SaveDecision.ReadOnly
        if (shared) return SaveDecision.Hold("shared link", "shared")
        if (!ownerStillAllowed) return SaveDecision.PermissionRevoked
        return when (policy) {
            VerifyIp.OFF -> SaveDecision.Allow
            VerifyIp.OWNER -> when {
                ownerIp == null -> SaveDecision.Hold("console session (no player IP)", "ip-mismatch")
                clientIp == null -> SaveDecision.Hold("client IP unavailable", "ip-mismatch")
                ipsMatch(ownerIp, clientIp) -> SaveDecision.Allow
                else -> SaveDecision.Hold("IP mismatch ($clientIp)", "ip-mismatch")
            }
            VerifyIp.ADMIN -> when {
                clientIp == null -> SaveDecision.Hold("client IP unavailable", "ip-mismatch")
                ownerIp != null && ipsMatch(ownerIp, clientIp) -> SaveDecision.Allow
                onlineAdminIps.any { ipsMatch(it, clientIp) } -> SaveDecision.Allow
                else -> SaveDecision.Hold("IP mismatch ($clientIp)", "ip-mismatch")
            }
        }
    }

    /**
     * Summarizes every console command embedded in [crate] so admins see privileged operations before approving a
     * pending edit (`reward.commands`, `COMMAND` open/reward/tier actions, and `key.commands`).
     */
    fun privilegedCommands(crate: CrateConfig, limit: Int = 25): List<String> {
        val found = ArrayList<String>()
        fun record(label: String, command: String) {
            if (found.size >= limit) return
            val cleaned = cleanValue(command)
            if (cleaned.isNotEmpty()) found.add("$label: $cleaned")
        }

        for (action in crate.effects.actions) {
            if (action.type == CrateActionType.COMMAND) record("open-effect", action.value)
        }
        val clicks = crate.key.commands
        for (cmd in clicks.leftClick) record("key-left-click", cmd)
        for (cmd in clicks.rightClick) record("key-right-click", cmd)
        for (cmd in clicks.shiftLeftClick) record("key-shift-left-click", cmd)
        for (cmd in clicks.shiftRightClick) record("key-shift-right-click", cmd)
        for ([tierId, tier] in crate.tiers) {
            for (action in tier.actions) {
                if (action.type == CrateActionType.COMMAND) record("tier/$tierId", action.value)
            }
        }
        for ([rewardId, reward] in crate.rewards) {
            for (cmd in reward.commands) record("reward/$rewardId", cmd)
        }
        return found
    }

    private fun cleanValue(text: String): String =
        text.filter { !it.isISOControl() && it != '§' }.trim().take(80)
}
