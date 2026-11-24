package mc.xalitoria.crates.paper.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How a placed crate behaves when opened. */
enum class ObtainMethod {
    /** Stays in the world permanently after each opening. */
    STATIC,

    /** Single-use crate that disappears once opened. */
    DYNAMIC,

    /** Spawns randomly while mining matching blocks and disappears after opening or despawn timer. */
    LUCKY_CHEST,
}

/** How a placed crate is represented in the world. */
enum class CrateDisplayKind {
    /** A standard Minecraft block placed at the crate's coordinates. */
    BLOCK,

    /** A stationary, invulnerable entity spawned at the crate's coordinates. */
    ENTITY,
}

/** What happens when a player's inventory cannot hold an item reward. */
enum class FullInventoryPolicy {
    /** Refuse to open the crate if the player lacks the required empty slots. */
    DENY,

    /** Drop overflow items naturally at the player's feet. */
    DROP,

    /** Store overflow items in XaliRedeem as claimable pending rewards (falls back to DROP if XaliRedeem is absent). */
    REDEEM,
}

/** Controls whether a reward is delivered directly or routed to XaliRedeem. */
enum class RedeemDeliveryMode {
    /** Deliver normally; overflow goes to XaliRedeem when the crate's full-inventory policy is REDEEM. */
    AUTO,

    /** Always store this reward in XaliRedeem for the player to claim later, even if their inventory has room. */
    ALWAYS,

    /** Never route this reward to XaliRedeem; drop overflow at the player's feet instead. */
    NEVER,
}

/** Particle geometric pattern around a crate. */
enum class ParticleAnimation {
    NONE,
    CIRCLE,
    SPIRAL,
    DOUBLE_SPIRAL,
    GROWING_SPIRAL,
    TILTED_RINGS,
    OFFSET_TILTED_RINGS,
}

/** Hologram text animation pattern above a placed crate. */
enum class HologramAnimationType {
    NONE,
    TEXT_CHANGE,
    MULTI_LINE_CHANGE,
}

/** Whether a lucky chest block list acts as a whitelist or a blacklist. */
enum class BlockFilterMode {
    WHITELIST,
    BLACKLIST,
}

/** Action executed when a crate starts or finishes opening. */
enum class CrateActionType {
    /** Private message to the opening player. */
    MESSAGE,

    /** Broadcast message to every online player. */
    BROADCAST,

    /** Broadcast message sent immediately when opening starts, before the animation finishes. */
    PRE_BROADCAST,

    /** Action bar message shown to the opening player. */
    ACTIONBAR,

    /** Title shown to the opening player. */
    TITLE,

    /** Subtitle shown to the opening player. */
    SUBTITLE,

    /** Command executed as the console (without a leading slash). */
    COMMAND,

    /** Command executed as the opening player (without a leading slash). */
    PLAYER_COMMAND,
}

/**
 * Clean, typed item definition used for crate items, key items, reward display items, and bonus reward items.
 * Supports vanilla materials as well as `head:<player>`, `texture:<base64>`, `nexo:<id>`, `itemsadder:<id>`, and `hdb:<id>`.
 */
@Serializable
data class CrateItem(
    /** Material key or custom source prefix (`diamond`, `chest`, `head:Notch`, `texture:<b64>`, `nexo:<id>`, `itemsadder:<id>`, `hdb:<id>`). */
    val material: String = "chest",
    /** Stack count, from 1 to 99. */
    @Within(1, 99)
    val amount: Int = 1,
    /** Custom display name. Supports legacy `&` codes, hex (`&#RRGGBB`), CMI gradients and MiniMessage. */
    val name: String? = null,
    /** Lore lines shown on the item. */
    val lore: List<String> = [],
    /** Adds an enchantment glint without showing visible enchantments. */
    val glow: Boolean = false,
    /** Durability damage applied to damageable items. */
    @Within(0, 100000)
    val damage: Int = 0,
    /** Legacy integer custom model data (`0` omits it). */
    @SerialName("custom-model-data")
    @Within(0, Int.MAX_VALUE.toLong())
    val customModelData: Int = 0,
    /** Resource pack item model key, e.g. `mypack:crate_key`. */
    @SerialName("item-model")
    val itemModel: String? = null,
    /** Whether the item cannot lose durability. */
    val unbreakable: Boolean = false,
    /** Map of enchantment key (e.g. `sharpness`, `unbreaking`) to level. */
    val enchantments: Map<String, Int> = emptyMap(),
    /** Bukkit item flags to hide attributes, enchants, potion effects, etc. */
    val flags: List<String> = [],
    /** Leather armor or potion tint as `#RRGGBB`. */
    val color: String? = null,
    /** Custom potion effects applied to potions and tipped arrows. */
    @SerialName("potion-effects")
    val potionEffects: List<PotionEffectSpec> = [],
    /** Banner base dye color (e.g. `WHITE`, `RED`). */
    @SerialName("base-color")
    val baseColor: String? = null,
    /** Banner patterns rendered in order. */
    val patterns: List<BannerPatternSpec> = [],
    /** Firework star / rocket metadata if this item is a firework. */
    val firework: FireworkConfig? = null,
    /** Attribute modifiers applied to the item. */
    val attributes: List<AttributeSpec> = [],
    /** Optional raw SNBT / component data preserved during SpecializedCrates migration. */
    val nbt: String? = null,
)

