package mc.xalitoria.crates.paper

import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.CrateItem
import mc.xalitoria.crates.paper.data.model.FallbackRewardConfig
import mc.xalitoria.crates.paper.data.model.RepeatConfig
import mc.xalitoria.crates.paper.data.model.RewardConfig
import mc.xalitoria.crates.paper.service.NamedReward
import mc.xalitoria.crates.paper.service.RewardRoller
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RewardRollerTest {

    @Test
    fun probabilityPercentagesIgnoreFallbackAndGuaranteed() {
        val crate = CrateConfig(
            rewards = mapOf(
                "common" to RewardConfig(weight = 75.0, displayItem = CrateItem("coal")),
                "rare" to RewardConfig(weight = 25.0, displayItem = CrateItem("diamond")),
                "bonus" to RewardConfig(weight = 100.0, guaranteed = true, displayItem = CrateItem("apple")),
                "fallback" to RewardConfig(weight = 50.0, fallbackOnly = true, displayItem = CrateItem("gold_ingot")),
            ),
        )
        assertEquals(75.0, RewardRoller.probabilityPercent(crate, "common"), 1e-6)
        assertEquals(25.0, RewardRoller.probabilityPercent(crate, "rare"), 1e-6)
        assertEquals(100.0, RewardRoller.probabilityPercent(crate, "bonus"), 1e-6)
        assertEquals(0.0, RewardRoller.probabilityPercent(crate, "fallback"), 1e-6)
    }

    @Test
    fun fallbackResolvesAndGuardsCycles() {
        val crate = CrateConfig(
            rewards = mapOf(
                "perm_reward" to RewardConfig(
                    weight = 100.0,
                    displayItem = CrateItem("emerald"),
                    fallback = FallbackRewardConfig(permission = "vip.perk", reward = "alt_reward", bypassPermission = ""),
                ),
                "alt_reward" to RewardConfig(
                    weight = 1.0,
                    fallbackOnly = true,
                    displayItem = CrateItem("gold_ingot"),
                ),
                "loop_a" to RewardConfig(
                    weight = 10.0,
                    displayItem = CrateItem("stone"),
                    fallback = FallbackRewardConfig(permission = "loop.perm", reward = "loop_b", bypassPermission = ""),
                ),
                "loop_b" to RewardConfig(
                    weight = 10.0,
                    displayItem = CrateItem("dirt"),
                    fallback = FallbackRewardConfig(permission = "loop.perm", reward = "loop_a", bypassPermission = ""),
                ),
            ),
        )

        val won = NamedReward("perm_reward", crate.rewards.getValue("perm_reward"))
        val [resolved, replaced] = RewardRoller.resolveFallback(crate, won) { it == "vip.perk" }
        assertTrue(replaced)
        assertEquals("alt_reward", resolved.id)

        val [unchanged, wasReplaced] = RewardRoller.resolveFallback(crate, won) { false }
        assertFalse(wasReplaced)
        assertEquals("perm_reward", unchanged.id)

        // Must terminate safely even on a cyclic fallback graph
        val loopStart = NamedReward("loop_a", crate.rewards.getValue("loop_a"))
        val [cycle, _] = RewardRoller.resolveFallback(crate, loopStart) { it == "loop.perm" }
        assertTrue(cycle.id == "loop_a" || cycle.id == "loop_b")
    }

    @Test
    fun rollRespectsRepeatPreventAndGuaranteedList() {
        val crate = CrateConfig(
            rewards = mapOf(
                "r1" to RewardConfig(weight = 1000.0, displayItem = CrateItem("diamond"), repeat = RepeatConfig(chance = 0.0, max = 0)),
                "r2" to RewardConfig(weight = 1.0, displayItem = CrateItem("emerald"), repeat = RepeatConfig(chance = 100.0, max = 3)),
                "always" to RewardConfig(weight = 1.0, guaranteed = true, displayItem = CrateItem("apple")),
            ),
        )

        val first = RewardRoller.roll(crate, previous = [], random = Random(42))
        assertNotNull(first)
        // With r1 already won and repeat.max=0, the next roll must pick r2
        val second = RewardRoller.roll(crate, previous = [NamedReward("r1", crate.rewards.getValue("r1"))], random = Random(42))
        assertEquals("r2", second?.id)
        assertEquals(listOf("always"), RewardRoller.guaranteed(crate).map { it.id })
    }

    @Test
    fun amountPlaceholderSubstitutesRanges() {
        val out = RewardRoller.expandRandomAmounts("give %player% diamond %amount3-7% and %amount10-10%", Random(7))
        val match = Regex("""give %player% diamond (\d+) and (\d+)""").matchEntire(out)
        assertTrue(match != null, "Unexpected output: $out")
        val first = match!!.groupValues[1].toInt()
        val second = match.groupValues[2].toInt()
        assertTrue(first in 3..7)
        assertEquals(10, second)
    }
}
