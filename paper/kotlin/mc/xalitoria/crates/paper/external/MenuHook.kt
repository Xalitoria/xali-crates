package mc.xalitoria.crates.paper.external

import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.service.RewardRoller
import net.xalitoria.menu.Action
import net.xalitoria.menu.Catalog
import net.xalitoria.menu.Condition
import net.xalitoria.menu.ItemSource
import net.xalitoria.menu.MenuContext
import net.xalitoria.menu.XaliMenuApi
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * XaliMenu integration (required dependency).
 *
 * Registers catalogs, actions, conditions and an [ItemSource] so every player-facing crate menu (virtual crates hub,
 * multi-crates, reward previews, virtual keys, history) is a standard customizable XaliMenu `.conf` file.
 * Admin in-game crate editors remain hardcoded non-editable Bukkit inventories in `gui/editor/`.
 */
class MenuHook(private val plugin: XaliCrates) {
    private val api: XaliMenuApi = XaliMenuApi.get()

    init {
        api.registerCatalog(
            plugin,
            CATALOG_CRATES,
            CRATES_VALUES,
            mapOf("multi-crate" to "all-crates", "enabled-only" to "true", "owned-only" to "false"),
        ) { settings ->
            CratesCatalog(
                plugin = plugin,
                multiCrateId = settings.string("multi-crate", ""),
                enabledOnly = settings.boolean("enabled-only", true),
                ownedOnly = settings.boolean("owned-only", false),
            )
        }
        api.registerCatalog(
            plugin,
            CATALOG_REWARDS,
            REWARDS_VALUES,
            mapOf("crate" to "{crate}", "include-fallback" to "false"),
        ) { settings ->
            RewardsCatalog(
                plugin = plugin,
                crateExpr = settings.string("crate", "{crate}"),
                includeFallback = settings.boolean("include-fallback", false),
            )
        }
        api.registerCatalog(
            plugin,
            CATALOG_KEYS,
            KEYS_VALUES,
            mapOf("only-owned" to "false"),
        ) { settings ->
            KeysCatalog(plugin = plugin, onlyOwned = settings.boolean("only-owned", false))
        }
        api.registerCatalog(
            plugin,
            CATALOG_HISTORY,
            HISTORY_VALUES,
            mapOf("limit" to "28"),
        ) { settings ->
            HistoryCatalog(plugin = plugin, limit = settings.int("limit", 28))
        }

        api.registerAction(plugin, ACTION_OPEN) { settings ->
            OpenCrateAction(
                plugin = plugin,
                crateExpr = settings.requireString("crate"),
                skip = settings.boolean("skip-animation", false),
                virtual = settings.boolean("virtual", true),
            )
        }
        api.registerAction(plugin, ACTION_PREVIEW) { settings ->
            PreviewCrateAction(plugin, settings.requireString("crate"))
        }
        api.registerAction(plugin, ACTION_CLAIM_KEY) { settings ->
            ClaimKeyAction(plugin, settings.string("crate", ""), settings.int("amount", 1))
        }
        api.registerAction(plugin, ACTION_DEPOSIT_KEY) { settings ->
            DepositKeyAction(plugin, settings.string("crate", ""), settings.int("amount", 1))
        }
        api.registerAction(plugin, ACTION_TOGGLE_LUCKY) {
            ToggleLuckyAction(plugin)
        }

        api.registerCondition(plugin, COND_HAS_KEY) { settings ->
            HasKeyCondition(
                plugin = plugin,
                crateExpr = settings.requireString("crate"),
                amount = settings.int("amount", 1),
                virtualOnly = settings.boolean("virtual-only", false),
                physicalOnly = settings.boolean("physical-only", false),
            )
        }
        api.registerCondition(plugin, COND_CAN_OPEN) { settings ->
            CanOpenCondition(plugin, settings.requireString("crate"))
        }
        api.registerCondition(plugin, COND_ON_COOLDOWN) { settings ->
            OnCooldownCondition(plugin, settings.requireString("crate"))
        }

        api.registerItemSource(plugin, CratesItemSource(plugin))
    }

    val loadedMenus: Set<String> get() = api.menus

