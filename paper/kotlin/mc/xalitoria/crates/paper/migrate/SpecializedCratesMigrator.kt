package mc.xalitoria.crates.paper.migrate

import mc.xalitoria.crates.paper.data.db.repository.CooldownRepository
import mc.xalitoria.crates.paper.data.db.repository.HistoryRepository
import mc.xalitoria.crates.paper.data.db.repository.PlacedCrateRepository
import mc.xalitoria.crates.paper.data.db.repository.VirtualBalanceRepository
import mc.xalitoria.crates.paper.data.model.AmbientEffectsConfig
import mc.xalitoria.crates.paper.data.model.AnimationConfig
import mc.xalitoria.crates.paper.data.model.AnimationEndEffects
import mc.xalitoria.crates.paper.data.model.AnimationItem
import mc.xalitoria.crates.paper.data.model.AnimationSounds
import mc.xalitoria.crates.paper.data.model.AnimationStage
import mc.xalitoria.crates.paper.data.model.AnimationSymbols
import mc.xalitoria.crates.paper.data.model.AttributeSpec
import mc.xalitoria.crates.paper.data.model.BannerPatternSpec
import mc.xalitoria.crates.paper.data.model.BlockFilterMode
import mc.xalitoria.crates.paper.data.model.CooldownConfig
import mc.xalitoria.crates.paper.data.model.CostConfig
import mc.xalitoria.crates.paper.data.model.CrateActionConfig
import mc.xalitoria.crates.paper.data.model.CrateActionType
import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.CrateDisplayConfig
import mc.xalitoria.crates.paper.data.model.CrateDisplayKind
import mc.xalitoria.crates.paper.data.model.CrateItem
import mc.xalitoria.crates.paper.data.model.CrateMenusConfig
import mc.xalitoria.crates.paper.data.model.FallbackRewardConfig
import mc.xalitoria.crates.paper.data.model.FireworkConfig
import mc.xalitoria.crates.paper.data.model.GiveItemConfig
import mc.xalitoria.crates.paper.data.model.HologramAnimationConfig
import mc.xalitoria.crates.paper.data.model.HologramAnimationType
import mc.xalitoria.crates.paper.data.model.HologramConfig
import mc.xalitoria.crates.paper.data.model.KeyClickCommands
import mc.xalitoria.crates.paper.data.model.KeyConfig
import mc.xalitoria.crates.paper.data.model.LuckyChestConfig
import mc.xalitoria.crates.paper.data.model.MultiCrateConfig
import mc.xalitoria.crates.paper.data.model.ObtainMethod
import mc.xalitoria.crates.paper.data.model.OpenEffectsConfig
import mc.xalitoria.crates.paper.data.model.OpenedSelectedItemConfig
import mc.xalitoria.crates.paper.data.model.OpeningConfig
import mc.xalitoria.crates.paper.data.model.ParticleAnimation
import mc.xalitoria.crates.paper.data.model.ParticleConfig
import mc.xalitoria.crates.paper.data.model.PotionEffectSpec
import mc.xalitoria.crates.paper.data.model.RepeatConfig
import mc.xalitoria.crates.paper.data.model.RewardConfig
import mc.xalitoria.crates.paper.data.model.SoundConfig
import mc.xalitoria.crates.paper.data.model.StageInventoryChange
import mc.xalitoria.crates.paper.data.model.TierConfig
import mc.xalitoria.crates.paper.data.model.UnselectedItemConfig
import mc.xalitoria.crates.paper.service.CrateRegistry
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeText

/** Summary returned after a SpecializedCrates migration (or dry run). */
data class MigrationReport(
    val dryRun: Boolean,
    val cratesMigrated: Int,
    val animationsMigrated: Int,
    val menusGenerated: Int,
    val placedCratesMigrated: Int,
    val playersMigrated: Int,
    val warnings: List<String>,
)

/** Parsed placed crate entry from SpecializedCrates `ActiveCrates.db`. */
data class MigratedPlacedCrate(val world: String, val x: Int, val y: Int, val z: Int, val crateId: String, val placedAt: Long)

/** Parsed player data entry from `PlayerData.db`, `PlayerData/<uuid>.db`, or MySQL `scPlayerStats`. */
data class MigratedPlayerData(
    val uuid: UUID,
    val virtualBalances: Map<String, Pair<Int, Int>>, // crateId -> (keys, crates)
    val cooldowns: Map<String, Long>, // crateId -> lastOpenedEpochMs
    val history: List<Triple<String, List<String>, Boolean>>, // (crateId, rewardIds, virtual)
)

/**
 * Pure, unit-testable migrator converting a SpecializedCrates data directory into XaliCrates configs, XaliMenu `.conf`
 * menus, and XaliCrates database records.
 */