/** One potion effect on an item or animation stage. */
@Serializable
data class PotionEffectSpec(
    /** Potion effect key (e.g. `speed`, `jump_boost`, `blindness`). */
    val effect: String,
    /** Effect duration in ticks (20 ticks = 1 second). */
    @Within(1, 1000000)
    val duration: Int = 200,
    /** Zero-based amplifier (`0` is level I, `1` is level II). */
    @Within(0, 255)
    val amplifier: Int = 0,
)

/** One banner layer pattern. */
@Serializable
data class BannerPatternSpec(
    /** Pattern key (e.g. `border`, `stripe_top`, `creeper`). */
    val pattern: String,
    /** Dye color name (e.g. `RED`, `GOLD`, `BLUE`). */
    val color: String = "WHITE",
)

/** One attribute modifier on an item. */
@Serializable
data class AttributeSpec(
    /** Attribute key (e.g. `attack_damage`, `max_health`). */
    val attribute: String,
    /** Modifier amount. */
    val amount: Double,
    /** Operation: `ADD_NUMBER`, `ADD_SCALAR`, or `MULTIPLY_SCALAR_1`. */
    val operation: String = "ADD_NUMBER",
    /** Optional equipment slot group (`HAND`, `OFF_HAND`, `HEAD`, `CHEST`, `LEGS`, `FEET`, `ANY`). */
    val slot: String? = null,
)

/** World display settings for a placed crate. */
@Serializable
data class CrateDisplayConfig(
    /** Whether the placed crate is a block or an entity. */
    val kind: CrateDisplayKind = CrateDisplayKind.BLOCK,
    /** Entity type name when `kind` is `ENTITY` (e.g. `ARMOR_STAND`, `VILLAGER`, `ENDER_CRYSTAL`). */
    val entity: String? = null,
    /** Optional custom name tag displayed on the entity. */
    @SerialName("entity-name")
    val entityName: String? = null,
)

/** Key item and interaction settings for a crate. */
@Serializable
data class KeyConfig(
    /** Whether a physical or virtual key is required to open this crate. */
    val required: Boolean = true,
    /** Physical key item definition. */
    val item: CrateItem = CrateItem(material = "tripwire_hook", name = "&eCrate Key", glow = true),
    /** Allows left-clicking in air or on a block while holding this key to open the reward preview menu. */
    @SerialName("left-click-preview")
    val leftClickPreview: Boolean = true,
    /** Optional commands executed when clicking while holding this key. */
    val commands: KeyClickCommands = KeyClickCommands(),
)

/** Commands bound to clicks while holding a crate key. Prefix with `player:` to run as the player instead of console. */
@Serializable
data class KeyClickCommands(
    @SerialName("left-click")
    val leftClick: List<String> = [],
    @SerialName("right-click")
    val rightClick: List<String> = [],
    @SerialName("shift-left-click")
    val shiftLeftClick: List<String> = [],
    @SerialName("shift-right-click")
    val shiftRightClick: List<String> = [],
)

/** Economy cost settings for opening a crate (requires Vault or VaultUnlocked). */
@Serializable
data class CostConfig(
    /** Money charged per opening (`0` disables economy cost). */
    @WithinRange(0.0, 1_000_000_000_000.0)
    val amount: Double = 0.0,
    /** Optional VaultUnlocked currency name; blank uses the default currency. */
    val currency: String = "",
    /** If true and `key.required` is true, players may pay with EITHER a key OR the economy cost instead of both. */
    @SerialName("key-or-economy")
    val keyOrEconomy: Boolean = false,
)