    fun hasMenu(name: String): Boolean = name in api.menus

    fun open(player: Player, menu: String, args: List<String> = []): Boolean {
        if (menu !in api.menus) return false
        if (args.isEmpty()) api.open(player, menu) else api.open(player, menu, args)
        return true
    }

    fun openCratesHub(player: Player, multiCrateId: String? = null): Boolean {
        val crateMenu = multiCrateId?.let { plugin.crates[it.lowercase()]?.menus?.multiCrate }
        val menuName = crateMenu?.takeIf { it.isNotBlank() } ?: plugin.config.data.menus.crates
        return if (multiCrateId != null) open(player, menuName, [multiCrateId]) else open(player, menuName)
    }

    fun openPreview(player: Player, crateId: String): Boolean {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: return false
        val menuName = crate.menus.preview?.takeIf { it.isNotBlank() } ?: plugin.config.data.menus.preview
        return open(player, menuName, [id])
    }

    fun openKeys(player: Player): Boolean = open(player, plugin.config.data.menus.keys)

    fun openHistory(player: Player): Boolean = open(player, plugin.config.data.menus.history)

    /** Writes default XaliMenu `.conf` menus into `plugins/XaliMenu/menus/` where missing. */
    fun writeDefaultMenus(): List<String> {
        val dir = plugin.server.pluginManager.getPlugin("XaliMenu")?.dataPath?.resolve("menus") ?: return []
        dir.createDirectories()
        val cfg = plugin.config.data.menus
        val mapping = listOf(
            cfg.crates to "/menu/crates.conf",
            cfg.preview to "/menu/crates-preview.conf",
            cfg.keys to "/menu/crates-keys.conf",
            cfg.history to "/menu/crates-history.conf",
        )
        val written = mutableListOf<String>()
        for ([menuName, resource] in mapping) {
            val file = dir.resolve("$menuName.conf")
            if (file.exists()) continue
            val text = MenuHook::class.java.getResourceAsStream(resource)?.use { String(it.readAllBytes(), Charsets.UTF_8) } ?: continue
            file.writeText(text)
            written.add(menuName)
        }
        return written
    }