object SpecializedCratesMigrator {
    /**
     * Runs a full migration from [sourceDir] (`plugins/SpecializedCrates`) into [targetDataDir] (`plugins/XaliCrates`)
     * and optional [xaliMenuDir] (`plugins/XaliMenu/menus`).
     */
    suspend fun migrate(
        sourceDir: Path,
        targetDataDir: Path,
        xaliMenuDir: Path?,
        dryRun: Boolean = false,
        overwrite: Boolean = false,
        skipData: Boolean = false,
    ): MigrationReport {
        val warnings = mutableListOf<String>()
        var cratesCount = 0
        var animsCount = 0
        var menusCount = 0
        var placedCount = 0
        var playersCount = 0

        val cratesOutDir = targetDataDir.resolve("crates")
        val animsOutDir = targetDataDir.resolve("animations")

        // Load legacy Rewards.yml if present (older SpecializedCrates format)
        val legacyRewards = mutableMapOf<String, RewardConfig>()
        val rewardsYml = sourceDir.resolve("Rewards.yml")
        if (rewardsYml.exists()) {
            val cfg = YamlConfiguration.loadConfiguration(rewardsYml.toFile())
            for (key in cfg.getKeys(false)) {
                if (key == "converted") continue
                val sec = cfg.getConfigurationSection(key) ?: continue
                legacyRewards[key] = parseReward(sec, warnings, "Rewards.yml:$key")
            }
        }

        // 1. Migrate custom animations/*.yml
        val animsInDir = sourceDir.resolve("animations")
        if (animsInDir.exists() && animsInDir.isDirectory()) {
            Files.list(animsInDir).use { stream ->
                stream.filter { it.isRegularFile() && it.extension.lowercase() in setOf("yml", "yaml") }.forEach { file ->
                    val id = sanitizeId(file.nameWithoutExtension)
                    try {
                        val anim = parseAnimation(YamlConfiguration.loadConfiguration(file.toFile()), warnings, file.fileName.toString())
                        val outFile = animsOutDir.resolve("$id.yml")
                        if (!outFile.exists() || overwrite) {
                            if (!dryRun) CrateRegistry.saveAnimation(animsOutDir, id, anim)
                            animsCount++
                        } else {
                            warnings.add("Skipped existing animation '$id' (use --overwrite to replace)")
                        }
                    } catch (e: Exception) {
                        warnings.add("Failed to migrate animation ${file.fileName}: ${e.message}")
                    }
                }
            }
        }

        // 2. Migrate Crates/*.crate and Crates/*.multicrate
        val cratesInDir = sourceDir.resolve("Crates")
        val crateNameMap = mutableMapOf<String, String>() // originalName -> normalized id
        if (cratesInDir.exists() && cratesInDir.isDirectory()) {
            Files.list(cratesInDir).use { stream ->
                stream.filter { it.isRegularFile() && it.extension.lowercase() in setOf("crate", "multicrate") }.forEach { file ->
                    val rawName = file.nameWithoutExtension
                    val id = sanitizeId(rawName)
                    val isMulti = file.extension.equals("multicrate", ignoreCase = true)
                    crateNameMap[rawName] = id
                    crateNameMap[rawName.lowercase()] = id
                    try {
                        val yml = YamlConfiguration.loadConfiguration(file.toFile())
                        val [crate, generatedMenu] = parseCrate(id, yml, isMulti, legacyRewards, warnings)
                        val outFile = cratesOutDir.resolve("$id.yml")
                        if (!outFile.exists() || overwrite) {
                            if (!dryRun) CrateRegistry.saveCrate(cratesOutDir, id, crate)
                            cratesCount++
                        } else {
                            warnings.add("Skipped existing crate '$id' (use --overwrite to replace)")
                        }
                        if (generatedMenu != null && xaliMenuDir != null) {
                            val [menuName, hocon] = generatedMenu
                            val menuFile = xaliMenuDir.resolve("$menuName.conf")
                            if (!menuFile.exists() || overwrite) {
                                if (!dryRun) {
                                    xaliMenuDir.createDirectories()
                                    menuFile.writeText(hocon)
                                }
                                menusCount++
                            }
                        }
                    } catch (e: Exception) {
                        warnings.add("Failed to migrate crate ${file.fileName}: ${e.message}")
                    }
                }
            }
        }

        if (!skipData) {
            // 3. Migrate ActiveCrates.db
            val activeFile = sourceDir.resolve("ActiveCrates.db")
            if (activeFile.exists()) {
                val placedList = parseActiveCrates(activeFile, crateNameMap, warnings)
                for (rec in placedList) {
                    if (!dryRun) {
                        PlacedCrateRepository.put(rec.world, rec.x, rec.y, rec.z, rec.crateId, rec.placedAt, 0L)
                    }
                    placedCount++
                }
            }

            // 4. Migrate player data (FLATFILE PlayerData.db, PLAYERFILES PlayerData/*.db, or SQL.yml)
            val playerRecords = parseAllPlayerData(sourceDir, crateNameMap, warnings)
            for (pd in playerRecords) {
                if (!dryRun) {
                    for ([crateId, bal] in pd.virtualBalances) {
                        val [keys, crates] = bal
                        if (keys > 0) VirtualBalanceRepository.giveKeys(pd.uuid, crateId, keys)
                        if (crates > 0) VirtualBalanceRepository.giveCrates(pd.uuid, crateId, crates)
                    }
                    for ([crateId, expiresAt] in pd.cooldowns) {
                        if (expiresAt > System.currentTimeMillis()) {
                            CooldownRepository.set(pd.uuid, crateId, "", expiresAt)
                        }
                    }
                    for ([crateId, rewards, isVirt] in pd.history) {
                        HistoryRepository.record(pd.uuid, pd.uuid.toString().take(8), crateId, rewards, System.currentTimeMillis(), isVirt)
                    }
                }
                playersCount++
            }
        }

        return MigrationReport(
            dryRun = dryRun,
            cratesMigrated = cratesCount,
            animationsMigrated = animsCount,
            menusGenerated = menusCount,
            placedCratesMigrated = placedCount,
            playersMigrated = playersCount,
            warnings = warnings,
        )
    }