/** Per-crate or per-location cooldown settings. */
@Serializable
data class CooldownConfig(
    /** Time a player must wait between openings (`0s`, `30s`, `5m`, `1h 30m`). */
    val duration: String = "0s",
    /** Track the cooldown per placed crate location rather than globally per crate type. */
    @SerialName("per-location")
    val perLocation: Boolean = false,
    /** Optional permission node that bypasses this cooldown. */
    @SerialName("bypass-permission")
    val bypassPermission: String? = null,
)

/** Opening animation, skip, and inventory space behavior for a crate. */
@Serializable
data class OpeningConfig(
    /** Animation id: built-in (`instant`, `open-chest`, `roulette`, `csgo`, `menu`, `enclose`, `discover`, `discover-eula`) or a custom animation file from `animations/`. */
    val animation: String = "csgo",
    /** Inventory title used by inventory animations (supports `%crate%` and color codes). */
    @SerialName("inventory-title")
    val inventoryTitle: String = "&8&l> &d%crate%",
    /** Whether closing the animation inventory early skips straight to the reward. */
    @SerialName("allow-skip")
    val allowSkip: Boolean = true,
    /** Whether the animation inventory automatically closes after finishing. */
    @SerialName("auto-close")
    val autoClose: Boolean = true,
    /** Empty inventory slots required before opening (`-1` inherits `crates.required-slots` from `config.yml`). */
    @SerialName("required-slots")
    @Within(-1, 36)
    val requiredSlots: Int = -1,
    /** How full inventories are handled (`DENY`, `DROP`, or `REDEEM` via XaliRedeem). Null inherits `config.yml`. */
    @SerialName("full-inventory")
    val fullInventory: FullInventoryPolicy? = null,
)

/** Native Paper TextDisplay hologram settings above a placed crate. */
@Serializable
data class HologramConfig(
    /** Whether a hologram is displayed above placed instances of this crate. */
    val enabled: Boolean = true,
    /** Lines shown above the crate. Supports colors, MiniMessage and PlaceholderAPI. */
    val lines: List<String> = [],
    /** Vertical offset in blocks added to the global `holograms.offset`. */
    @WithinRange(-10.0, 20.0)
    val offset: Double = 0.0,
    /** Optional animation cycling prefixes across hologram lines. */
    val animation: HologramAnimationConfig = HologramAnimationConfig(),
    /** Temporary line shown above the crate after a reward is won (blank disables it). */
    @SerialName("reward-line")
    val rewardLine: String = "&a+ &f%reward% &a+",
    /** How long the temporary reward hologram stays visible (e.g. `3s`). */
    @SerialName("reward-duration")
    val rewardDuration: String = "3s",
    /** Extra vertical offset for the temporary reward hologram. */
    @SerialName("reward-offset")
    @WithinRange(-10.0, 20.0)
    val rewardOffset: Double = 0.3,
)

/** Animated text prefix cycling for crate holograms. */
@Serializable
data class HologramAnimationConfig(
    /** Animation style: `NONE`, `TEXT_CHANGE`, or `MULTI_LINE_CHANGE`. */
    val type: HologramAnimationType = HologramAnimationType.NONE,
    /** Ticks between animation frames. */
    @Within(1, 1200)
    val interval: Int = 20,
    /** Color/formatting prefixes cycled across the lines. */
    val prefixes: List<String> = [],
)

/** One particle emitter attached to an ambient crate or an opening burst. */
@Serializable
data class ParticleConfig(
    /** Minecraft particle key (e.g. `flame`, `enchant`, `dust`, `happy_villager`, `portal`). */
    val type: String = "flame",
    /** Geometric animation pattern around the crate. */
    val animation: ParticleAnimation = ParticleAnimation.NONE,
    /** Particles spawned per tick / burst. */
    @Within(1, 500)
    val amount: Int = 1,
    /** Particle speed / extra parameter. */
    @WithinRange(0.0, 10.0)
    val speed: Double = 0.05,
    /** Spread along X, Y and Z (also used as radius for animated patterns). */
    @SerialName("range-x")
    @WithinRange(0.0, 20.0)
    val rangeX: Double = 0.6,
    @SerialName("range-y")
    @WithinRange(0.0, 20.0)
    val rangeY: Double = 0.6,
    @SerialName("range-z")
    @WithinRange(0.0, 20.0)
    val rangeZ: Double = 0.6,
    /** Center offset relative to the crate block center. */
    @SerialName("offset-x")
    @WithinRange(-10.0, 10.0)
    val offsetX: Double = 0.0,
    @SerialName("offset-y")
    @WithinRange(-10.0, 10.0)
    val offsetY: Double = 0.5,
    @SerialName("offset-z")
    @WithinRange(-10.0, 10.0)
    val offsetZ: Double = 0.0,
    /** Optional hex color `#RRGGBB` for `dust` / colorable particles. */
    val color: String? = null,
)

