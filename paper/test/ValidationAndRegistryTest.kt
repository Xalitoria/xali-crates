package mc.xalitoria.crates.paper

import mc.xalitoria.crates.paper.data.model.AnimationConfig
import mc.xalitoria.crates.paper.data.model.AnimationStage
import mc.xalitoria.crates.paper.data.model.ConfigProblem
import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.CrateDisplayConfig
import mc.xalitoria.crates.paper.data.model.CrateDisplayKind
import mc.xalitoria.crates.paper.data.model.CrateItem
import mc.xalitoria.crates.paper.data.model.FallbackRewardConfig
import mc.xalitoria.crates.paper.data.model.MultiCrateConfig
import mc.xalitoria.crates.paper.data.model.OpeningConfig
import mc.xalitoria.crates.paper.data.model.RewardConfig
import mc.xalitoria.crates.paper.data.model.Validation
import mc.xalitoria.crates.paper.service.CrateRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ValidationAndRegistryTest {

    @Test
    fun starterDefaultsRoundTripCleanly(@TempDir dir: Path) {
        CrateRegistry.writeDefaultsIfEmpty(dir)
        val loaded = CrateRegistry.loadAll(dir)

        assertTrue("starter" in loaded.crates, "starter crate missing")
        assertTrue("all-crates" in loaded.crates, "all-crates multi-crate missing")
        assertTrue("starburst" in loaded.animations, "starburst animation missing")
        assertTrue(
            loaded.problems.values.flatten().none { it.severity == ConfigProblem.Severity.ERROR },
            "Unexpected validation errors: ${loaded.problems}",
        )
    }

    @Test
    fun crateValidationCatchesErrorsAndWarnings() {
        val bad = CrateConfig(
            display = CrateDisplayConfig(kind = CrateDisplayKind.ENTITY, entity = null),
            opening = OpeningConfig(animation = "nonexistent_anim", requiredSlots = 50),
            rewards = mapOf(
                "neg" to RewardConfig(
                    weight = -5.0,
                    displayItem = CrateItem("diamond"),
                    fallback = FallbackRewardConfig(permission = "vip.perk", reward = "missing_fallback"),
                ),
            ),
        )
        val problems = Validation.validateCrate("test", bad, knownAnimations = setOf("starburst"), knownCrates = setOf("test"))
        val paths = problems.map { it.path }.toSet()
        assertTrue("opening.required-slots" in paths)
        assertTrue("display.entity" in paths)
        assertTrue("opening.animation" in paths)
        assertTrue("rewards.neg.weight" in paths)
        assertTrue("rewards.neg.fallback.reward" in paths)
    }

    @Test
    fun multiCrateCannotReferenceItself() {
        val selfRef = CrateConfig(multiCrate = MultiCrateConfig(enabled = true, crates = ["hub", "starter"]))
        val problems = Validation.validateCrate("hub", selfRef, knownAnimations = emptySet(), knownCrates = setOf("hub", "starter"))
        assertTrue(problems.any { it.path == "multi-crate.crates[0]" && it.severity == ConfigProblem.Severity.ERROR })
    }

    @Test
    fun animationValidationChecksGridDimensions() {
        val bad = AnimationConfig(
            stages = listOf(
                AnimationStage(
                    stayTicks = 2,
                    grid = listOf("A,A,A"), // must be 9 columns
                ),
            ),
        )
        val problems = Validation.validateAnimation("broken", bad)
        assertTrue(problems.any { it.path.startsWith("stages[0].grid") })
        assertEquals(ConfigProblem.Severity.ERROR, problems.first().severity)
    }
}