    /** Parses a single `.crate` or `.multicrate` YAML file into a [CrateConfig] + optional generated XaliMenu `.conf`. */
    fun parseCrate(
        id: String,
        yml: YamlConfiguration,
        isMultiCrate: Boolean,
        legacyRewards: Map<String, RewardConfig> = emptyMap(),
        warnings: MutableList<String> = mutableListOf(),
    ): Pair<CrateConfig, Pair<String, String>?> {
        val enabled = yml.getBoolean("enabled", true)
        val obtain = when (yml.getString("obtain-method", "STATIC")?.uppercase()) {
            "DYNAMIC" -> ObtainMethod.DYNAMIC
            "LUCKYCHEST", "LUCKY_CHEST" -> ObtainMethod.LUCKY_CHEST
            else -> ObtainMethod.STATIC
        }
        val displayKind = when (yml.getString("display.type", "block")?.uppercase()) {
            "MOB", "NPC", "ENTITY" -> CrateDisplayKind.ENTITY
            else -> CrateDisplayKind.BLOCK
        }
        val displayEntity = yml.getString("display.creature") ?: yml.getString("display.name")
        val crateItem = yml.getConfigurationSection("crate")?.let { parseItem(it) } ?: CrateItem()
        val keyItem = yml.getConfigurationSection("key")?.let { parseItem(it) } ?: CrateItem(material = "tripwire_hook", name = "&eKey")

        val keyCommands = KeyClickCommands(
            leftClick = yml.getStringList("key.commands.left-click-commands").map(::convertKeyCommand),
            rightClick = yml.getStringList("key.commands.right-click-commands").map(::convertKeyCommand),
            shiftLeftClick = yml.getStringList("key.commands.shift-left-click-commands").map(::convertKeyCommand),
            shiftRightClick = yml.getStringList("key.commands.shift-right-click-commands").map(::convertKeyCommand),
        )
        val keyConfig = KeyConfig(
            required = yml.getBoolean("key.require", !isMultiCrate),
            item = keyItem,
            leftClickPreview = yml.getBoolean("left-click-key-preview", true),
            commands = keyCommands,
        )

        val costAmount = yml.getDouble("cost", -1.0).let { if (it < 0.0) 0.0 else it }
        val cost = CostConfig(amount = costAmount, keyOrEconomy = yml.getBoolean("require-key-or-economy", false))

        val cooldownSec = yml.getInt("cooldown", 0).coerceAtLeast(0)
        val ignoreCdPerm = yml.getString("ignore-cooldown-permission")?.takeUnless { it.equals("no permission", ignoreCase = true) || it.isBlank() }
        val cooldown = CooldownConfig(
            duration = "${cooldownSec}s",
            perLocation = yml.getBoolean("cooldown-per-location", false),
            bypassPermission = ignoreCdPerm,
        )

        val rawAnim = yml.getString("open.crate-animation", "INV_CSGO").orEmpty()
        val animId = mapAnimationName(rawAnim)
        val opening = OpeningConfig(
            animation = animId,
            inventoryTitle = yml.getString("inventory-name") ?: "&8&l> &d%crate%",
            allowSkip = yml.getBoolean("allow-skip-animation", false),
            autoClose = yml.getBoolean("auto-close", true),
        )

        val holoAnimType = when (yml.getString("hologram.animation.type", "NONE")?.uppercase()) {
            "TEXT_CHANGE", "TEXTCHANGE" -> HologramAnimationType.TEXT_CHANGE
            "MULTI_LINE_CHANGE", "MULTILINECHANGE" -> HologramAnimationType.MULTI_LINE_CHANGE
            else -> HologramAnimationType.NONE
        }
        val rewardHoloTicks = yml.getInt("hologram.reward-hologram-duration", 60).coerceAtLeast(0)
        val hologram = HologramConfig(
            enabled = yml.getStringList("hologram.lines").isNotEmpty() || !yml.getString("hologram.reward-hologram").isNullOrBlank(),
            lines = yml.getStringList("hologram.lines"),
            offset = yml.getDouble("hologram-offset", 0.0),
            animation = HologramAnimationConfig(
                type = holoAnimType,
                interval = yml.getInt("hologram.animation.speed", 20).coerceAtLeast(1),
                prefixes = yml.getStringList("hologram.animation.prefixes"),
            ),
            rewardLine = yml.getString("hologram.reward-hologram") ?: "",
            rewardDuration = "${(rewardHoloTicks / 20).coerceAtLeast(1)}s",
            rewardOffset = yml.getDouble("hologram.reward-hologram-yoffset", 0.0),
        )

        val ambient = AmbientEffectsConfig(particles = parseParticles(yml.getConfigurationSection("play.particles")))
        val effects = OpenEffectsConfig(
            actions = yml.getStringList("open.actions").mapNotNull(::parseAction),
            sounds = yml.getStringList("open.sounds").mapNotNull(::parseSound),
            particles = parseParticles(yml.getConfigurationSection("open.particles")),
            fireworks = yml.getStringList("open.fireworks").mapNotNull(::parseFirework),
        )

        val overrideDefaults = yml.getBoolean("open.tier-actions-override-defaults", true)
        val tiers = linkedMapOf<String, TierConfig>()
        yml.getConfigurationSection("open.crate-tiers")?.let { tierSec ->
            for (tierName in tierSec.getKeys(false)) {
                val ts = tierSec.getConfigurationSection(tierName) ?: continue
                tiers[tierName.lowercase()] = TierConfig(
                    overrideDefaults = overrideDefaults,
                    actions = ts.getStringList("actions").mapNotNull(::parseAction),
                    sounds = ts.getStringList("sounds").mapNotNull(::parseSound),
                    particles = parseParticles(ts.getConfigurationSection("particles")),
                    fireworks = ts.getStringList("fireworks").mapNotNull(::parseFirework),
                )
            }
        }

        val luckyChest = yml.getConfigurationSection("lucky-chest")?.let { lc ->
            val rawChance = lc.getString("chance", "1/100") ?: "1/100"
            val pct = parseFractionChance(rawChance)
            LuckyChestConfig(
                chance = pct,
                blockFilter = if (lc.getBoolean("is-block-list-whitelist", true)) BlockFilterMode.WHITELIST else BlockFilterMode.BLACKLIST,
                blocks = lc.getStringList("block-list"),
                worlds = lc.getStringList("worlds"),
                requirePermission = lc.getBoolean("require-permission", true),
            )
        }

        // Parse rewards (inline section OR legacy string list referencing Rewards.yml)
        val rewards = linkedMapOf<String, RewardConfig>()
        val rewardsSec = yml.getConfigurationSection("rewards")
        if (rewardsSec != null) {
            for (rKey in rewardsSec.getKeys(false)) {
                val rSec = rewardsSec.getConfigurationSection(rKey) ?: continue
                rewards[rKey] = parseReward(rSec, warnings, "$id:$rKey")
            }
        } else if (yml.isList("rewards")) {
            for (rName in yml.getStringList("rewards")) {
                val found = legacyRewards[rName]
                if (found != null) rewards[rName] = found
                else warnings.add("Crate '$id' references missing legacy reward '$rName'")
            }
        }

        // Handle MultiCrate or Custom Reward Display -> generate clean XaliMenu .conf!
        var generatedMenu: Pair<String, String>? = null
        var menus = CrateMenusConfig()
        var multiCrate: MultiCrateConfig? = null

        if (isMultiCrate) {
            val childCrates = mutableListOf<String>()
            yml.getConfigurationSection("gui.objects")?.let { objSec ->
                for (sym in objSec.getKeys(false)) {
                    if (objSec.isString(sym)) {
                        childCrates.add(sanitizeId(objSec.getString(sym)!!))
                    }
                }
            }
            val menuName = "crates-multi-$id"
            multiCrate = MultiCrateConfig(enabled = true, crates = childCrates.distinct())
            menus = menus.copy(multiCrate = menuName)
            generatedMenu = menuName to generateMultiCrateMenuHocon(id, yml, childCrates)
        } else if (yml.getString("reward-display.type")?.equals("CUSTOM", ignoreCase = true) == true) {
            val menuName = "crates-preview-$id"
            menus = menus.copy(preview = menuName)
            generatedMenu = menuName to generateCustomPreviewHocon(id, yml)
        }

        val permission = yml.getString("permission")?.takeUnless { it.equals("no permission", ignoreCase = true) || it.isBlank() }
        val config = CrateConfig(
            enabled = enabled,
            displayName = crateItem.name ?: yml.getString("inventory-name") ?: id,
            permission = permission,
            obtain = obtain,
            display = CrateDisplayConfig(kind = displayKind, entity = displayEntity),
            crateItem = crateItem,
            key = keyConfig,
            cost = cost,
            cooldown = cooldown,
            opening = opening,
            hologram = hologram,
            ambient = ambient,
            effects = effects,
            tiers = tiers,
            luckyChest = luckyChest,
            menus = menus,
            multiCrate = multiCrate,
            rewards = rewards,
        )
        return config to generatedMenu
    }