/** One sound played on crate open or reward tier. */
@Serializable
data class SoundConfig(
    /** Minecraft sound key (e.g. `entity.player.levelup`, `block.chest.open`, `ui.button.click`). */
    val sound: String,
    /** Sound volume (`0.0` to `10.0`). */
    @WithinRange(0.0, 10.0)
    val volume: Float = 1.0f,
    /** Sound pitch (`0.0` to `2.0`). */
    @WithinRange(0.0, 2.0)
    val pitch: Float = 1.0f,
)

/** One firework launched on crate open or reward tier. */
@Serializable
data class FireworkConfig(
    /** Primary burst colors as `#RRGGBB` or Bukkit color names (e.g. `AQUA`, `#FF5500`). */
    val colors: List<String> = ["AQUA", "WHITE"],
    /** Fade colors as `#RRGGBB` or Bukkit color names. */
    @SerialName("fade-colors")
    val fadeColors: List<String> = [],
    /** Firework burst shape: `BALL`, `BALL_LARGE`, `STAR`, `BURST`, or `CREEPER`. */
    val shape: String = "STAR",
    /** Whether the burst flickers. */
    val flicker: Boolean = false,
    /** Whether the burst leaves a trail. */
    val trail: Boolean = true,
    /** Rocket flight power (`0` explodes almost immediately, `1`–`3` flies higher). */
    @Within(0, 4)
    val power: Int = 1,
)

/** One action executed when opening a crate. */
@Serializable
data class CrateActionConfig(
    /** What kind of action to execute. */
    val type: CrateActionType,
    /** Text or command payload. Placeholders: `%player%`, `%displayname%`, `%crate%`, `%cratename%`, `%key%`, `%reward%`, `%rarity%`. */
    val value: String,
)

/** Ambient effects played continuously around placed crates. */
@Serializable
data class AmbientEffectsConfig(
    /** Ambient particles displayed around placed crates. */
    val particles: List<ParticleConfig> = [],
)

/** Effects triggered when a crate opens (or overridden per reward rarity tier). */
@Serializable
data class OpenEffectsConfig(
    /** Actions executed on open (messages, broadcasts, titles, actionbars, commands). */
    val actions: List<CrateActionConfig> = [],
    /** Sounds played on open. */
    val sounds: List<SoundConfig> = [],
    /** Particle bursts spawned on open. */
    val particles: List<ParticleConfig> = [],
    /** Fireworks launched on open. */
    val fireworks: List<FireworkConfig> = [],
)

/** Per-rarity visual and messaging overrides. */
@Serializable
data class TierConfig(
    /** Optional formatted label for `%rarity%` placeholders (defaults to the tier key). */
    @SerialName("display-name")
    val displayName: String? = null,
    /** When true, this tier's effects replace the crate's default `effects` instead of adding to them. */
    @SerialName("override-defaults")
    val overrideDefaults: Boolean = true,
    /** Tier-specific actions. */
    val actions: List<CrateActionConfig> = [],
    /** Tier-specific sounds. */
    val sounds: List<SoundConfig> = [],
    /** Tier-specific particle bursts. */
    val particles: List<ParticleConfig> = [],
    /** Tier-specific fireworks. */
    val fireworks: List<FireworkConfig> = [],
)

