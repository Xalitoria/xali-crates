package mc.xalitoria.crates.paper.data.config

import com.charleskorn.kaml.YamlComment
import com.xalitoria.util.config.DatabaseConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mc.xalitoria.crates.paper.data.model.FullInventoryPolicy
import mc.xalitoria.crates.paper.data.model.SoundConfig

/**
 * Main plugin configuration (`plugins/XaliCrates/config.yml`).
 * Consolidates global crate, key, lucky chest, animation, menu, redeem, hologram and web editor settings cleanly.
 */
@Serializable
data class MainConfig(
    val debug: Boolean = false,
    val language: String = "en-us",
    @YamlComment("Database for placed crates, virtual keys/crates, cooldowns and opening history.")
    val database: DatabaseConfig = DatabaseConfig(
        tablePrefix = "xalicrates_",
        sqlite = DatabaseConfig.Sqlite("xalicrates.db"),
        mysql = DatabaseConfig.Mysql(database = "xalicrates"),
    ),
    val keys: KeysSettings = KeysSettings(),
    val crates: CratesSettings = CratesSettings(),
    @SerialName("lucky-chest")
    val luckyChest: LuckyChestSettings = LuckyChestSettings(),
    val virtual: VirtualSettings = VirtualSettings(),
    val menus: MenusSettings = MenusSettings(),
    val redeem: RedeemSettings = RedeemSettings(),
    val holograms: HologramSettings = HologramSettings(),
    val particles: ParticleSettings = ParticleSettings(),
    val history: HistorySettings = HistorySettings(),
    val animations: BuiltInAnimationsConfig = BuiltInAnimationsConfig(),
    val editor: EditorSettings = EditorSettings(),
) {
    @Serializable
    data class KeysSettings(
        @SerialName("require-pdc-tag")
        @YamlComment("Require the xalicrates:key PDC tag on physical keys. Disable only when migrating legacy untracked keys.")
        val requirePdcTag: Boolean = true,
        @SerialName("require-lore")
        val requireLore: Boolean = true,
        @SerialName("require-enchants")
        val requireEnchants: Boolean = false,
        @SerialName("shift-click-open-all")
        @YamlComment("Allow shift-right-clicking a static crate to instantly open multiple keys.")
        val shiftClickOpenAll: Boolean = true,
        @SerialName("shift-click-confirm")
        val shiftClickConfirm: Boolean = true,
        @SerialName("max-shift-click-opening")
        val maxShiftClickOpening: Int = 100,
        @SerialName("virtual-on-full-inventory")
        @YamlComment("When giving physical keys to a full inventory, grant virtual keys instead of dropping/redeem.")
        val virtualOnFullInventory: Boolean = false,
        @SerialName("allow-left-click-interaction")
        val allowLeftClickInteraction: Boolean = false,
        @SerialName("prioritize-physical")
        @YamlComment("When a player holds both a physical key and virtual keys, consume the physical key first.")
        val prioritizePhysical: Boolean = true,
        @SerialName("allow-key-sending")
        val allowKeySending: Boolean = true,
    )

    @Serializable
    data class CratesSettings(
        @SerialName("open-in-hand")
        @YamlComment("Allow opening a physical crate item by right-clicking air.")
        val openInHand: Boolean = true,
        @SerialName("sneak-for-open-in-hand")
        val sneakForOpenInHand: Boolean = true,
        @SerialName("required-slots")
        @YamlComment("Default empty inventory slots required to open a crate when full-inventory is DENY.")
        val requiredSlots: Int = 1,
        @SerialName("full-inventory")
        @YamlComment("Default full-inventory handling: DENY, DROP, or REDEEM (stores overflow in XaliRedeem when installed).")
        val fullInventory: FullInventoryPolicy = FullInventoryPolicy.REDEEM,
        @SerialName("explosions-destroy-dynamic")
        val explosionsDestroyDynamic: Boolean = true,
        @SerialName("push-back")
        val pushBack: Boolean = true,
        @SerialName("push-back-sound")
        val pushBackSound: SoundConfig = SoundConfig("entity.villager.no", 1.0f, 1.0f),
        @SerialName("place-creative")
        val placeCreative: Boolean = true,
        @SerialName("open-creative")
        val openCreative: Boolean = true,
        @SerialName("place-effect")
        val placeEffect: Boolean = true,
        @SerialName("confirm-open")
        val confirmOpen: Boolean = false,
        @SerialName("confirm-timeout")
        val confirmTimeout: String = "3s",
        @SerialName("keep-block-consistent")
        val keepBlockConsistent: Boolean = true,
        @SerialName("max-concurrent-openings")
        val maxConcurrentOpenings: Int = 1000,
        @SerialName("title-fade-in")
        val titleFadeIn: String = "0s",
        @SerialName("title-stay")
        val titleStay: String = "4s",
        @SerialName("title-fade-out")
        val titleFadeOut: String = "1s",
    )

    @Serializable
    data class LuckyChestSettings(
        @SerialName("allow-creative")
        val allowCreative: Boolean = false,
        @SerialName("allow-placed-blocks")
        val allowPlacedBlocks: Boolean = false,
        @SerialName("despawn-after")
        @YamlComment("Duration after which un-opened lucky chests despawn (0s = never).")
        val despawnAfter: String = "0s",
        @SerialName("give-item-instead")
        val giveItemInstead: Boolean = false,
    )

    @Serializable
    data class VirtualSettings(
        @SerialName("require-virtual-crate-and-key")
        val requireVirtualCrateAndKey: Boolean = false,
        @SerialName("allow-key-deposit")
        val allowKeyDeposit: Boolean = true,
    )

    @Serializable
    data class MenusSettings(
        @SerialName("write-defaults")
        @YamlComment("Write default XaliMenu .conf files into plugins/XaliMenu/menus/ when missing.")
        val writeDefaults: Boolean = true,
        @YamlComment("XaliMenu menu opened by /crates.")
        val crates: String = "crates",
        @YamlComment("Default XaliMenu menu opened for reward previews (receives crate id as arg 0).")
        val preview: String = "crates-preview",
        @YamlComment("XaliMenu menu opened by /keys.")
        val keys: String = "crates-keys",
        @YamlComment("XaliMenu menu opened for crate history.")
        val history: String = "crates-history",
    )

    @Serializable
    data class RedeemSettings(
        @YamlComment("Use XaliRedeem (when installed) to store overflow items and rewards that aren't static vouchers.")
        val enabled: Boolean = true,
        @SerialName("overflow-title")
        val overflowTitle: String = "&d%crate% &7Overflow: &f%reward%",
        @SerialName("overflow-description")
        val overflowDescription: List<String> = ["&7Your inventory was full while opening &d%crate%&7."],
        @SerialName("notify-player")
        val notifyPlayer: Boolean = true,
    )

    @Serializable
    data class HologramSettings(
        @YamlComment("Global vertical offset in blocks for native TextDisplay crate holograms.")
        val offset: Double = 0.0,
        @SerialName("view-range")
        val viewRange: Float = 32.0f,
    )

    @Serializable
    data class ParticleSettings(
        val enabled: Boolean = true,
        @SerialName("view-distance")
        val viewDistance: Double = 25.0,
        @SerialName("tick-interval")
        val tickInterval: Int = 2,
    )

    @Serializable
    data class HistorySettings(
        val enabled: Boolean = true,
        @SerialName("max-per-player")
        val maxPerPlayer: Int = 500,
    )

    @Serializable
    data class BuiltInAnimationsConfig(
        @SerialName("open-chest")
        val openChest: OpenChestConfig = OpenChestConfig(),
        val roulette: RouletteConfig = RouletteConfig(),
        val csgo: CsgoConfig = CsgoConfig(),
        val menu: MenuAnimConfig = MenuAnimConfig(),
        val enclose: EncloseConfig = EncloseConfig(),
        val discover: DiscoverConfig = DiscoverConfig(),
    ) {
        @Serializable
        data class OpenChestConfig(
            @SerialName("open-duration-ticks")
            val openDurationTicks: Int = 60,
            @SerialName("early-reward-hologram")
            val earlyRewardHologram: Boolean = true,
            @SerialName("reward-hologram-delay-ticks")
            val rewardHologramDelayTicks: Int = 0,
            @SerialName("early-open-effects")
            val earlyOpenEffects: Boolean = false,
        )

        @Serializable
        data class RouletteConfig(
            @SerialName("tick-sound")
            val tickSound: SoundConfig = SoundConfig("block.iron_door.close", 0.5f, 1.5f),
            @SerialName("tile-update-ticks")
            val tileUpdateTicks: Int = 2,
            @SerialName("steps-per-speed")
            val stepsPerSpeed: Int = 3,
            @SerialName("final-tick-length")
            val finalTickLength: Int = 11,
            @SerialName("filler-blocks")
            val fillerBlocks: List<String> = ["black_stained_glass_pane", "white_stained_glass_pane", "gray_stained_glass_pane"],
        )

        @Serializable
        data class CsgoConfig(
            @SerialName("tick-sound")
            val tickSound: SoundConfig = SoundConfig("block.chest.locked", 0.5f, 1.5f),
            @SerialName("indicator-material")
            val indicatorMaterial: String = "redstone_torch",
            @SerialName("tile-update-ticks")
            val tileUpdateTicks: Int = 2,
            @SerialName("steps-per-speed")
            val stepsPerSpeed: Int = 3,
            @SerialName("final-tick-length")
            val finalTickLength: Int = 11,
            @SerialName("close-delay-ticks")
            val closeDelayTicks: Int = 40,
            @SerialName("filler-blocks")
            val fillerBlocks: List<String> = ["black_stained_glass_pane", "white_stained_glass_pane", "gray_stained_glass_pane"],
        )

        @Serializable
        data class MenuAnimConfig(
            val rows: Int = 3,
            @SerialName("min-rewards")
            val minRewards: Int = 1,
            @SerialName("max-rewards")
            val maxRewards: Int = 5,
        )

        @Serializable
        data class EncloseConfig(
            @SerialName("tick-sound")
            val tickSound: SoundConfig = SoundConfig("entity.player.levelup", 0.5f, 1.5f),
            @SerialName("fill-material")
            val fillMaterial: String = "magenta_stained_glass_pane",
            @SerialName("reward-amount")
            val rewardAmount: Int = 1,
            @SerialName("update-ticks")
            val updateTicks: Int = 2,
            val rows: Int = 3,
        )

        @Serializable
        data class DiscoverConfig(
            @SerialName("tick-sound")
            val tickSound: SoundConfig = SoundConfig("entity.item.pickup", 0.5f, 1.5f),
            @SerialName("click-sound")
            val clickSound: SoundConfig = SoundConfig("block.tripwire.click_on", 0.5f, 1.2f),
            @SerialName("uncover-sound")
            val uncoverSound: SoundConfig = SoundConfig("entity.player.levelup", 0.5f, 1.2f),
            val rows: Int = 3,
            @SerialName("min-rewards")
            val minRewards: Int = 1,
            @SerialName("max-rewards")
            val maxRewards: Int = 4,
            @SerialName("show-slot-count")
            val showSlotCount: Boolean = true,
            @SerialName("cover-material")
            val coverMaterial: String = "chest",
            @SerialName("cover-name")
            val coverName: String = "&aReward #%number%",
            @SerialName("cover-lore")
            val coverLore: String = "&7You have &f%remaining% &7picks left.",
            @SerialName("picked-material")
            val pickedMaterial: String = "lime_stained_glass",
            @SerialName("picked-name")
            val pickedName: String = "&aPicked! Uncover all to unlock",
            @SerialName("unlock-name")
            val unlockName: String = "&aClick to reveal your reward!",
        )
    }

    @Serializable
    data class EditorSettings(
        @YamlComment("Run a direct HTTP endpoint on this server for /xcrates web.")
        val enabled: Boolean = false,
        val host: String = "127.0.0.1",
        val port: Int = 8124,
        @SerialName("public-url")
        val publicUrl: String = "",
        val site: String = "https://crate.xali.to",
        @SerialName("session-minutes")
        val sessionMinutes: Int = 60,
        val relay: RelaySettings = RelaySettings(),
    ) {
        @Serializable
        data class RelaySettings(
            val url: String = "https://crate.xali.to/relay",
            val token: String = "",
        )
    }

    fun validate() {
        require(keys.maxShiftClickOpening >= 1) { "keys.max-shift-click-opening must be at least 1" }
        require(crates.requiredSlots in 0..36) { "crates.required-slots must be between 0 and 36" }
        require(crates.maxConcurrentOpenings >= 1) { "crates.max-concurrent-openings must be at least 1" }
        require(particles.tickInterval >= 1) { "particles.tick-interval must be at least 1" }
        require(editor.port in 1..65535) { "editor.port must be between 1 and 65535" }
        require(editor.sessionMinutes >= 1) { "editor.session-minutes must be at least 1" }
    }
}
