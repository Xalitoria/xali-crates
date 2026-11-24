package mc.xalitoria.crates.paper.data.model

import com.xalitoria.util.kotlin.parseDuration

/** A validation problem reported for a crate or animation file. */
data class ConfigProblem(
    val path: String,
    val message: String,
    val severity: Severity = Severity.ERROR,
) {
    enum class Severity { WARNING, ERROR }

    override fun toString(): String = "$path: $message"
}

/**
 * Pure semantic validator for [CrateConfig] and [AnimationConfig].
 * Used on startup, `/xcrates validate`, `/xcrates reload`, and the web editor backend.
 */
object Validation {
    val ID_PATTERN = Regex("[a-z0-9_-]{1,64}")

    val BUILTIN_ANIMATIONS = setOf(
        "instant",
        "open-chest",
        "roulette",
        "csgo",
        "menu",
        "enclose",
        "discover",
        "discover-eula",
    )

    fun validateCrate(
        id: String,
        crate: CrateConfig,
        knownAnimations: Set<String> = emptySet(),
        knownCrates: Set<String> = emptySet(),
    ): List<ConfigProblem> = buildList {
        if (!ID_PATTERN.matches(id)) {
            add(ConfigProblem("id", "'$id' must be 1–64 lowercase letters, digits, '_' or '-'"))
        }
        validateItem("crate-item", crate.crateItem, this)
        if (!crate.isMultiCrate) {
            validateItem("key.item", crate.key.item, this)
        }
        if (crate.cost.amount < 0.0) {
            add(ConfigProblem("cost.amount", "must not be negative"))
        }
        checkDuration("cooldown.duration", crate.cooldown.duration, this)
        checkDuration("hologram.reward-duration", crate.hologram.rewardDuration, this)
        if (crate.opening.requiredSlots !in -1..36) {
            add(ConfigProblem("opening.required-slots", "must be between -1 and 36"))
        }
        if (crate.display.kind == CrateDisplayKind.ENTITY && crate.display.entity.isNullOrBlank()) {
            add(ConfigProblem("display.entity", "is required when display.kind is ENTITY"))
        }

        if (crate.isMultiCrate) {
            val children = crate.multiCrate?.crates.orEmpty()
            for ([i, child] in children.withIndex()) {
                if (child.equals(id, ignoreCase = true)) {
                    add(ConfigProblem("multi-crate.crates[$i]", "a multi-crate cannot reference itself"))
                } else if (knownCrates.isNotEmpty() && child.lowercase() !in knownCrates) {
                    add(ConfigProblem("multi-crate.crates[$i]", "references unknown crate '$child'", ConfigProblem.Severity.WARNING))
                }
            }
        } else {
            val anim = crate.opening.animation.lowercase()
            if (anim !in BUILTIN_ANIMATIONS && knownAnimations.isNotEmpty() && anim !in knownAnimations) {
                add(ConfigProblem("opening.animation", "unknown animation '${crate.opening.animation}'", ConfigProblem.Severity.WARNING))
            }
            if (crate.rewards.isEmpty()) {
                add(ConfigProblem("rewards", "crate has no rewards configured", ConfigProblem.Severity.WARNING))
            } else {
                var totalWeight = 0.0
                for ([rewardId, reward] in crate.rewards) {
                    val base = "rewards.$rewardId"
                    if (reward.weight < 0.0) {
                        add(ConfigProblem("$base.weight", "must not be negative"))
                    } else if (!reward.fallbackOnly && !reward.guaranteed) {
                        totalWeight += reward.weight
                    }
                    validateItem("$base.display-item", reward.displayItem, this)
                    reward.items.forEachIndexed { idx, item -> validateItem("$base.items[$idx]", item, this) }
                    for (linked in reward.linkedRewards) {
                        if (linked == rewardId) {
                            add(ConfigProblem("$base.linked-rewards", "cannot link to itself"))
                        } else if (linked !in crate.rewards) {
                            add(ConfigProblem("$base.linked-rewards", "references unknown reward '$linked'", ConfigProblem.Severity.WARNING))
                        }
                    }
                    for (contained in reward.shulkerContents) {
                        if (contained !in crate.rewards) {
                            add(ConfigProblem("$base.shulker-contents", "references unknown reward '$contained'", ConfigProblem.Severity.WARNING))
                        }
                    }
                    reward.fallback?.let { fb ->
                        if (fb.permission.isBlank()) add(ConfigProblem("$base.fallback.permission", "must not be blank"))
                        if (fb.reward == rewardId) add(ConfigProblem("$base.fallback.reward", "cannot fall back to itself"))
                        else if (fb.reward !in crate.rewards) {
                            add(ConfigProblem("$base.fallback.reward", "references unknown reward '${fb.reward}'"))
                        }
                    }
                    if (reward.repeat.chance !in 0.0..100.0) {
                        add(ConfigProblem("$base.repeat.chance", "must be between 0 and 100"))
                    }
                    if (reward.repeat.max < 0) {
                        add(ConfigProblem("$base.repeat.max", "must not be negative"))
                    }
                }
                if (totalWeight <= 0.0 && crate.rewards.values.none { it.guaranteed }) {
                    add(ConfigProblem("rewards", "total rollable reward weight must be greater than 0", ConfigProblem.Severity.WARNING))
                }
            }
        }

        if (crate.obtain == ObtainMethod.LUCKY_CHEST) {
            val lc = crate.luckyChest
            if (lc == null) {
                add(ConfigProblem("lucky-chest", "lucky-chest section is required when obtain is LUCKY_CHEST"))
            } else {
                if (lc.chance !in 0.0..100.0) add(ConfigProblem("lucky-chest.chance", "must be between 0 and 100"))
                lc.despawnAfter?.let { checkDuration("lucky-chest.despawn-after", it, this) }
            }
        }

        crate.ambient.particles.forEachIndexed { i, p -> validateParticle("ambient.particles[$i]", p, this) }
        crate.effects.particles.forEachIndexed { i, p -> validateParticle("effects.particles[$i]", p, this) }
        crate.effects.sounds.forEachIndexed { i, s -> validateSound("effects.sounds[$i]", s, this) }
        crate.tiers.forEach { [tierName, tier] ->
            tier.particles.forEachIndexed { i, p -> validateParticle("tiers.$tierName.particles[$i]", p, this) }
            tier.sounds.forEachIndexed { i, s -> validateSound("tiers.$tierName.sounds[$i]", s, this) }
        }
    }