/** Settings for mining-spawned lucky chests (`obtain: LUCKY_CHEST`). */
@Serializable
data class LuckyChestConfig(
    /** Spawn chance percentage per matching mined block (`0.0` to `100.0`, e.g. `1.0` = 1 in 100). */
    @WithinRange(0.0, 100.0)
    val chance: Double = 1.0,
    /** Whether `blocks` is a `WHITELIST` or a `BLACKLIST`. */
    @SerialName("block-filter")
    val blockFilter: BlockFilterMode = BlockFilterMode.WHITELIST,
    /** Material names matched when mining (empty allows any block when WHITELIST). */
    val blocks: List<String> = [],
    /** World names where this lucky chest may spawn (empty allows all worlds). */
    val worlds: List<String> = [],
    /** Whether players must hold `xalicrates.luckychest` (or `xalicrates.luckychest.<id>`) to trigger spawns. */
    @SerialName("require-permission")
    val requirePermission: Boolean = true,
    /** Optional per-crate despawn duration (e.g. `10m`); null inherits `lucky-chest.despawn-after` from `config.yml`. */
    @SerialName("despawn-after")
    val despawnAfter: String? = null,
    /** Give the crate item directly instead of replacing the mined block; null inherits `config.yml`. */
    @SerialName("give-item-instead")
    val giveItemInstead: Boolean? = null,
)

/** Custom XaliMenu menu bindings for a crate. */
@Serializable
data class CrateMenusConfig(
    /** XaliMenu menu name opened to preview this crate's rewards (defaults to `menus.preview` from `config.yml`). */
    val preview: String? = null,
    /** XaliMenu menu name opened when this crate is a multi-crate (defaults to `menus.crates` from `config.yml`). */
    @SerialName("multi-crate")
    val multiCrate: String? = null,
)

/** Turns a crate into a multi-crate hub that opens a XaliMenu selection menu listing child crates. */
@Serializable
data class MultiCrateConfig(
    /** Whether this crate acts as a multi-crate selector instead of rolling rewards directly. */
    val enabled: Boolean = false,
    /** Ordered list of crate ids exposed by this multi-crate (empty lists all enabled non-multi crates). */
    val crates: List<String> = [],
)

/** Controls giving the display item itself as part of a reward. */
@Serializable
data class GiveItemConfig(
    /** Whether to give the reward's `display-item` to the player. */
    val enabled: Boolean = false,
    /** Keep the `display-item`'s custom name on the given stack. */
    @SerialName("keep-name")
    val keepName: Boolean = true,
    /** Keep the `display-item`'s lore on the given stack. */
    @SerialName("keep-lore")
    val keepLore: Boolean = true,
)

/** Permission-gated fallback replacement for one-time or rank-locked rewards. */
@Serializable
data class FallbackRewardConfig(
    /** Permission node that triggers the fallback reward when the winning player already has it. */
    val permission: String,
    /** Reward id in the same crate to grant instead. */
    val reward: String,
    /** Optional permission that bypasses the fallback (defaults to `xalicrates.admin`). */
    @SerialName("bypass-permission")
    val bypassPermission: String = "xalicrates.admin",
)

/** Controls whether a reward can repeat within multi-reward openings. */
@Serializable
data class RepeatConfig(
    /** Percent chance (`0`–`100`) that this reward may be rolled again in the same multi-reward opening. */
    @WithinRange(0.0, 100.0)
    val chance: Double = 0.0,
    /** Maximum number of times this reward may be won in a single multi-reward opening (`0` = never repeat). */
    @Within(0, 100)
    val max: Int = 1,
)

/** Optional XaliRedeem integration settings on a specific reward. */
@Serializable
data class RewardRedeemConfig(
    /** Delivery policy: `AUTO` (overflow only), `ALWAYS` (always store in XaliRedeem), or `NEVER` (drop overflow). */
    val mode: RedeemDeliveryMode = RedeemDeliveryMode.AUTO,
    /** Optional static XaliRedeem voucher id to grant instead of storing an ad-hoc pending reward. */
    val voucher: String? = null,
    /** How many of `voucher` to grant when `voucher` is set. */
    @SerialName("voucher-amount")
    @Within(1, 10000)
    val voucherAmount: Int = 1,
)