    /** Parses a SpecializedCrates `animations/<name>.yml` into an [AnimationConfig]. */
    fun parseAnimation(yml: YamlConfiguration, warnings: MutableList<String> = mutableListOf(), sourceName: String = ""): AnimationConfig {
        val fillers = linkedMapOf<String, AnimationItem>()
        yml.getConfigurationSection("filler_items")?.let { fSec ->
            for (sym in fSec.getKeys(false)) {
                val sec = fSec.getConfigurationSection(sym) ?: continue
                fillers[sym] = parseAnimItem(sec)
            }
        }
        val byRarity = linkedMapOf<String, AnimationItem>()
        yml.getConfigurationSection("opened_selected_reward_item.rarity")?.let { rSec ->
            for (rKey in rSec.getKeys(false)) {
                val sec = rSec.getConfigurationSection(rKey) ?: continue
                byRarity[rKey.lowercase()] = parseAnimItem(sec)
            }
        }
        val stages = mutableListOf<AnimationStage>()
        yml.getConfigurationSection("stages")?.let { sSec ->
            val sortedKeys = sSec.getKeys(false).sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            for (k in sortedKeys) {
                val st = sSec.getConfigurationSection(k) ?: continue
                val invChange = when (st.getString("inventory_change")?.lowercase()) {
                    "close" -> StageInventoryChange.CLOSE
                    "open" -> StageInventoryChange.OPEN
                    else -> StageInventoryChange.NONE
                }
                val potions = st.getStringList("potion_effects").mapNotNull(::parseLegacyPotion)
                stages.add(
                    AnimationStage(
                        grid = st.getStringList("inventory"),
                        repeatStage = st.getInt("repeated_stage", 0),
                        repeatTimes = st.getInt("repeat_times", 0),
                        stayTicks = st.getInt("frame_stay_time", 0),
                        resetRewards = st.getStringList("reset_rewards"),
                        rewardSlots = st.getStringList("rewards_obtained"),
                        sound = st.getString("sound")?.let { parseSound(it) },
                        potionEffects = potions,
                        inventoryChange = invChange,
                        totemEffect = st.getBoolean("activate_totem_of_undying", false),
                    ),
                )
            }
        }
        val endParticles = mutableListOf<ParticleConfig>()
        yml.getConfigurationSection("end_actions.particles")?.let { pSec ->
            for (pType in pSec.getKeys(false)) {
                val ps = pSec.getConfigurationSection(pType) ?: continue
                endParticles.add(
                    ParticleConfig(
                        type = mapParticleName(pType),
                        amount = ps.getInt("amount", 10).coerceIn(1, 500),
                        rangeX = ps.getDouble("x_offset", 1.0),
                        rangeY = ps.getDouble("y_offset", 1.0),
                        rangeZ = ps.getDouble("z_offset", 1.0),
                    ),
                )
            }
        }
        return AnimationConfig(
            inventorySize = yml.getInt("inventory_size", 27).let { if (it in 9..54 && it % 9 == 0) it else 27 },
            defaultFrameTicks = yml.getInt("default_frame_rate", 4).coerceAtLeast(1),
            autoCloseTicks = yml.getInt("auto_close", 60).coerceAtLeast(0),
            minSelect = yml.getInt("min_amount_to_select", 4).coerceAtLeast(1),
            symbols = AnimationSymbols(
                air = yml.getString("air_symbol") ?: "A",
                randomReward = yml.getString("random_reward_symbol") ?: "R",
                rewardSymbols = yml.getStringList("reward_symbols"),
                randomFiller = yml.getString("random_filler_item_symbol") ?: "RF",
                selectReward = yml.getString("select_reward_symbol") ?: "RS",
                openSelectedReward = yml.getString("open_selected_reward_symbol") ?: "OS",
                revealReward = yml.getString("reveal_reward_symbol") ?: "RR",
                selectedRevealReward = yml.getString("selected_reveal_reward_symbol") ?: "SR",
                nextStage = yml.getString("next_stage_symbol") ?: "NS",
            ),
            fillers = fillers,
            closedSelectItem = yml.getConfigurationSection("closed_select_reward_item")?.let(::parseAnimItem) ?: AnimationItem(material = "chest", name = "&6&l???", amountFromSlot = true),
            selectedItem = yml.getConfigurationSection("selected_reward_item")?.let(::parseAnimItem) ?: AnimationItem(material = "white_stained_glass_pane", name = "&f&l???", amountFromSlot = true),
            openedSelectedItem = OpenedSelectedItemConfig(
                revealInstantly = yml.getBoolean("opened_selected_reward_item.reveal_item_instantly", false),
                item = yml.getConfigurationSection("opened_selected_reward_item")?.let(::parseAnimItem) ?: AnimationItem(material = "white_stained_glass_pane", name = "&f&l???"),
                byRarity = byRarity,
            ),
            unselectedItem = UnselectedItemConfig(
                randomFiller = yml.getBoolean("unselected_reward_item.random_filler", false),
                filler = yml.getString("unselected_reward_item.filler"),
                item = yml.getConfigurationSection("unselected_reward_item")?.let(::parseAnimItem) ?: AnimationItem(material = "air", name = ""),
            ),
            revealItem = yml.getConfigurationSection("reveal_item")?.let(::parseAnimItem) ?: AnimationItem(material = "cyan_stained_glass_pane", name = "&bClick to reveal", glow = true),
            nextStageItem = yml.getConfigurationSection("next_stage_item")?.let(::parseAnimItem) ?: AnimationItem(material = "ender_chest", name = "&eContinue", glow = true),
            stages = stages,
            sounds = AnimationSounds(
                stage = yml.getString("sounds.default_stage_sound")?.let(::parseSound),
                click = yml.getString("sounds.click_item_sound")?.let(::parseSound),
                end = yml.getString("end_actions.sound")?.let(::parseSound),
            ),
            endEffects = AnimationEndEffects(particles = endParticles),
        )
    }

