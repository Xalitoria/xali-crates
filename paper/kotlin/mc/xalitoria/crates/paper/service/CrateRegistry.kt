package mc.xalitoria.crates.paper.service

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import mc.xalitoria.crates.paper.data.model.AnimationConfig
import mc.xalitoria.crates.paper.data.model.AnimationItem
import mc.xalitoria.crates.paper.data.model.AnimationSounds
import mc.xalitoria.crates.paper.data.model.AnimationStage
import mc.xalitoria.crates.paper.data.model.ConfigProblem
import mc.xalitoria.crates.paper.data.model.CrateActionConfig
import mc.xalitoria.crates.paper.data.model.CrateActionType
import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.CrateItem
import mc.xalitoria.crates.paper.data.model.FireworkConfig
import mc.xalitoria.crates.paper.data.model.GiveItemConfig
import mc.xalitoria.crates.paper.data.model.HologramConfig
import mc.xalitoria.crates.paper.data.model.KeyConfig
import mc.xalitoria.crates.paper.data.model.MultiCrateConfig
import mc.xalitoria.crates.paper.data.model.ObtainMethod
import mc.xalitoria.crates.paper.data.model.OpenEffectsConfig
import mc.xalitoria.crates.paper.data.model.OpeningConfig
import mc.xalitoria.crates.paper.data.model.ParticleAnimation
import mc.xalitoria.crates.paper.data.model.ParticleConfig
import mc.xalitoria.crates.paper.data.model.RewardConfig
import mc.xalitoria.crates.paper.data.model.SoundConfig
import mc.xalitoria.crates.paper.data.model.TierConfig
import mc.xalitoria.crates.paper.data.model.Validation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Result of loading all crates and custom animations from disk. */
data class LoadedCatalog(
    val crates: Map<String, CrateConfig>,
    val animations: Map<String, AnimationConfig>,
    val problems: Map<String, List<ConfigProblem>>,
)

/**
 * Reads, validates, and atomically writes `crates/<id>.yml` and `animations/<id>.yml` using kaml.
 */