    fun validateAnimation(id: String, anim: AnimationConfig): List<ConfigProblem> = buildList {
        if (!ID_PATTERN.matches(id)) {
            add(ConfigProblem("id", "'$id' must be 1–64 lowercase letters, digits, '_' or '-'"))
        }
        if (anim.inventorySize !in 9..54 || anim.inventorySize % 9 != 0) {
            add(ConfigProblem("inventory-size", "must be a multiple of 9 between 9 and 54"))
        }
        if (anim.defaultFrameTicks < 1) {
            add(ConfigProblem("default-frame-ticks", "must be at least 1"))
        }
        if (anim.autoCloseTicks < 0) {
            add(ConfigProblem("auto-close-ticks", "must not be negative"))
        }
        if (anim.stages.isEmpty()) {
            add(ConfigProblem("stages", "animation must define at least one stage"))
        }
        val expectedRows = anim.inventorySize / 9
        val knownSymbols = buildSet {
            add(anim.symbols.air)
            add(anim.symbols.randomReward)
            add(anim.symbols.randomFiller)
            add(anim.symbols.selectReward)
            add(anim.symbols.openSelectedReward)
            add(anim.symbols.revealReward)
            add(anim.symbols.selectedRevealReward)
            add(anim.symbols.nextStage)
            addAll(anim.symbols.rewardSymbols)
            addAll(anim.fillers.keys)
        }
        anim.stages.forEachIndexed { idx, stage ->
            val base = "stages[$idx]"
            if (stage.repeatStage != 0) {
                if (stage.repeatStage < 1 || stage.repeatStage > anim.stages.size) {
                    add(ConfigProblem("$base.repeat-stage", "references stage ${stage.repeatStage} outside 1..${anim.stages.size}"))
                }
                if (stage.repeatTimes < 1) {
                    add(ConfigProblem("$base.repeat-times", "must be at least 1 when repeat-stage is set"))
                }
            }
            if (stage.grid.isNotEmpty()) {
                if (stage.grid.size != expectedRows) {
                    add(ConfigProblem("$base.grid", "has ${stage.grid.size} rows but inventory-size ${anim.inventorySize} expects $expectedRows"))
                }
                stage.grid.forEachIndexed { r, row ->
                    val cells = row.split(',').map { it.trim() }
                    if (cells.size != 9) {
                        add(ConfigProblem("$base.grid[$r]", "must have 9 comma-separated cells, found ${cells.size}"))
                    }
                    cells.forEach { cell ->
                        if (cell.isNotEmpty() && cell !in knownSymbols) {
                            add(ConfigProblem("$base.grid[$r]", "unknown symbol '$cell'", ConfigProblem.Severity.WARNING))
                        }
                    }
                }
            }
            stage.rewardSlots.forEachIndexed { rIdx, coord ->
                val parts = coord.split(',').map { it.trim().toIntOrNull() }
                if (parts.size != 2 || parts[0] == null || parts[1] == null || parts[0]!! !in 1..expectedRows || parts[1]!! !in 1..9) {
                    add(ConfigProblem("$base.reward-slots[$rIdx]", "'$coord' must be 'row,col' within 1..$expectedRows, 1..9"))
                }
            }
        }
    }