    /** Parses `ActiveCrates.db` (`world;x;y;z` -> `{crate, placedTime}`). */
    fun parseActiveCrates(
        file: Path,
        crateNameMap: Map<String, String>,
        warnings: MutableList<String>,
    ): List<MigratedPlacedCrate> {
        val yml = YamlConfiguration.loadConfiguration(file.toFile())
        val out = mutableListOf<MigratedPlacedCrate>()
        for (locKey in yml.getKeys(false)) {
            val parts = locKey.split(';', ',')
            if (parts.size < 4) {
                warnings.add("ActiveCrates.db: could not parse location '$locKey'")
                continue
            }
            val world = parts[0]
            val x = parts[1].toDoubleOrNull()?.toInt()
            val y = parts[2].toDoubleOrNull()?.toInt()
            val z = parts[3].toDoubleOrNull()?.toInt()
            val rawCrate = yml.getString("$locKey.crate")
            if (x == null || y == null || z == null || rawCrate.isNullOrBlank()) {
                warnings.add("ActiveCrates.db: incomplete entry '$locKey'")
                continue
            }
            val crateId = crateNameMap[rawCrate] ?: crateNameMap[rawCrate.lowercase()] ?: sanitizeId(rawCrate)
            val placedAt = yml.getLong("$locKey.placedTime", System.currentTimeMillis())
            out.add(MigratedPlacedCrate(world, x, y, z, crateId, placedAt))
        }
        return out
    }

