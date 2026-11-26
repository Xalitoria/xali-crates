package mc.xalitoria.crates.paper

import mc.xalitoria.crates.paper.data.config.MainConfig.VerifyIp
import mc.xalitoria.crates.paper.data.model.CrateActionConfig
import mc.xalitoria.crates.paper.data.model.CrateActionType
import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.KeyClickCommands
import mc.xalitoria.crates.paper.data.model.KeyConfig
import mc.xalitoria.crates.paper.data.model.OpenEffectsConfig
import mc.xalitoria.crates.paper.data.model.RewardConfig
import mc.xalitoria.crates.paper.data.model.TierConfig
import mc.xalitoria.crates.paper.editor.EditorApi
import mc.xalitoria.crates.paper.editor.EditorGuard
import mc.xalitoria.crates.paper.editor.EditorRelay
import mc.xalitoria.crates.paper.editor.EditorSessions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorSecurityTest {

    @Test
    fun ipNormalizationAndLoopbackEquivalence() {
        assertEquals("1.2.3.4", EditorGuard.normalizeIp("::ffff:1.2.3.4"))
        assertEquals("127.0.0.1", EditorGuard.normalizeIp(" 127.0.0.1 "))
        assertNull(EditorGuard.normalizeIp("evil.example.com"))
        assertTrue(EditorGuard.isLoopback("::1"))
        assertTrue(EditorGuard.isLoopback("127.0.0.1"))
        assertTrue(EditorGuard.ipsMatch("127.0.0.1", "::1"))
        assertTrue(EditorGuard.ipsMatch("::ffff:10.0.0.5", "10.0.0.5"))
        assertFalse(EditorGuard.ipsMatch("10.0.0.5", "10.0.0.6"))
    }

    @Test
    fun resolveClientIpHonorsForwardingOnlyFromTrustedLoopback() {
        assertEquals("198.51.100.2", EditorGuard.resolveClientIp("127.0.0.1", "198.51.100.2", "203.0.113.9", trustProxy = true))
        assertEquals("203.0.113.9", EditorGuard.resolveClientIp("::1", null, "203.0.113.9, 10.0.0.1", trustProxy = true))
        assertEquals("127.0.0.1", EditorGuard.resolveClientIp("127.0.0.1", "198.51.100.2", null, trustProxy = false))
        assertEquals("192.0.2.5", EditorGuard.resolveClientIp("192.0.2.5", "198.51.100.2", "203.0.113.9", trustProxy = true))
    }

    @Test
    fun checkSaveEnforcesReadOnlySharedRevokedAndIpPolicies() {
        assertEquals(
            EditorGuard.SaveDecision.ReadOnly,
            EditorGuard.checkSave(VerifyIp.OFF, shared = false, readOnly = true, ownerIp = "1.2.3.4", ownerStillAllowed = true, clientIp = "1.2.3.4", onlineAdminIps = []),
        )
        assertTrue(
            EditorGuard.checkSave(VerifyIp.OFF, shared = true, readOnly = false, ownerIp = "1.2.3.4", ownerStillAllowed = true, clientIp = "1.2.3.4", onlineAdminIps = []) is EditorGuard.SaveDecision.Hold,
        )
        assertEquals(
            EditorGuard.SaveDecision.PermissionRevoked,
            EditorGuard.checkSave(VerifyIp.OFF, shared = false, readOnly = false, ownerIp = "1.2.3.4", ownerStillAllowed = false, clientIp = "1.2.3.4", onlineAdminIps = []),
        )
        assertEquals(
            EditorGuard.SaveDecision.Allow,
            EditorGuard.checkSave(VerifyIp.ADMIN, shared = false, readOnly = false, ownerIp = null, ownerStillAllowed = true, clientIp = "5.6.7.8", onlineAdminIps = ["5.6.7.8"]),
        )
        assertTrue(
            EditorGuard.checkSave(VerifyIp.ADMIN, shared = false, readOnly = false, ownerIp = "1.2.3.4", ownerStillAllowed = true, clientIp = "9.9.9.9", onlineAdminIps = ["5.6.7.8"]) is EditorGuard.SaveDecision.Hold,
        )
        assertTrue(
            EditorGuard.checkSave(VerifyIp.OWNER, shared = false, readOnly = false, ownerIp = null, ownerStillAllowed = true, clientIp = "5.6.7.8", onlineAdminIps = ["5.6.7.8"]) is EditorGuard.SaveDecision.Hold,
        )
        assertEquals(
            EditorGuard.SaveDecision.Allow,
            EditorGuard.checkSave(VerifyIp.OWNER, shared = false, readOnly = false, ownerIp = "1.2.3.4", ownerStillAllowed = true, clientIp = "::ffff:1.2.3.4", onlineAdminIps = []),
        )
    }

    @Test
    fun privilegedCommandsFindsEveryConsoleCommandSource() {
        val crate = CrateConfig(
            key = KeyConfig(commands = KeyClickCommands(leftClick = ["say left"], rightClick = ["lp user %player% promote"])),
            effects = OpenEffectsConfig(actions = [CrateActionConfig(CrateActionType.COMMAND, "eco give %player% 100")]),
            tiers = mapOf("mythic" to TierConfig(actions = [CrateActionConfig(CrateActionType.COMMAND, "bc mythic")])),
            rewards = mapOf(
                "vip" to RewardConfig(
                    commands = ["lp user %player% parent add vip"],
                    playerCommands = ["spawn"],
                ),
            ),
        )
        val found = EditorGuard.privilegedCommands(crate)
        assertEquals(5, found.size)
        assertTrue(found.any { it.contains("lp user %player% parent add vip") })
        assertFalse(found.any { it.contains("spawn") })
        assertTrue(found.any { it.contains("eco give %player% 100") })
        assertTrue(found.any { it.contains("bc mythic") })
        assertTrue(found.any { it.contains("lp user %player% promote") })
    }

    @Test
    fun byteRangeAndSkinAddressAndCallIdRejectAbuse() {
        assertEquals(0L..1023L, EditorApi.byteRange("bytes=0-1023", 5000))
        assertEquals(100L..4999L, EditorApi.byteRange("bytes=100-", 5000))
        assertNull(EditorApi.byteRange("bytes=5000-5010", 5000))
        assertNull(EditorApi.byteRange("bytes=200-100", 5000))
        assertEquals(0L..(EditorApi.MAX_PART - 1L), EditorApi.byteRange("bytes=0-99999999", 50_000_000))

        assertNotNull(EditorApi.skinAddress("https://textures.minecraft.net/texture/0123456789abcdef0123456789abcdef"))
        assertNull(EditorApi.skinAddress("https://169.254.169.254/latest/meta-data/"))
        assertNull(EditorApi.skinAddress("https://textures.minecraft.net/texture/0123456789abcdef?foo=bar"))
        assertNull(EditorApi.skinAddress("https://textures.minecraft.net:8443/texture/0123456789abcdef"))

        assertTrue(EditorRelay.CALL_ID.matches("abc123_-"))
        assertFalse(EditorRelay.CALL_ID.matches("../../etc/passwd"))
        assertFalse(EditorRelay.CALL_ID.matches(""))
    }

    @Test
    fun editorSessionsCapsMaximumActiveTokens() {
        var now = 1_000L
        val sessions = EditorSessions(minutes = { 60 }, now = { now })
        val tokens = (1..(EditorSessions.MAX_SESSIONS + 5)).map {
            now += 10
            sessions.create(owner = "admin$it")
        }
        // Oldest 5 tokens were evicted to stay within MAX_SESSIONS
        assertNull(sessions.session(tokens.first()))
        assertNotNull(sessions.session(tokens.last()))
    }
}