    private fun validateItem(path: String, item: CrateItem, out: MutableList<ConfigProblem>) {
        if (item.material.isBlank()) out.add(ConfigProblem("$path.material", "must not be blank"))
        if (item.amount !in 1..99) out.add(ConfigProblem("$path.amount", "must be between 1 and 99"))
        if (item.damage < 0) out.add(ConfigProblem("$path.damage", "must not be negative"))
        if (item.color != null && !HEX_COLOR.matches(item.color)) {
            out.add(ConfigProblem("$path.color", "'${item.color}' must be a hex color like #RRGGBB", ConfigProblem.Severity.WARNING))
        }
    }

    private fun validateParticle(path: String, particle: ParticleConfig, out: MutableList<ConfigProblem>) {
        if (particle.type.isBlank()) out.add(ConfigProblem("$path.type", "must not be blank"))
        if (particle.amount !in 1..500) out.add(ConfigProblem("$path.amount", "must be between 1 and 500"))
        if (particle.color != null && !HEX_COLOR.matches(particle.color)) {
            out.add(ConfigProblem("$path.color", "'${particle.color}' must be #RRGGBB", ConfigProblem.Severity.WARNING))
        }
    }

    private fun validateSound(path: String, sound: SoundConfig, out: MutableList<ConfigProblem>) {
        if (sound.sound.isBlank()) out.add(ConfigProblem("$path.sound", "must not be blank"))
        if (sound.volume < 0f) out.add(ConfigProblem("$path.volume", "must not be negative"))
        if (sound.pitch !in 0f..2f) out.add(ConfigProblem("$path.pitch", "must be between 0.0 and 2.0", ConfigProblem.Severity.WARNING))
    }

    private fun checkDuration(path: String, text: String, out: MutableList<ConfigProblem>) {
        runCatching { parseDuration(path, text) }.onFailure {
            out.add(ConfigProblem(path, it.message ?: "invalid duration '$text'"))
        }
    }

    private val HEX_COLOR = Regex("#?[0-9A-Fa-f]{6}")
}