    private class CratesCatalog(
        private val plugin: XaliCrates,
        private val multiCrateId: String,
        private val enabledOnly: Boolean,
        private val ownedOnly: Boolean,
    ) : Catalog {
        override fun elements(ctx: MenuContext): List<Map<String, String>> {
            val resolvedMulti = ctx.parse(multiCrateId).lowercase()
            val ids = if (resolvedMulti.isNotBlank()) {
                plugin.crates[resolvedMulti]?.multiCrate?.crates?.map { it.lowercase() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: plugin.crates.entries.filter { !it.value.isMultiCrate }.map { it.key }
            } else {
                plugin.crates.entries.filter { !it.value.isMultiCrate }.map { it.key }
            }
            return ids.mapNotNull { id ->
                val crate = plugin.crates[id] ?: return@mapNotNull null
                if (enabledOnly && !crate.enabled) return@mapNotNull null
                val balance = plugin.keys.cachedBalance(ctx.player.uniqueId, id)
                val physical = plugin.keys.countPhysicalKeys(ctx.player, id)
                val totalKeys = balance.keys + physical
                if (ownedOnly && totalKeys <= 0 && balance.crates <= 0) return@mapNotNull null
                val cooldownSec = plugin.openService.remainingCooldownSeconds(ctx.player, id, null)
                mapOf(
                    "id" to id,
                    "name" to plugin.displayNameOf(id, crate),
                    "material" to "xalicrates:crate/$id",
                    "keys" to balance.keys.toString(),
                    "physical_keys" to physical.toString(),
                    "total_keys" to totalKeys.toString(),
                    "crates" to balance.crates.toString(),
                    "cost" to crate.cost.amount.toString(),
                    "cooldown" to cooldownSec.toString(),
                    "can_open" to plugin.openService.canOpenNow(ctx.player, id).toString(),
                    "reward_count" to crate.rewards.count { !it.value.fallbackOnly }.toString(),
                )
            }
        }
    }

    private class RewardsCatalog(
        private val plugin: XaliCrates,
        private val crateExpr: String,
        private val includeFallback: Boolean,
    ) : Catalog {
        override fun elements(ctx: MenuContext): List<Map<String, String>> {
            val crateId = ctx.parse(crateExpr).lowercase()
            val crate = plugin.crates[crateId] ?: return []
            return crate.rewards.entries
                .filter { includeFallback || !it.value.fallbackOnly }
                .map { [rewardId, reward] ->
                    val pct = RewardRoller.probabilityPercent(crate, rewardId)
                    mapOf(
                        "id" to rewardId,
                        "crate" to crateId,
                        "name" to (reward.displayItem.name ?: rewardId),
                        "material" to "xalicrates:reward/$crateId/$rewardId",
                        "chance" to RewardRoller.formatPercent(pct),
                        "weight" to reward.weight.toString(),
                        "rarity" to reward.rarity,
                        "guaranteed" to reward.guaranteed.toString(),
                    )
                }
        }
    }

    private class KeysCatalog(
        private val plugin: XaliCrates,
        private val onlyOwned: Boolean,
    ) : Catalog {
        override fun elements(ctx: MenuContext): List<Map<String, String>> =
            plugin.crates.entries
                .filter { it.value.enabled && !it.value.isMultiCrate }
                .mapNotNull { [id, crate] ->
                    val bal = plugin.keys.cachedBalance(ctx.player.uniqueId, id)
                    val physical = plugin.keys.countPhysicalKeys(ctx.player, id)
                    if (onlyOwned && bal.keys <= 0 && physical <= 0 && bal.crates <= 0) return@mapNotNull null
                    mapOf(
                        "id" to id,
                        "name" to plugin.displayNameOf(id, crate),
                        "material" to "xalicrates:key/$id",
                        "keys" to bal.keys.toString(),
                        "physical_keys" to physical.toString(),
                        "crates" to bal.crates.toString(),
                    )
                }
    }

    private class HistoryCatalog(private val plugin: XaliCrates, private val limit: Int) : Catalog {
        private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        override fun elements(ctx: MenuContext): List<Map<String, String>> =
            plugin.openService.cachedHistory(ctx.player.uniqueId).take(limit).map { entry ->
                mapOf(
                    "crate" to entry.crateId,
                    "rewards" to entry.rewards.joinToString(", "),
                    "time" to formatter.format(Instant.ofEpochMilli(entry.openedAt)),
                    "virtual" to entry.virtual.toString(),
                    "material" to "xalicrates:crate/${entry.crateId}",
                )
            }
    }

    private class OpenCrateAction(
        private val plugin: XaliCrates,
        private val crateExpr: String,
        private val skip: Boolean,
        private val virtual: Boolean,
    ) : Action {
        override fun run(ctx: MenuContext) {
            val id = ctx.parse(crateExpr).lowercase()
            ctx.player.closeInventory()
            plugin.openService.open(ctx.player, id, placed = null, force = false, skipAnimation = skip, virtual = virtual)
        }
    }

    private class PreviewCrateAction(private val plugin: XaliCrates, private val crateExpr: String) : Action {
        override fun run(ctx: MenuContext) {
            plugin.menuHook.openPreview(ctx.player, ctx.parse(crateExpr))
        }
    }

    private class ClaimKeyAction(private val plugin: XaliCrates, private val crateExpr: String, private val amount: Int) : Action {
        override fun run(ctx: MenuContext) {
            val id = ctx.parse(crateExpr).lowercase()
            plugin.keys.claimKey(ctx.player, id, amount) { ctx.refresh() }
        }
    }

    private class DepositKeyAction(private val plugin: XaliCrates, private val crateExpr: String, private val amount: Int) : Action {
        override fun run(ctx: MenuContext) {
            val id = ctx.parse(crateExpr).lowercase()
            plugin.keys.depositKey(ctx.player, id, amount) { ctx.refresh() }
        }
    }

    private class ToggleLuckyAction(private val plugin: XaliCrates) : Action {
        override fun run(ctx: MenuContext) {
            plugin.luckyChests.togglePlayer(ctx.player)
            ctx.refresh()
        }
    }

    private class HasKeyCondition(
        private val plugin: XaliCrates,
        private val crateExpr: String,
        private val amount: Int,
        private val virtualOnly: Boolean,
        private val physicalOnly: Boolean,
    ) : Condition {
        override fun test(ctx: MenuContext): Boolean {
            val id = ctx.parse(crateExpr).lowercase()
            val virtual = if (physicalOnly) 0 else plugin.keys.cachedBalance(ctx.player.uniqueId, id).keys
            val physical = if (virtualOnly) 0 else plugin.keys.countPhysicalKeys(ctx.player, id)
            return (virtual + physical) >= amount
        }
    }

    private class CanOpenCondition(private val plugin: XaliCrates, private val crateExpr: String) : Condition {
        override fun test(ctx: MenuContext): Boolean = plugin.openService.canOpenNow(ctx.player, ctx.parse(crateExpr).lowercase())
    }

    private class OnCooldownCondition(private val plugin: XaliCrates, private val crateExpr: String) : Condition {
        override fun test(ctx: MenuContext): Boolean =
            plugin.openService.remainingCooldownSeconds(ctx.player, ctx.parse(crateExpr).lowercase(), null) > 0L
    }

    /**
     * Resolves `xalicrates:crate/<id>`, `xalicrates:key/<id>`, and `xalicrates:reward/<crate>/<reward>` to exact ItemStacks.
     */
    private class CratesItemSource(private val plugin: XaliCrates) : ItemSource {
        override val prefix: String = "xalicrates"

        override fun item(id: String): ItemStack? {
            val parts = id.split('/')
            return when {
                parts.size == 2 && parts[0] == "crate" -> plugin.items.buildCrateItem(parts[1].lowercase(), 1)
                parts.size == 2 && parts[0] == "key" -> plugin.items.buildKeyItem(parts[1].lowercase(), 1)
                parts.size == 3 && parts[0] == "reward" -> plugin.items.buildRewardPreviewItem(parts[1].lowercase(), parts[2])
                else -> null
            }
        }
    }

    companion object {
        const val CATALOG_CRATES = "xalicrates:crates"
        const val CATALOG_REWARDS = "xalicrates:rewards"
        const val CATALOG_KEYS = "xalicrates:keys"
        const val CATALOG_HISTORY = "xalicrates:history"

        const val ACTION_OPEN = "xalicrates:open"
        const val ACTION_PREVIEW = "xalicrates:preview"
        const val ACTION_CLAIM_KEY = "xalicrates:claim-key"
        const val ACTION_DEPOSIT_KEY = "xalicrates:deposit-key"
        const val ACTION_TOGGLE_LUCKY = "xalicrates:toggle-lucky"

        const val COND_HAS_KEY = "xalicrates:has-key"
        const val COND_CAN_OPEN = "xalicrates:can-open"
        const val COND_ON_COOLDOWN = "xalicrates:on-cooldown"

        private val CRATES_VALUES = mapOf(
            "id" to "starter",
            "name" to "Starter Crate",
            "material" to "chest",
            "keys" to "3",
            "physical_keys" to "1",
            "total_keys" to "4",
            "crates" to "0",
            "cost" to "0.0",
            "cooldown" to "0",
            "can_open" to "true",
            "reward_count" to "8",
        )

        private val REWARDS_VALUES = mapOf(
            "id" to "diamonds",
            "crate" to "starter",
            "name" to "3x Diamonds",
            "material" to "diamond",
            "chance" to "25",
            "weight" to "30.0",
            "rarity" to "rare",
            "guaranteed" to "false",
        )

        private val KEYS_VALUES = mapOf(
            "id" to "starter",
            "name" to "Starter Crate",
            "material" to "tripwire_hook",
            "keys" to "3",
            "physical_keys" to "1",
            "crates" to "0",
        )

        private val HISTORY_VALUES = mapOf(
            "crate" to "starter",
            "rewards" to "3x Diamonds",
            "time" to "2026-11-23 12:00",
            "virtual" to "true",
            "material" to "chest",
        )
    }
}
