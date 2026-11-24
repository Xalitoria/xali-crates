package mc.xalitoria.crates.paper.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Inventory visibility change triggered by an animation stage. */
enum class StageInventoryChange {
    NONE,
    CLOSE,
    OPEN,
}

/** One item used as a filler or interactive button inside a custom stage-based animation. */
@Serializable
data class AnimationItem(
    /** Material key (e.g. `gray_stained_glass_pane`, `chest`, `ender_chest`). */
    val material: String = "gray_stained_glass_pane",
    /** Optional display name. */
    val name: String = " ",
    /** Optional lore lines. */
    val lore: List<String> = [],
    /** Adds an enchantment glint. */
    val glow: Boolean = false,
    /** When true, sets the stack size to the slot number (1-indexed), useful for numbered mystery chests. */
    @SerialName("amount-from-slot")
    val amountFromSlot: Boolean = false,
    /** Optional custom model data integer. */
    @SerialName("custom-model-data")
    @Within(0, Int.MAX_VALUE.toLong())
    val customModelData: Int = 0,
    /** Optional resource pack item model key. */
    @SerialName("item-model")
    val itemModel: String? = null,
)

/** Configuration for the `open-selected-reward` reveal stage in pick-a-chest animations. */
@Serializable
data class OpenedSelectedItemConfig(
    /** When true, selected slots immediately show the won reward without needing a click to reveal. */
    @SerialName("reveal-instantly")
    val revealInstantly: Boolean = false,
    /** Default mystery item shown before clicking to reveal (when `reveal-instantly` is false and rarity isn't matched). */
    val item: AnimationItem = AnimationItem(material = "white_stained_glass_pane", name = "&f&l???"),
    /** Optional per-rarity reveal items (keyed by reward rarity, e.g. `common`, `rare`, `legendary`). */
    @SerialName("by-rarity")
    val byRarity: Map<String, AnimationItem> = emptyMap(),
)

/** Replacement item for slots that the player did NOT select during a selection stage. */
@Serializable
data class UnselectedItemConfig(
    /** Use a random filler item from `fillers`. */
    @SerialName("random-filler")
    val randomFiller: Boolean = false,
    /** Specific filler symbol from `fillers` (overrides `item` when set). */
    val filler: String? = null,
    /** Explicit item shown in unselected slots (default AIR). */
    val item: AnimationItem = AnimationItem(material = "air", name = ""),
)

/** Symbols used inside stage grids to place dynamic rewards, fillers and interactive controls. */
@Serializable
data class AnimationSymbols(
    /** Empty slot symbol. */
    val air: String = "A",
    /** Replaced with a freshly rolled random reward on every frame. */
    @SerialName("random-reward")
    val randomReward: String = "R",
    /** Persistent reward symbols (e.g. `R1`, `R2`) that keep the same rolled reward until listed in `reset-rewards`. */
    @SerialName("reward-symbols")
    val rewardSymbols: List<String> = ["R1"],
    /** Replaced with a random entry from `fillers`. */
    @SerialName("random-filler")
    val randomFiller: String = "RF",
    /** Clickable mystery chest symbol; once `min-select` are picked, advances to the next stage. */
    @SerialName("select-reward")
    val selectReward: String = "RS",
    /** Displays the player's selected mystery slots for reveal or instant award. */
    @SerialName("open-selected-reward")
    val openSelectedReward: String = "OS",
    /** Clickable symbol that uncovers a random reward into the pool for `selected-reveal-reward`. */
    @SerialName("reveal-reward")
    val revealReward: String = "RR",
    /** Displays rewards uncovered via `reveal-reward` in arbitrary positions. */
    @SerialName("selected-reveal-reward")
    val selectedRevealReward: String = "SR",
    /** Clickable button that advances to the next stage (pair with `stay-ticks: -1`). */
    @SerialName("next-stage")
    val nextStage: String = "NS",
)