object CrateRegistry {
    val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false,
            encodeDefaults = true,
            breakScalarsAt = 120,
        ),
    )

    fun writeDefaultsIfEmpty(dataDir: Path) {
        val cratesDir = dataDir.resolve("crates")
        val animsDir = dataDir.resolve("animations")
        if (!cratesDir.exists()) {
            cratesDir.createDirectories()
            saveCrate(cratesDir, "starter", starterCrate())
            saveCrate(cratesDir, "all-crates", starterMultiCrate())
        }
        if (!animsDir.exists()) {
            animsDir.createDirectories()
            saveAnimation(animsDir, "starburst", starterAnimation())
        }
    }

    fun loadAll(dataDir: Path): LoadedCatalog {
        val cratesDir = dataDir.resolve("crates").also { it.createDirectories() }
        val animsDir = dataDir.resolve("animations").also { it.createDirectories() }

        val animations = linkedMapOf<String, AnimationConfig>()
        val problems = linkedMapOf<String, MutableList<ConfigProblem>>()

        listYamlFiles(animsDir).forEach { file ->
            val id = file.nameWithoutExtension.lowercase()
            try {
                val anim = yaml.decodeFromString(AnimationConfig.serializer(), file.readText())
                val issues = Validation.validateAnimation(id, anim)
                if (issues.isNotEmpty()) problems.getOrPut("animations/$id") { mutableListOf() }.addAll(issues)
                if (issues.none { it.severity == ConfigProblem.Severity.ERROR }) {
                    animations[id] = anim
                }
            } catch (e: Exception) {
                problems.getOrPut("animations/$id") { mutableListOf() }
                    .add(ConfigProblem("file", e.message ?: e::class.simpleName.orEmpty()))
            }
        }

        val rawCrates = linkedMapOf<String, CrateConfig>()
        listYamlFiles(cratesDir).forEach { file ->
            val id = file.nameWithoutExtension.lowercase()
            try {
                rawCrates[id] = yaml.decodeFromString(CrateConfig.serializer(), file.readText())
            } catch (e: Exception) {
                problems.getOrPut("crates/$id") { mutableListOf() }
                    .add(ConfigProblem("file", e.message ?: e::class.simpleName.orEmpty()))
            }
        }

        val crates = linkedMapOf<String, CrateConfig>()
        val allIds = rawCrates.keys
        for ([id, crate] in rawCrates) {
            val issues = Validation.validateCrate(id, crate, animations.keys, allIds)
            if (issues.isNotEmpty()) problems.getOrPut("crates/$id") { mutableListOf() }.addAll(issues)
            if (issues.none { it.severity == ConfigProblem.Severity.ERROR }) {
                crates[id] = crate
            }
        }

        return LoadedCatalog(crates, animations, problems)
    }

    fun saveCrate(cratesDir: Path, id: String, crate: CrateConfig) {
        val cleanId = id.lowercase()
        require(Validation.ID_PATTERN.matches(cleanId)) { "Invalid crate id '$id'" }
        cratesDir.createDirectories()
        val target = cratesDir.resolve("$cleanId.yml")
        val text = yaml.encodeToString(CrateConfig.serializer(), crate) + "\n"
        atomicWrite(target, text)
    }

    fun saveAnimation(animsDir: Path, id: String, anim: AnimationConfig) {
        val cleanId = id.lowercase()
        require(Validation.ID_PATTERN.matches(cleanId)) { "Invalid animation id '$id'" }
        animsDir.createDirectories()
        val target = animsDir.resolve("$cleanId.yml")
        val text = yaml.encodeToString(AnimationConfig.serializer(), anim) + "\n"
        atomicWrite(target, text)
    }

    fun deleteCrateFile(cratesDir: Path, id: String): Boolean =
        Files.deleteIfExists(cratesDir.resolve("${id.lowercase()}.yml"))

    fun deleteAnimationFile(animsDir: Path, id: String): Boolean =
        Files.deleteIfExists(animsDir.resolve("${id.lowercase()}.yml"))

    private fun atomicWrite(target: Path, content: String) {
        if (target.exists()) {
            Files.copy(target, target.resolveSibling("${target.fileName}.bak"), StandardCopyOption.REPLACE_EXISTING)
        }
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        tmp.writeText(content)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun listYamlFiles(dir: Path): List<Path> =
        Files.list(dir).use { stream ->
            stream.filter { it.isRegularFile() && it.extension.lowercase() in setOf("yml", "yaml") }
                .sorted()
                .toList()
        }

    fun starterCrate(): CrateConfig = CrateConfig(
        enabled = true,
        displayName = "&dStarter Crate",
        obtain = ObtainMethod.STATIC,
        crateItem = CrateItem(
            material = "chest",
            name = "&d&lStarter Crate",
            lore = ["&7Right-click to open", "&7Left-click to preview rewards"],
            glow = true,
        ),
        key = KeyConfig(
            required = true,
            item = CrateItem(
                material = "tripwire_hook",
                name = "&d&lStarter Crate Key",
                lore = ["&7Use on a &dStarter Crate&7."],
                glow = true,
            ),
        ),
        opening = OpeningConfig(animation = "csgo", inventoryTitle = "&8&l> &dStarter Crate"),
        hologram = HologramConfig(
            enabled = true,
            lines = ["&d&lStarter Crate", "&7Right-click with a key to open", "&7Left-click to preview"],
            rewardLine = "&a+ &f%reward% &a+",
        ),
        ambient = mc.xalitoria.crates.paper.data.model.AmbientEffectsConfig(
            particles = [
                ParticleConfig(type = "enchant", animation = ParticleAnimation.SPIRAL, amount = 2, rangeX = 0.8, rangeY = 1.2, rangeZ = 0.8),
            ],
        ),
        effects = OpenEffectsConfig(
            actions = [
                CrateActionConfig(CrateActionType.MESSAGE, "&dCrates &7> &fYou won %reward%&f!"),
                CrateActionConfig(CrateActionType.ACTIONBAR, "&aWon: &f%reward%"),
            ],
            sounds = [SoundConfig("entity.player.levelup", 1.0f, 1.2f)],
            particles = [ParticleConfig(type = "happy_villager", amount = 15, rangeX = 1.0, rangeY = 1.0, rangeZ = 1.0, speed = 0.2)],
        ),
        tiers = mapOf(
            "rare" to TierConfig(
                displayName = "&bRare",
                overrideDefaults = true,
                actions = [
                    CrateActionConfig(CrateActionType.MESSAGE, "&dCrates &7> &bYou won a Rare reward: %reward%&b!"),
                    CrateActionConfig(CrateActionType.BROADCAST, "&dCrates &7> &f%player% &7won a &bRare &7reward: %reward%&7!"),
                ],
                sounds = [SoundConfig("ui.toast.challenge_complete", 1.0f, 1.0f)],
                fireworks = [FireworkConfig(colors = ["AQUA", "WHITE"], shape = "STAR", trail = true)],
            ),
        ),
        rewards = linkedMapOf(
            "diamonds" to RewardConfig(
                weight = 30.0,
                rarity = "default",
                displayItem = CrateItem(
                    material = "diamond",
                    amount = 3,
                    name = "&b3x Diamonds",
                    lore = ["&7Chance: &f%chance%%"],
                    glow = true,
                ),
                giveItem = GiveItemConfig(enabled = true, keepName = false, keepLore = false),
            ),
            "golden_apples" to RewardConfig(
                weight = 45.0,
                rarity = "default",
                displayItem = CrateItem(
                    material = "golden_apple",
                    amount = 4,
                    name = "&e4x Golden Apples",
                    lore = ["&7Chance: &f%chance%%"],
                ),
                giveItem = GiveItemConfig(enabled = true, keepName = false, keepLore = false),
            ),
            "hero_blade" to RewardConfig(
                weight = 10.0,
                rarity = "rare",
                displayItem = CrateItem(
                    material = "diamond_sword",
                    name = "&b&lHero Blade",
                    lore = ["&7A rare crate blade", "&7Chance: &b%chance%%"],
                    enchantments = mapOf("sharpness" to 5, "unbreaking" to 3),
                ),
                giveItem = GiveItemConfig(enabled = true, keepName = true, keepLore = true),
            ),
        ),
    )

    fun starterMultiCrate(): CrateConfig = CrateConfig(
        enabled = true,
        displayName = "&5&lAll Crates",
        obtain = ObtainMethod.STATIC,
        crateItem = CrateItem(
            material = "ender_chest",
            name = "&5&lAll Crates",
            lore = ["&7Open to browse all crates"],
            glow = true,
        ),
        hologram = HologramConfig(
            enabled = true,
            lines = ["&5&lCrate Hub", "&7Click to browse crates"],
        ),
        multiCrate = MultiCrateConfig(enabled = true, crates = ["starter"]),
    )

    fun starterAnimation(): AnimationConfig = AnimationConfig(
        inventorySize = 27,
        defaultFrameTicks = 4,
        autoCloseTicks = 40,
        fillers = mapOf(
            "F" to AnimationItem(material = "gray_stained_glass_pane", name = " "),
            "P" to AnimationItem(material = "magenta_stained_glass_pane", name = " ", glow = true),
        ),
        stages = [
            AnimationStage(
                grid = [
                    "F,F,F,P,P,P,F,F,F",
                    "F,F,RF,RF,R,RF,RF,F,F",
                    "F,F,F,P,P,P,F,F,F",
                ],
                stayTicks = 3,
            ),
            AnimationStage(repeatStage = 1, repeatTimes = 12, stayTicks = 3),
            AnimationStage(
                grid = [
                    "P,P,P,P,P,P,P,P,P",
                    "P,A,A,A,R1,A,A,A,P",
                    "P,P,P,P,P,P,P,P,P",
                ],
                rewardSlots = ["2,5"],
                sound = SoundConfig("entity.player.levelup", 0.8f, 1.2f),
            ),
        ],
        sounds = AnimationSounds(
            stage = SoundConfig("ui.button.click", 0.5f, 1.2f),
            end = SoundConfig("entity.player.levelup", 1.0f, 1.0f),
        ),
    )
}