/** One reward entry inside a crate's `rewards` map. */
@Serializable
data class RewardConfig(
    /** Relative roll weight (higher = more likely; probability is `weight / sum(non-fallback weights)`). */
    @WithinRange(0.0, 1_000_000.0)
    val weight: Double = 10.0,
    /** Rarity tier id matching a key in the crate's `tiers` map (`default` uses the crate's base effects). */
    val rarity: String = "default",
    /** If true, this reward is always granted in addition to the rolled reward(s). */
    val guaranteed: Boolean = false,
    /** If true, this reward is excluded from random rolls and only granted via another reward's `fallback`. */
    @SerialName("fallback-only")
    val fallbackOnly: Boolean = false,
    /** Item shown in preview menus and animations (and optionally given via `give-item`). */
    @SerialName("display-item")
    val displayItem: CrateItem = CrateItem(material = "diamond", name = "&bReward"),
    /** Whether and how to give `display-item` to the player. */
    @SerialName("give-item")
    val giveItem: GiveItemConfig = GiveItemConfig(),
    /** Extra physical items granted in addition to `display-item` / `commands`. */
    val items: List<CrateItem> = [],
    /** Console commands executed without a leading slash. Supports `{player}`, `%player%`, `%reward%`, and `%amountX-Y%`. */
    val commands: List<String> = [],
    /** Commands executed by the winning player as themselves. */
    @SerialName("player-commands")
    val playerCommands: List<String> = [],
    /** Other reward ids in this crate that are also granted whenever this reward is won. */
    @SerialName("linked-rewards")
    val linkedRewards: List<String> = [],
    /** Reward ids whose display items are packed inside this reward when `display-item` is a shulker box. */
    @SerialName("shulker-contents")
    val shulkerContents: List<String> = [],
    /** Optional permission-based fallback replacement. */
    val fallback: FallbackRewardConfig? = null,
    /** Controls duplicate prevention in multi-reward animations. */
    val repeat: RepeatConfig = RepeatConfig(),
    /** Optional XaliRedeem routing (works when XaliRedeem is installed). */
    val redeem: RewardRedeemConfig = RewardRedeemConfig(),
)

/**
 * Root configuration of a crate file (`plugins/XaliCrates/crates/<id>.yml`).
 * The file name without `.yml` is the crate's identifier.
 */
@Serializable
data class CrateConfig(
    /** Whether players can open, place, or preview this crate. */
    val enabled: Boolean = true,
    /** Display name shown in messages, menus and animations (defaults to `crate-item.name` or the crate id). */
    @SerialName("display-name")
    val displayName: String? = null,
    /** Optional permission required to open this crate. */
    val permission: String? = null,
    /** How placed instances of this crate behave (`STATIC`, `DYNAMIC`, or `LUCKY_CHEST`). */
    val obtain: ObtainMethod = ObtainMethod.STATIC,
    /** How placed instances appear in the world (block or entity). */
    val display: CrateDisplayConfig = CrateDisplayConfig(),
    /** Physical crate item given by `/xcrates givecrate` and shown in virtual crate menus. */
    @SerialName("crate-item")
    val crateItem: CrateItem = CrateItem(material = "chest", name = "&dCrate", glow = true),
    /** Key requirement, physical key item, and key click commands. */
    val key: KeyConfig = KeyConfig(),
    /** Optional Vault economy cost and key-or-economy rules. */
    val cost: CostConfig = CostConfig(),
    /** Cooldown rules per player or per crate location. */
    val cooldown: CooldownConfig = CooldownConfig(),
    /** Animation selection, skipping, and inventory space behavior. */
    val opening: OpeningConfig = OpeningConfig(),
    /** Hologram lines, animation, and temporary reward display. */
    val hologram: HologramConfig = HologramConfig(),
    /** Ambient particles displayed around placed crates. */
    val ambient: AmbientEffectsConfig = AmbientEffectsConfig(),
    /** Default effects played when this crate is opened. */
    val effects: OpenEffectsConfig = OpenEffectsConfig(),
    /** Per-rarity overrides for actions, sounds, particles, and fireworks. */
    val tiers: Map<String, TierConfig> = emptyMap(),
    /** Lucky chest spawn rules when `obtain` is `LUCKY_CHEST`. */
    @SerialName("lucky-chest")
    val luckyChest: LuckyChestConfig? = null,
    /** Custom XaliMenu menu bindings for previewing or multi-crate browsing. */
    val menus: CrateMenusConfig = CrateMenusConfig(),
    /** Multi-crate hub configuration. */
    @SerialName("multi-crate")
    val multiCrate: MultiCrateConfig? = null,
    /** All rewards belonging to this crate, keyed by reward id. */
    val rewards: Map<String, RewardConfig> = emptyMap(),
) {
    val isMultiCrate: Boolean get() = multiCrate?.enabled == true
}