/** One stage (frame or loop) in a custom inventory animation. */
@Serializable
data class AnimationStage(
    /** Rows of comma-separated symbols (9 columns per row, e.g. `"F,F,F,R1,R,R1,F,F,F"`). Omit when repeating another stage. */
    val grid: List<String> = [],
    /** 1-based stage index to repeat (`0` means do not repeat another stage). */
    @SerialName("repeat-stage")
    @Within(0, 500)
    val repeatStage: Int = 0,
    /** How many times to play `repeat-stage`. */
    @SerialName("repeat-times")
    @Within(0, 500)
    val repeatTimes: Int = 0,
    /** Ticks this stage stays active (`0` uses `default-frame-ticks`, `-1` waits indefinitely for player interaction). */
    @SerialName("stay-ticks")
    @Within(-1, 12000)
    val stayTicks: Int = 0,
    /** Persistent reward symbols to re-roll when entering this stage. */
    @SerialName("reset-rewards")
    val resetRewards: List<String> = [],
    /** Winning slot coordinates as `"row,col"` (1-based) awarded when the animation completes on this stage. */
    @SerialName("reward-slots")
    val rewardSlots: List<String> = [],
    /** Optional sound overriding `sounds.stage`. */
    val sound: SoundConfig? = null,
    /** Optional potion effects applied to the viewer on this stage. */
    @SerialName("potion-effects")
    val potionEffects: List<PotionEffectSpec> = [],
    /** Temporarily close or reopen the animation inventory on this stage. */
    @SerialName("inventory-change")
    val inventoryChange: StageInventoryChange = StageInventoryChange.NONE,
    /** Plays the Totem of Undying visual effect on the viewer. */
    @SerialName("totem-effect")
    val totemEffect: Boolean = false,
)

/** Sounds played during a custom animation. */
@Serializable
data class AnimationSounds(
    /** Default sound played on every stage transition. */
    val stage: SoundConfig? = SoundConfig(sound = "ui.button.click", volume = 0.5f, pitch = 1.0f),
    /** Sound played when the player clicks an interactive slot. */
    val click: SoundConfig? = SoundConfig(sound = "entity.bat.takeoff", volume = 0.5f, pitch = 1.0f),
    /** Sound played when the animation ends. */
    val end: SoundConfig? = null,
)

/** Visual effects played when a custom animation completes. */
@Serializable
data class AnimationEndEffects(
    /** Particle bursts spawned at the player when the animation finishes. */
    val particles: List<ParticleConfig> = [],
)

/**
 * Root configuration of a custom stage-based crate animation (`plugins/XaliCrates/animations/<id>.yml`).
 */
@Serializable
data class AnimationConfig(
    /** Inventory size in slots (multiple of 9, from 9 to 54). */
    @SerialName("inventory-size")
    @Within(9, 54)
    val inventorySize: Int = 27,
    /** Default ticks each stage stays visible before advancing. */
    @SerialName("default-frame-ticks")
    @Within(1, 1200)
    val defaultFrameTicks: Int = 4,
    /** Ticks to keep the inventory open after the final stage before auto-closing. */
    @SerialName("auto-close-ticks")
    @Within(0, 1200)
    val autoCloseTicks: Int = 60,
    /** Minimum number of `select-reward` slots the player must pick before advancing. */
    @SerialName("min-select")
    @Within(1, 54)
    val minSelect: Int = 4,
    /** Symbol definitions used in `stages[].grid`. */
    val symbols: AnimationSymbols = AnimationSymbols(),
    /** Named filler items referenced by symbol in stage grids. */
    val fillers: Map<String, AnimationItem> = mapOf(
        "F" to AnimationItem(material = "gray_stained_glass_pane", name = " "),
    ),
    /** Unselected mystery chest item for `select-reward` slots. */
    @SerialName("closed-select-item")
    val closedSelectItem: AnimationItem = AnimationItem(
        material = "chest",
        name = "&6&l???",
        lore = ["&7Click to select this mystery chest."],
        amountFromSlot = true,
    ),
    /** Highlighted item shown after a player selects a `select-reward` slot. */
    @SerialName("selected-item")
    val selectedItem: AnimationItem = AnimationItem(
        material = "white_stained_glass_pane",
        name = "&f&lSelected",
        lore = ["&7You selected this mystery chest."],
        amountFromSlot = true,
    ),
    /** Reveal settings for `open-selected-reward` slots. */
    @SerialName("opened-selected-item")
    val openedSelectedItem: OpenedSelectedItemConfig = OpenedSelectedItemConfig(),
    /** Replacement for unselected `select-reward` slots after selection ends. */
    @SerialName("unselected-item")
    val unselectedItem: UnselectedItemConfig = UnselectedItemConfig(),
    /** Clickable item for `reveal-reward` slots. */
    @SerialName("reveal-item")
    val revealItem: AnimationItem = AnimationItem(
        material = "cyan_stained_glass_pane",
        name = "&bClick to reveal",
        glow = true,
    ),
    /** Clickable button for `next-stage` slots. */
    @SerialName("next-stage-item")
    val nextStageItem: AnimationItem = AnimationItem(
        material = "ender_chest",
        name = "&eContinue",
        glow = true,
    ),
    /** Ordered sequence of stages (1-indexed when referenced by `repeat-stage`). */
    val stages: List<AnimationStage> = [],
    /** Sound configuration for stages, clicks, and completion. */
    val sounds: AnimationSounds = AnimationSounds(),
    /** Particle effects played on animation completion. */
    @SerialName("end-effects")
    val endEffects: AnimationEndEffects = AnimationEndEffects(),
)