    /** Parses SpecializedCrates player data from FLATFILE, PLAYERFILES, or SQL.yml. */
    fun parseAllPlayerData(
        sourceDir: Path,
        crateNameMap: Map<String, String>,
        warnings: MutableList<String>,
    ): List<MigratedPlayerData> {
        val configYml = sourceDir.resolve("config.yml").takeIf { it.exists() }?.let { YamlConfiguration.loadConfiguration(it.toFile()) }
        val storage = configYml?.getString("store-data", "FLATFILE")?.uppercase() ?: "FLATFILE"
        val out = mutableListOf<MigratedPlayerData>()

        // Flatfile: PlayerData.db
        val flat = sourceDir.resolve("PlayerData.db")
        if (flat.exists()) {
            val yml = YamlConfiguration.loadConfiguration(flat.toFile())
            for (uuidStr in yml.getKeys(false)) {
                val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull() ?: continue
                val sec = yml.getConfigurationSection(uuidStr) ?: continue
                out.add(
                    parsePlayerBlob(
                        uuid = uuid,
                        virtualStr = sec.getString("virtual-crates").orEmpty(),
                        cooldownStr = sec.getString("crate-cooldowns").orEmpty(),
                        historyStr = sec.getString("history").orEmpty(),
                        crateNameMap = crateNameMap,
                    ),
                )
            }
        }

        // Playerfiles: PlayerData/<uuid>.db
        val playerDir = sourceDir.resolve("PlayerData")
        if (playerDir.exists() && playerDir.isDirectory()) {
            Files.list(playerDir).use { stream ->
                stream.filter { it.isRegularFile() }.forEach { file ->
                    val uuid = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull() ?: return@forEach
                    val yml = YamlConfiguration.loadConfiguration(file.toFile())
                    out.add(
                        parsePlayerBlob(
                            uuid = uuid,
                            virtualStr = yml.getString("virtual-crates").orEmpty(),
                            cooldownStr = yml.getString("crate-cooldowns").orEmpty(),
                            historyStr = yml.getString("history").orEmpty(),
                            crateNameMap = crateNameMap,
                        ),
                    )
                }
            }
        }

        // Optional MySQL migration when store-data == MYSQL and SQL.yml is configured
        val sqlFile = sourceDir.resolve("SQL.yml")
        if (storage == "MYSQL" && sqlFile.exists()) {
            try {
                val sqlYml = YamlConfiguration.loadConfiguration(sqlFile.toFile())
                val host = sqlYml.getString("database.ip") ?: "localhost"
                val port = sqlYml.getString("database.port") ?: "3306"
                val db = sqlYml.getString("database.name") ?: ""
                val user = sqlYml.getString("database.username") ?: ""
                val pass = sqlYml.getString("database.password") ?: ""
                if (db.isNotBlank() && user.isNotBlank()) {
                    val url = "jdbc:mariadb://$host:$port/$db"
                    DriverManager.getConnection(url, user, pass).use { conn ->
                        conn.prepareStatement("SELECT uuid, virtualCrates, crateCooldowns, history FROM scPlayerStats").use { ps ->
                            ps.executeQuery().use { rs ->
                                while (rs.next()) {
                                    val uuid = runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull() ?: continue
                                    out.add(
                                        parsePlayerBlob(
                                            uuid = uuid,
                                            virtualStr = rs.getString("virtualCrates").orEmpty(),
                                            cooldownStr = rs.getString("crateCooldowns").orEmpty(),
                                            historyStr = rs.getString("history").orEmpty(),
                                            crateNameMap = crateNameMap,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                warnings.add("Could not read SpecializedCrates MySQL table scPlayerStats: ${e.message}")
            }
        }
        return out
    }

    internal fun parsePlayerBlob(
        uuid: UUID,
        virtualStr: String,
        cooldownStr: String,
        historyStr: String,
        crateNameMap: Map<String, String>,
    ): MigratedPlayerData {
        val balances = mutableMapOf<String, Pair<Int, Int>>()
        for (entry in virtualStr.split(',')) {
            if (entry.isBlank()) continue
            val parts = entry.split(';')
            if (parts.size < 3) continue
            val id = crateNameMap[parts[0]] ?: crateNameMap[parts[0].lowercase()] ?: sanitizeId(parts[0])
            val crates = parts[1].toIntOrNull() ?: 0
            val keys = parts[2].toIntOrNull() ?: 0
            if (keys > 0 || crates > 0) balances[id] = keys to crates
        }
        val cooldowns = mutableMapOf<String, Long>()
        for (entry in cooldownStr.split(',')) {
            if (entry.isBlank()) continue
            val parts = entry.split(';')
            if (parts.size < 2) continue
            val id = crateNameMap[parts[0]] ?: crateNameMap[parts[0].lowercase()] ?: sanitizeId(parts[0])
            val ts = parts[1].toLongOrNull() ?: continue
            cooldowns[id] = ts
        }
        val history = mutableListOf<Triple<String, List<String>, Boolean>>()
        for (entry in historyStr.split(',')) {
            if (entry.isBlank()) continue
            val parts = entry.split(';')
            if (parts.size < 4) continue
            val id = crateNameMap[parts[1]] ?: crateNameMap[parts[1].lowercase()] ?: sanitizeId(parts[1])
            val rewards = parts[2].removePrefix("[").removeSuffix("]").split("%newReward% ").map { it.trim() }.filter { it.isNotEmpty() }
            val virt = parts[3].equals("true", ignoreCase = true)
            history.add(Triple(id, rewards, virt))
        }
        return MigratedPlayerData(uuid, balances, cooldowns, history)
    }

    private fun parseReward(sec: ConfigurationSection, warnings: MutableList<String>, ctx: String): RewardConfig {
        val displayItem = sec.getConfigurationSection("display-item")?.let(::parseItem) ?: CrateItem(material = "diamond")
        val fbName = sec.getString("fallback-reward.reward-name")?.takeIf { it.isNotBlank() }
            ?: sec.getString("fallback.reward")?.takeIf { it.isNotBlank() }
            .orEmpty()
        val fbPerm = sec.getString("fallback-reward.permission")?.takeIf { it.isNotBlank() }
            ?: sec.getString("fallback.permission")?.takeIf { it.isNotBlank() }
            ?: sec.getStringList("fallback.permissions").firstOrNull { it.isNotBlank() }
            .orEmpty()
        val fallback = if (fbName.isNotBlank() && fbPerm.isNotBlank()) {
            FallbackRewardConfig(permission = fbPerm, reward = fbName)
        } else null
        val giveEnabled = if (sec.isBoolean("give-display-item")) {
            sec.getBoolean("give-display-item", false)
        } else {
            sec.getBoolean("give-display-item.value", false)
        }
        return RewardConfig(
            weight = sec.getDouble("chance", 10.0).coerceAtLeast(0.0),
            rarity = sec.getString("rarity")?.lowercase()?.ifBlank { "default" } ?: "default",
            guaranteed = sec.getBoolean("guaranteed-reward", sec.getBoolean("guaranteed", false)),
            fallbackOnly = sec.getBoolean("fallback-reward.value", sec.getBoolean("fallback-only", false)),
            displayItem = displayItem,
            giveItem = GiveItemConfig(
                enabled = giveEnabled,
                keepName = sec.getBoolean("give-display-item.with-name", true),
                keepLore = sec.getBoolean("give-display-item.with-lore", true),
            ),
            commands = sec.getStringList("commands"),
            linkedRewards = sec.getStringList("linked-rewards"),
            shulkerContents = sec.getStringList("shulker-contents"),
            fallback = fallback,
            repeat = RepeatConfig(
                chance = sec.getDouble("repeatChance", 0.0).coerceIn(0.0, 100.0),
                max = sec.getInt("maxRepeat", 1).coerceAtLeast(0),
            ),
        )
    }

    private fun parseItem(sec: ConfigurationSection): CrateItem {
        val rawMat = sec.getString("material", "chest") ?: "chest"
        val material = mapMaterial(rawMat, sec.getString("base64-texture"), sec.getString("player-head-name"))
        val enchants = linkedMapOf<String, Int>()
        for (line in sec.getStringList("enchantments")) {
            val parts = line.split(';', ':')
            if (parts.size >= 2) {
                val key = mapLegacyEnchant(parts[0].trim())
                val lvl = parts[1].trim().toIntOrNull() ?: 1
                enchants[key] = lvl
            }
        }
        val potions = sec.getStringList("potion-effects").mapNotNull(::parseLegacyPotion)
        val color = sec.getConfigurationSection("color")?.let { c ->
            String.format("#%02X%02X%02X", c.getInt("red").coerceIn(0, 255), c.getInt("green").coerceIn(0, 255), c.getInt("blue").coerceIn(0, 255))
        }
        val patterns = sec.getConfigurationSection("patterns")?.let { pSec ->
            pSec.getKeys(false).map { pat -> BannerPatternSpec(pat.lowercase(), pSec.getString(pat, "WHITE") ?: "WHITE") }
        } ?: []
        val attrs = sec.getConfigurationSection("attributes")?.let { aSec ->
            aSec.getKeys(false).mapNotNull { attrKey ->
                val a = aSec.getConfigurationSection(attrKey) ?: return@mapNotNull null
                AttributeSpec(
                    attribute = attrKey.lowercase().removePrefix("generic_"),
                    amount = a.getDouble("amount", 0.0),
                    operation = a.getString("operation", "ADD_NUMBER") ?: "ADD_NUMBER",
                    slot = a.getString("slot"),
                )
            }
        } ?: []
        val nbt = sec.getString("nbt-tags")?.takeUnless { it.isBlank() || it == "{}" }
        return CrateItem(
            material = material,
            amount = sec.getInt("amount", 1).coerceIn(1, 99),
            name = sec.getString("name"),
            lore = sec.getStringList("lore"),
            glow = sec.getBoolean("glow", sec.getBoolean("enchanted", false)),
            damage = sec.getInt("damage", 0).coerceAtLeast(0),
            customModelData = sec.getInt("custom-model-data", 0).coerceAtLeast(0),
            itemModel = sec.getString("item-model") ?: sec.getString("item_model"),
            unbreakable = sec.getBoolean("unbreakable", false),
            enchantments = enchants,
            flags = sec.getStringList("item-flags"),
            color = color,
            potionEffects = potions,
            baseColor = sec.getString("base-color"),
            patterns = patterns,
            attributes = attrs,
            nbt = nbt,
        )
    }

    private fun parseAnimItem(sec: ConfigurationSection): AnimationItem = AnimationItem(
        material = mapMaterial(sec.getString("material", "gray_stained_glass_pane") ?: "gray_stained_glass_pane", null, null),
        name = sec.getString("name") ?: " ",
        lore = sec.getStringList("lore"),
        glow = sec.getBoolean("enchanted", sec.getBoolean("glow", false)),
        amountFromSlot = sec.getBoolean("amount_slot", false),
        customModelData = sec.getInt("custom-model-data", 0).coerceAtLeast(0),
        itemModel = sec.getString("item-model") ?: sec.getString("item_model"),
    )

    private fun parseParticles(sec: ConfigurationSection?): List<ParticleConfig> {
        if (sec == null) return []
        return sec.getKeys(false).mapNotNull { key ->
            val p = sec.getConfigurationSection(key) ?: return@mapNotNull null
            val anim = runCatching { ParticleAnimation.valueOf(p.getString("animation", "NONE")!!.uppercase()) }
                .getOrDefault(ParticleAnimation.NONE)
            val color = if (p.getBoolean("color.enabled", false)) {
                String.format("#%02X%02X%02X", p.getInt("color.red", 255), p.getInt("color.green", 0), p.getInt("color.blue", 0))
            } else null
            ParticleConfig(
                type = mapParticleName(p.getString("type", "flame") ?: "flame"),
                animation = anim,
                amount = p.getInt("amount", 1).coerceIn(1, 500),
                speed = p.getDouble("speed", 0.05).coerceAtLeast(0.0),
                rangeX = p.getDouble("range-x", 0.6).coerceAtLeast(0.0),
                rangeY = p.getDouble("range-y", 0.6).coerceAtLeast(0.0),
                rangeZ = p.getDouble("range-z", 0.6).coerceAtLeast(0.0),
                offsetX = p.getDouble("center-x", 0.0),
                offsetY = p.getDouble("center-y", 0.5),
                offsetZ = p.getDouble("center-z", 0.0),
                color = color,
            )
        }
    }

    private fun parseSound(raw: String): SoundConfig? {
        val parts = raw.split(',').map { it.trim() }
        if (parts.isEmpty() || parts[0].isEmpty()) return null
        val sound = mapLegacySound(parts[0])
        val vol = parts.getOrNull(1)?.toFloatOrNull()?.coerceIn(0f, 10f) ?: 1.0f
        val pitch = parts.getOrNull(2)?.toFloatOrNull()?.coerceIn(0f, 2f) ?: 1.0f
        return SoundConfig(sound, vol, pitch)
    }

    private fun parseAction(raw: String): CrateActionConfig? {
        val idx = raw.indexOf(',')
        if (idx <= 0) return null
        val typeName = raw.substring(0, idx).trim().uppercase()
        val value = raw.substring(idx + 1).trim()
        val type = when (typeName) {
            "MESSAGE" -> CrateActionType.MESSAGE
            "BROADCAST" -> CrateActionType.BROADCAST
            "PRE_BROADCAST" -> CrateActionType.PRE_BROADCAST
            "ACTIONBAR" -> CrateActionType.ACTIONBAR
            "TITLE" -> CrateActionType.TITLE
            "SUBTITLE" -> CrateActionType.SUBTITLE
            "COMMAND" -> CrateActionType.COMMAND
            else -> return null
        }
        return CrateActionConfig(type, value)
    }

    private fun parseFirework(raw: String): FireworkConfig? {
        // Format: "BLUE;AQUA, WHITE;YELLOW;ORANGE, false, true, STAR, 1"
        val parts = raw.split(',').map { it.trim() }
        if (parts.isEmpty()) return null
        val colors = parts[0].split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val fades = parts.getOrNull(1)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val flicker = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: false
        val trail = parts.getOrNull(3)?.toBooleanStrictOrNull() ?: true
        val shape = parts.getOrNull(4)?.uppercase()?.ifEmpty { "STAR" } ?: "STAR"
        val power = parts.getOrNull(5)?.toIntOrNull()?.coerceIn(0, 4) ?: 1
        return FireworkConfig(colors.ifEmpty { ["WHITE"] }, fades, shape, flicker, trail, power)
    }

    private fun parseLegacyPotion(raw: String): PotionEffectSpec? {
        val parts = raw.split(';')
        if (parts.isEmpty() || parts[0].isBlank()) return null
        val effect = when (val n = parts[0].trim().lowercase()) {
            "jump" -> "jump_boost"
            "increase_damage" -> "strength"
            "damage_resistance" -> "resistance"
            "fast_digging" -> "haste"
            "slow" -> "slowness"
            else -> n
        }
        val duration = parts.getOrNull(1)?.toIntOrNull()?.let { if (it <= 0) 200 else it * 20 } ?: 200
        val amp = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 255) ?: 0
        return PotionEffectSpec(effect, duration, amp)
    }

    private fun generateMultiCrateMenuHocon(id: String, yml: YamlConfiguration, childCrates: List<String>): String {
        val title = (yml.getString("inventory-name") ?: "&5&l$id").replace("\"", "\\\"")
        val rows = yml.getStringList("gui.rows").size.coerceIn(1, 6).let { if (it == 0) 3 else it }
        return """
            # Migrated from SpecializedCrates multicrate '$id'
            title = "$title"
            size = ${rows * 9}
            register = false
            pages {
              catalog { type = "xalicrates:crates", multi-crate = "$id" }
              slots = [${(10 until (rows * 9 - 9)).filter { it % 9 in 1..7 }.joinToString(", ")}]
              item {
                material = "{material}"
                name = "%xalicrates_crate_name_{id}%"
                lore = [
                  "&7Virtual Keys: &d{keys}",
                  "&aLeft-click &7to preview &8| &eRight-click &7to open",
                ]
                update = true
                click.left.actions = [{ type = "xalicrates:preview", crate = "{id}" }]
                click.right.actions = [{ type = "xalicrates:open", crate = "{id}", virtual = true }]
              }
            }
            items {
              close { material = "barrier", slot = ${rows * 9 - 5}, name = "&cClose", click.any.actions = [{ type = "close" }] }
            }
        """.trimIndent() + "\n"
    }

    private fun generateCustomPreviewHocon(id: String, yml: YamlConfiguration): String {
        val title = (yml.getString("reward-display.name") ?: "&8Rewards: &d$id").replace("\"", "\\\"")
        return """
            # Migrated from SpecializedCrates custom reward preview of '$id'
            title = "$title &7({page}/{pages})"
            size = 54
            register = false
            args = ["crate"]
            layout = ["#########", "#xxxxxxx#", "#xxxxxxx#", "#xxxxxxx#", "#xxxxxxx#", "#########"]
            legend { "#" = border }
            pages {
              catalog { type = "xalicrates:rewards", crate = "$id" }
              layout = ["#########", "#xxxxxxx#", "#xxxxxxx#", "#xxxxxxx#", "#xxxxxxx#", "#########"]
              cell = "x"
              item {
                material = "{material}"
                name = "%xalicrates_reward_name_${id}_{id}%"
                lore = ["&7Rarity: &d{rarity}", "&7Chance: &e{chance}%"]
              }
            }
            items {
              border { material = "gray_stained_glass_pane", name = " " }
              prev {
                material = "arrow", slot = 45, name = "&aPrevious Page"
                view.require = [{ condition { type = "compare", left = "{page}", op = "GT", right = "1" } }]
                click.any.actions = [{ type = "page", by = -1 }]
              }
              next {
                material = "arrow", slot = 53, name = "&aNext Page"
                view.require = [{ condition { type = "compare", left = "{page}", op = "LT", right = "{pages}" } }]
                click.any.actions = [{ type = "page", by = 1 }]
              }
              close { material = "barrier", slot = 49, name = "&cClose", click.any.actions = [{ type = "close" }] }
            }
        """.trimIndent() + "\n"
    }

    fun sanitizeId(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9_-]"), "_").trim('_').ifEmpty { "crate" }.take(64)

    internal fun parseFractionChance(raw: String): Double {
        val parts = raw.split('/')
        if (parts.size == 2) {
            val num = parts[0].trim().toDoubleOrNull() ?: 1.0
            val den = parts[1].trim().toDoubleOrNull() ?: 100.0
            if (den > 0.0) return ((num / den) * 100.0).coerceIn(0.0, 100.0)
        }
        return raw.trim().toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 1.0
    }

    internal fun mapAnimationName(raw: String): String = when (raw.trim().uppercase()) {
        "GIVE_KEY", "NONE", "" -> "instant"
        "BLOCK_CRATEOPEN", "OPENCHEST", "OPEN_CHEST" -> "open-chest"
        "INV_ROULETTE", "ROULETTE" -> "roulette"
        "INV_CSGO", "CSGO" -> "csgo"
        "INV_MENU", "MENU" -> "menu"
        "INV_ENCLOSE", "ENCLOSE" -> "enclose"
        "INV_DISCOVER", "DISCOVER" -> "discover"
        "INV_DISCOVER_EULA_FRIENDLY", "DISCOVER_EULA" -> "discover-eula"
        else -> sanitizeId(raw)
    }

    internal fun mapMaterial(raw: String, base64: String?, headName: String?): String {
        if (!base64.isNullOrBlank()) return "texture:$base64"
        if (!headName.isNullOrBlank()) return "head:$headName"
        val trimmed = raw.trim()
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("nexo:") -> "nexo:${trimmed.substringAfter(':')}"
            lower.startsWith("itemsadder:") -> "itemsadder:${trimmed.substringAfter(':')}"
            lower.startsWith("headdatabase:") -> "hdb:${trimmed.substringAfter(':')}"
            else -> {
                val base = trimmed.substringBefore(';').trim().lowercase()
                when (base) {
                    "grass" -> "short_grass"
                    "redstone_torch_on" -> "redstone_torch"
                    "stained_glass_pane" -> "gray_stained_glass_pane"
                    else -> base
                }
            }
        }
    }

    private fun mapLegacyEnchant(raw: String): String = when (raw.uppercase()) {
        "DURABILITY" -> "unbreaking"
        "DAMAGE_ALL" -> "sharpness"
        "DAMAGE_UNDEAD" -> "smite"
        "DAMAGE_ARTHROPODS" -> "bane_of_arthropods"
        "DIG_SPEED" -> "efficiency"
        "LOOT_BONUS_BLOCKS" -> "fortune"
        "LOOT_BONUS_MOBS" -> "looting"
        "PROTECTION_ENVIRONMENTAL" -> "protection"
        "PROTECTION_FIRE" -> "fire_protection"
        "PROTECTION_FALL" -> "feather_falling"
        "PROTECTION_EXPLOSIONS" -> "blast_protection"
        "PROTECTION_PROJECTILE" -> "projectile_protection"
        "OXYGEN" -> "respiration"
        "WATER_WORKER" -> "aqua_affinity"
        "ARROW_DAMAGE" -> "power"
        "ARROW_KNOCKBACK" -> "punch"
        "ARROW_FIRE" -> "flame"
        "ARROW_INFINITE" -> "infinity"
        else -> raw.lowercase()
    }

    private fun mapParticleName(raw: String): String = when (raw.trim().uppercase()) {
        "VILLAGER_HAPPY" -> "happy_villager"
        "VILLAGER_ANGRY" -> "angry_villager"
        "SMOKE_NORMAL" -> "smoke"
        "CRIT_MAGIC" -> "enchanted_hit"
        "EXPLOSION_HUGE" -> "explosion_emitter"
        "SPELL_WITCH" -> "witch"
        "SPELL_INSTANT" -> "instant_effect"
        "FIREWORKS_SPARK" -> "firework"
        "REDSTONE" -> "dust"
        "SUSPENDED_DEPTH" -> "underwater"
        else -> raw.trim().lowercase()
    }

    private fun mapLegacySound(raw: String): String = when (val upper = raw.trim().uppercase()) {
        "CLICK" -> "ui.button.click"
        "LEVEL_UP", "ENTITY_PLAYER_LEVELUP" -> "entity.player.levelup"
        "BAT_TAKEOFF" -> "entity.bat.takeoff"
        "ENTITY_GENERIC_EXPLODE" -> "entity.generic.explode"
        "ENTITY_ENDER_DRAGON_GROWL", "ENDERDRAGON_GROWL" -> "entity.ender_dragon.growl"
        "AMBIENT_CAVE" -> "ambient.cave"
        "BLOCK_ANVIL_PLACE" -> "block.anvil.place"
        else -> if ('.' in raw) raw.lowercase() else upper.lowercase().replace('_', '.')
    }

    private fun convertKeyCommand(cmd: String): String =
        if (cmd.contains("player; ")) "player:" + cmd.replace("player; ", "").trim() else cmd.trim()
}
