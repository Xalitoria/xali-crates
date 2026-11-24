package mc.xalitoria.crates.paper.gui.editor

import com.xalitoria.util.bukkit.runTask
import com.xalitoria.util.message.fail
import com.xalitoria.util.message.pass
import com.xalitoria.util.message.send
import com.xalitoria.util.message.toComponent
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.ObtainMethod
import mc.xalitoria.crates.paper.data.model.RedeemDeliveryMode
import mc.xalitoria.crates.paper.data.model.RewardConfig
import mc.xalitoria.crates.paper.data.model.Validation
import mc.xalitoria.crates.paper.service.CrateRegistry
import mc.xalitoria.crates.paper.service.RewardRoller
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hardcoded, **non-editable** in-game admin crate editor GUI.
 *
 * Unlike player-facing menus (which live in customizable XaliMenu `.conf` files), admin crate editor menus are fixed
 * programmatic inventories owned directly by XaliCrates so they cannot be altered or broken by menu configurations.
 */
class CrateEditorManager(private val plugin: XaliCrates) {
    private val chatPrompts = ConcurrentHashMap<UUID, (String) -> Unit>()

    sealed interface Screen : InventoryHolder {
        var inv: Inventory
        override fun getInventory(): Inventory = inv

        class CrateList(val page: Int) : Screen { override lateinit var inv: Inventory }
        class CrateDetail(val crateId: String) : Screen { override lateinit var inv: Inventory }
        class RewardsList(val crateId: String, val page: Int) : Screen { override lateinit var inv: Inventory }
        class RewardDetail(val crateId: String, val rewardId: String) : Screen { override lateinit var inv: Inventory }
    }

    fun isEditorInventory(inventory: Inventory?): Boolean = inventory?.holder is Screen

    fun hasChatPrompt(playerId: UUID): Boolean = chatPrompts.containsKey(playerId)

    fun handleChat(player: Player, message: String): Boolean {
        val handler = chatPrompts.remove(player.uniqueId) ?: return false
        player.runTask(plugin) {
            if (message.equals("cancel", ignoreCase = true)) {
                player send plugin.messageConfig.data.editor.promptCancelled
            } else {
                handler(message)
            }
        }
        return true
    }

    fun forget(playerId: UUID) {
        chatPrompts.remove(playerId)
    }

    fun openCrateList(player: Player, page: Int = 0) {
        val holder = Screen.CrateList(page)
        val inv = Bukkit.createInventory(holder, 54, "&8Crate Editor &7(Page ${page + 1})".toComponent())
        holder.inv = inv
        fillBorder(inv)

        val entries = plugin.crates.entries.sortedBy { it.key }
        val pageEntries = entries.drop(page * 28).take(28)
        for ([idx, entry] in pageEntries.withIndex()) {
            val slot = contentSlot(idx)
            val [id, crate] = entry
            val stack = plugin.items.buildCrateItem(id, 1) ?: ItemStack(Material.CHEST)
            stack.editMeta { meta ->
                meta.customName("&d$id &8(${if (crate.enabled) "&aON" else "&cOFF"}&8)".toComponent().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                meta.lore(
                    listOf(
                        "&7Display: &r${plugin.displayNameOf(id, crate)}",
                        "&7Obtain: &f${crate.obtain.name}",
                        "&7Animation: &f${crate.opening.animation}",
                        "&7Rewards: &f${crate.rewards.size}",
                        "&7Placed: &f${plugin.placed.countOf(id)}",
                        "",
                        "&eClick to edit this crate",
                    ).map { it.toComponent().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE) },
                )
            }
            inv.setItem(slot, stack)
        }

        if (page > 0) inv.setItem(45, button(Material.ARROW, "&aPrevious Page"))
        inv.setItem(47, button(Material.EMERALD, "&a+ Create New Crate", ["&7Prompts for a new crate id in chat"]))
        inv.setItem(49, button(Material.BARRIER, "&cClose"))
        inv.setItem(51, button(Material.COMPASS, "&bOpen Web Editor", ["&7Starts a /xcrates web session"]))
        if ((page + 1) * 28 < entries.size) inv.setItem(53, button(Material.ARROW, "&aNext Page"))

        player.openInventory(inv)
    }

    fun openCrateDetail(player: Player, crateId: String) {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: run { openCrateList(player); return }
        val holder = Screen.CrateDetail(id)
        val inv = Bukkit.createInventory(holder, 45, "&8Editing Crate: &d$id".toComponent())
        holder.inv = inv
        fillBorder(inv)

        inv.setItem(10, button(
            if (crate.enabled) Material.LIME_DYE else Material.GRAY_DYE,
            "&eEnabled: ${if (crate.enabled) "&aYES" else "&cNO"}",
            ["&7Click to toggle"],
        ))
        inv.setItem(11, button(
            Material.CHEST,
            "&eObtain Method: &f${crate.obtain.name}",
            ["&7Click to cycle STATIC / DYNAMIC / LUCKY_CHEST"],
        ))
        inv.setItem(12, button(
            Material.TRIPWIRE_HOOK,
            "&eRequire Key: ${if (crate.key.required) "&aYES" else "&cNO"}",
            ["&7Click to toggle"],
        ))
        inv.setItem(13, button(
            Material.NETHER_STAR,
            "&eAnimation: &f${crate.opening.animation}",
            ["&7Left-click to cycle built-ins", "&7Right-click to type custom animation id"],
        ))
        inv.setItem(14, button(
            Material.CLOCK,
            "&eCooldown: &f${crate.cooldown.duration}",
            ["&7Click to type new duration (e.g. 30s, 5m, 0s)"],
        ))
        inv.setItem(15, button(
            Material.GOLD_INGOT,
            "&eCost: &f${crate.cost.amount}",
            ["&7Click to type new Vault cost (0 = free)"],
        ))
        inv.setItem(16, button(
            Material.FEATHER,
            "&eAllow Skip Animation: ${if (crate.opening.allowSkip) "&aYES" else "&cNO"}",
            ["&7Click to toggle"],
        ))

        inv.setItem(19, button(
            Material.ITEM_FRAME,
            "&eSet Crate Item from Main Hand",
            ["&7Current: &f${crate.crateItem.material}", "&7Hold an item and click to apply"],
        ))
        inv.setItem(20, button(
            Material.NAME_TAG,
            "&eSet Key Item from Main Hand",
            ["&7Current: &f${crate.key.item.material}", "&7Hold an item and click to apply"],
        ))
        inv.setItem(21, button(
            Material.DIAMOND,
            "&bEdit Rewards &7(${crate.rewards.size})",
            ["&7View, edit, or drag-and-drop new rewards"],
        ))
        inv.setItem(22, button(
            Material.PAPER,
            "&eDisplay Name",
            ["&7Current: &r${plugin.displayNameOf(id, crate)}", "&7Click to type new display name"],
        ))
        inv.setItem(23, button(
            Material.SHIELD,
            "&ePermission",
            ["&7Current: &f${crate.permission ?: "none"}", "&7Click to type permission ('none' clears)"],
        ))
        inv.setItem(25, button(
            Material.TNT,
            "&cDelete Crate",
            ["&cShift-right-click to permanently delete"],
        ))

        inv.setItem(36, button(Material.ARROW, "&eBack to Crates"))
        inv.setItem(40, button(Material.BARRIER, "&cClose"))
        inv.setItem(44, button(Material.COMPASS, "&bEdit in Web Editor", ["&7Starts a /xcrates web $id session"]))

        player.openInventory(inv)
    }

    fun openRewardsList(player: Player, crateId: String, page: Int = 0) {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: run { openCrateList(player); return }
        val holder = Screen.RewardsList(id, page)
        val inv = Bukkit.createInventory(holder, 54, "&8Rewards: &d$id &7(Page ${page + 1})".toComponent())
        holder.inv = inv
        fillBorder(inv)

        val entries = crate.rewards.entries.toList()
        val pageEntries = entries.drop(page * 28).take(28)
        for ([idx, entry] in pageEntries.withIndex()) {
            val [rewardId, reward] = entry
            val pct = RewardRoller.formatPercent(RewardRoller.probabilityPercent(crate, rewardId))
            val stack = plugin.items.buildRewardPreviewItem(id, rewardId) ?: ItemStack(Material.DIAMOND)
            stack.editMeta { meta ->
                val existingLore = meta.lore()?.toMutableList() ?: mutableListOf()
                existingLore.add("".toComponent())
                existingLore.add("&8ID: &7$rewardId &8| Weight: &f${reward.weight} &8(&e$pct%&8)".toComponent().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                existingLore.add("&eClick to edit reward".toComponent().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                meta.lore(existingLore)
            }
            inv.setItem(contentSlot(idx), stack)
        }

        if (page > 0) inv.setItem(45, button(Material.ARROW, "&aPrevious Page"))
        inv.setItem(48, button(Material.ARROW, "&eBack to Crate"))
        inv.setItem(49, button(
            Material.HOPPER,
            "&aDrop Item Here to Add Reward",
            ["&7Click this slot while holding an item on your cursor", "&7to create a new reward directly from that item!"],
        ))
        inv.setItem(50, button(Material.BARRIER, "&cClose"))
        if ((page + 1) * 28 < entries.size) inv.setItem(53, button(Material.ARROW, "&aNext Page"))

        player.openInventory(inv)
    }

    fun openRewardDetail(player: Player, crateId: String, rewardId: String) {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: run { openCrateList(player); return }
        val reward = crate.rewards[rewardId] ?: run { openRewardsList(player, id); return }
        val holder = Screen.RewardDetail(id, rewardId)
        val inv = Bukkit.createInventory(holder, 45, "&8Reward: &d$rewardId".toComponent())
        holder.inv = inv
        fillBorder(inv)

        val pct = RewardRoller.formatPercent(RewardRoller.probabilityPercent(crate, rewardId))
        inv.setItem(4, plugin.items.buildRewardPreviewItem(id, rewardId) ?: ItemStack(Material.DIAMOND))

        inv.setItem(10, button(
            Material.PAPER,
            "&eWeight: &f${reward.weight} &7($pct%)",
            ["&7Left-click +5 | Right-click -5 | Shift-click to type"],
        ))
        inv.setItem(11, button(
            Material.AMETHYST_SHARD,
            "&eRarity: &f${reward.rarity}",
            ["&7Click to type rarity tier name"],
        ))
        inv.setItem(12, button(
            if (reward.giveItem.enabled) Material.LIME_DYE else Material.GRAY_DYE,
            "&eGive Display Item: ${if (reward.giveItem.enabled) "&aYES" else "&cNO"}",
            ["&7Click to toggle"],
        ))
        inv.setItem(13, button(
            if (reward.guaranteed) Material.LIME_DYE else Material.GRAY_DYE,
            "&eGuaranteed: ${if (reward.guaranteed) "&aYES" else "&cNO"}",
            ["&7Always awarded in addition to rolled rewards"],
        ))
        inv.setItem(14, button(
            if (reward.fallbackOnly) Material.LIME_DYE else Material.GRAY_DYE,
            "&eFallback Only: ${if (reward.fallbackOnly) "&aYES" else "&cNO"}",
            ["&7Excluded from random rolls"],
        ))
        inv.setItem(15, button(
            Material.CHEST_MINECART,
            "&eXaliRedeem Mode: &f${reward.redeem.mode.name}",
            ["&7Click to cycle AUTO / ALWAYS / NEVER"],
        ))
        inv.setItem(16, button(
            Material.COMMAND_BLOCK,
            "&eCommands &7(${reward.commands.size})",
            reward.commands.take(4).map { "&7/ &f$it" } + listOf("", "&aLeft-click to add command", "&cRight-click to clear commands"),
        ))
        inv.setItem(20, button(
            Material.ITEM_FRAME,
            "&eSet Display Item from Main Hand",
            ["&7Hold an item and click to update this reward's item"],
        ))
        inv.setItem(24, button(
            Material.TNT,
            "&cDelete Reward",
            ["&cShift-right-click to delete this reward"],
        ))

        inv.setItem(36, button(Material.ARROW, "&eBack to Rewards"))
        inv.setItem(40, button(Material.BARRIER, "&cClose"))

        player.openInventory(inv)
    }

    fun handleClick(event: InventoryClickEvent) {
        val screen = event.inventory.holder as? Screen ?: return
        val player = event.whoClicked as? Player ?: return

        // Allow picking up items in player's own bottom inventory only on RewardsList (for drag-and-drop onto slot 49)
        if (event.clickedInventory != event.view.topInventory) {
            if (screen !is Screen.RewardsList) event.isCancelled = true
            return
        }
        event.isCancelled = true
        val slot = event.slot

        when (screen) {
            is Screen.CrateList -> handleCrateListClick(player, screen, slot)
            is Screen.CrateDetail -> handleCrateDetailClick(player, screen, slot, event.click)
            is Screen.RewardsList -> handleRewardsListClick(player, screen, slot, event.cursor)
            is Screen.RewardDetail -> handleRewardDetailClick(player, screen, slot, event.click)
        }
    }

    private fun handleCrateListClick(player: Player, screen: Screen.CrateList, slot: Int) {
        when (slot) {
            45 -> if (screen.page > 0) openCrateList(player, screen.page - 1)
            47 -> prompt(player, "the new crate id (lowercase letters, digits, _ or -)") { input ->
                val id = input.trim().lowercase()
                if (!Validation.ID_PATTERN.matches(id)) {
                    player fail "Invalid crate id '$id'."
                } else if (id in plugin.crates) {
                    player fail "Crate '$id' already exists."
                } else {
                    saveCrateAndRefresh(player, id, CrateConfig(displayName = "&d$id"))
                    openCrateDetail(player, id)
                }
            }
            49 -> player.closeInventory()
            51 -> {
                player.closeInventory()
                plugin.startWebEditor(player, initialCrate = null, autoReload = true)
            }
            53 -> openCrateList(player, screen.page + 1)
            else -> {
                val idx = contentIndex(slot) ?: return
                val id = plugin.crates.keys.sorted().getOrNull(screen.page * 28 + idx) ?: return
                openCrateDetail(player, id)
            }
        }
    }

    private fun handleCrateDetailClick(player: Player, screen: Screen.CrateDetail, slot: Int, click: ClickType) {
        val id = screen.crateId
        val crate = plugin.crates[id] ?: return
        when (slot) {
            10 -> {
                saveCrateAndRefresh(player, id, crate.copy(enabled = !crate.enabled))
                openCrateDetail(player, id)
            }
            11 -> {
                val values = ObtainMethod.entries
                val next = values[(values.indexOf(crate.obtain) + 1) % values.size]
                saveCrateAndRefresh(player, id, crate.copy(obtain = next))
                openCrateDetail(player, id)
            }
            12 -> {
                saveCrateAndRefresh(player, id, crate.copy(key = crate.key.copy(required = !crate.key.required)))
                openCrateDetail(player, id)
            }
            13 -> {
                if (click.isRightClick) {
                    prompt(player, "the animation name (built-in or custom file id)") { input ->
                        saveCrateAndRefresh(player, id, crate.copy(opening = crate.opening.copy(animation = input.trim().lowercase())))
                        openCrateDetail(player, id)
                    }
                } else {
                    val builtins = Validation.BUILTIN_ANIMATIONS.toList()
                    val next = builtins[(builtins.indexOf(crate.opening.animation.lowercase()) + 1) % builtins.size]
                    saveCrateAndRefresh(player, id, crate.copy(opening = crate.opening.copy(animation = next)))
                    openCrateDetail(player, id)
                }
            }
            14 -> prompt(player, "the cooldown duration (e.g. 0s, 30s, 5m, 1h)") { input ->
                saveCrateAndRefresh(player, id, crate.copy(cooldown = crate.cooldown.copy(duration = input.trim())))
                openCrateDetail(player, id)
            }
            15 -> prompt(player, "the Vault cost number (0 for free)") { input ->
                val value = input.trim().toDoubleOrNull()?.coerceAtLeast(0.0)
                if (value == null) player fail "Not a valid number."
                else saveCrateAndRefresh(player, id, crate.copy(cost = crate.cost.copy(amount = value)))
                openCrateDetail(player, id)
            }
            16 -> {
                saveCrateAndRefresh(player, id, crate.copy(opening = crate.opening.copy(allowSkip = !crate.opening.allowSkip)))
                openCrateDetail(player, id)
            }
            19 -> {
                val held = player.inventory.itemInMainHand
                if (held.type.isAir) player fail "Hold an item in your main hand first."
                else saveCrateAndRefresh(player, id, crate.copy(crateItem = plugin.items.fromItemStack(held)))
                openCrateDetail(player, id)
            }
            20 -> {
                val held = player.inventory.itemInMainHand
                if (held.type.isAir) player fail "Hold an item in your main hand first."
                else saveCrateAndRefresh(player, id, crate.copy(key = crate.key.copy(item = plugin.items.fromItemStack(held))))
                openCrateDetail(player, id)
            }
            21 -> openRewardsList(player, id, 0)
            22 -> prompt(player, "the new display name") { input ->
                saveCrateAndRefresh(player, id, crate.copy(displayName = input))
                openCrateDetail(player, id)
            }
            23 -> prompt(player, "the permission node (or 'none' to clear)") { input ->
                val perm = input.trim().takeUnless { it.equals("none", ignoreCase = true) || it.isEmpty() }
                saveCrateAndRefresh(player, id, crate.copy(permission = perm))
                openCrateDetail(player, id)
            }
            25 -> if (click == ClickType.SHIFT_RIGHT) {
                CrateRegistry.deleteCrateFile(plugin.dataPath.resolve("crates"), id)
                plugin.placed.removeAllOf(id, clearBlock = false)
                plugin.reload()
                player pass "Deleted crate '$id'."
                openCrateList(player, 0)
            }
            36 -> openCrateList(player, 0)
            40 -> player.closeInventory()
            44 -> {
                player.closeInventory()
                plugin.startWebEditor(player, initialCrate = id, autoReload = true)
            }
        }
    }

    private fun handleRewardsListClick(player: Player, screen: Screen.RewardsList, slot: Int, cursor: ItemStack?) {
        val id = screen.crateId
        val crate = plugin.crates[id] ?: return
        when (slot) {
            45 -> if (screen.page > 0) openRewardsList(player, id, screen.page - 1)
            48 -> openCrateDetail(player, id)
            49 -> {
                if (cursor != null && !cursor.type.isAir) {
                    val base = cursor.type.key.key
                    var rewardId = base
                    var suffix = 2
                    while (rewardId in crate.rewards) rewardId = "${base}_${suffix++}"
                    val itemSpec = plugin.items.fromItemStack(cursor)
                    val newReward = RewardConfig(
                        weight = 10.0,
                        displayItem = itemSpec,
                        giveItem = mc.xalitoria.crates.paper.data.model.GiveItemConfig(enabled = true),
                    )
                    val updated = crate.copy(rewards = crate.rewards + (rewardId to newReward))
                    saveCrateAndRefresh(player, id, updated)
                    player pass "Added reward '$rewardId' to crate '$id'."
                    openRewardsList(player, id, screen.page)
                }
            }
            50 -> player.closeInventory()
            53 -> openRewardsList(player, id, screen.page + 1)
            else -> {
                val idx = contentIndex(slot) ?: return
                val rewardId = crate.rewards.keys.toList().getOrNull(screen.page * 28 + idx) ?: return
                openRewardDetail(player, id, rewardId)
            }
        }
    }

    private fun handleRewardDetailClick(player: Player, screen: Screen.RewardDetail, slot: Int, click: ClickType) {
        val id = screen.crateId
        val rewardId = screen.rewardId
        val crate = plugin.crates[id] ?: return
        val reward = crate.rewards[rewardId] ?: return

        fun updateReward(next: RewardConfig) {
            saveCrateAndRefresh(player, id, crate.copy(rewards = crate.rewards + (rewardId to next)))
            openRewardDetail(player, id, rewardId)
        }

        when (slot) {
            10 -> {
                if (click.isShiftClick) {
                    prompt(player, "the new numeric weight") { input ->
                        val w = input.trim().toDoubleOrNull()?.coerceAtLeast(0.0)
                        if (w == null) player fail "Not a valid number." else updateReward(reward.copy(weight = w))
                    }
                } else {
                    val delta = if (click.isLeftClick) 5.0 else -5.0
                    updateReward(reward.copy(weight = (reward.weight + delta).coerceAtLeast(0.0)))
                }
            }
            11 -> prompt(player, "the rarity tier id (e.g. default, rare, legendary)") { input ->
                updateReward(reward.copy(rarity = input.trim().lowercase().ifEmpty { "default" }))
            }
            12 -> updateReward(reward.copy(giveItem = reward.giveItem.copy(enabled = !reward.giveItem.enabled)))
            13 -> updateReward(reward.copy(guaranteed = !reward.guaranteed))
            14 -> updateReward(reward.copy(fallbackOnly = !reward.fallbackOnly))
            15 -> {
                val modes = RedeemDeliveryMode.entries
                val next = modes[(modes.indexOf(reward.redeem.mode) + 1) % modes.size]
                updateReward(reward.copy(redeem = reward.redeem.copy(mode = next)))
            }
            16 -> {
                if (click.isRightClick) {
                    updateReward(reward.copy(commands = []))
                } else {
                    prompt(player, "the console command to add (without leading /)") { input ->
                        updateReward(reward.copy(commands = reward.commands + input.trim().removePrefix("/")))
                    }
                }
            }
            20 -> {
                val held = player.inventory.itemInMainHand
                if (held.type.isAir) player fail "Hold an item in your main hand first."
                else updateReward(reward.copy(displayItem = plugin.items.fromItemStack(held)))
            }
            24 -> if (click == ClickType.SHIFT_RIGHT) {
                saveCrateAndRefresh(player, id, crate.copy(rewards = crate.rewards - rewardId))
                player pass "Deleted reward '$rewardId'."
                openRewardsList(player, id, 0)
            }
            36 -> openRewardsList(player, id, 0)
            40 -> player.closeInventory()
        }
    }

    private fun saveCrateAndRefresh(player: Player, id: String, updated: CrateConfig) {
        CrateRegistry.saveCrate(plugin.dataPath.resolve("crates"), id, updated)
        plugin.reload()
        player send plugin.messageConfig.data.editor.ingameSaved(id)
    }

    private fun prompt(player: Player, what: String, onAnswer: (String) -> Unit) {
        player.closeInventory()
        chatPrompts[player.uniqueId] = onAnswer
        player send plugin.messageConfig.data.editor.promptChat(what)
    }

    private fun fillBorder(inv: Inventory) {
        val pane = button(Material.GRAY_STAINED_GLASS_PANE, " ")
        val rows = inv.size / 9
        for (i in 0 until inv.size) {
            val r = i / 9
            val c = i % 9
            if (r == 0 || r == rows - 1 || c == 0 || c == 8) inv.setItem(i, pane)
        }
    }

    private fun contentSlot(index: Int): Int = ((index / 7) + 1) * 9 + (index % 7) + 1

    private fun contentIndex(slot: Int): Int? {
        val r = slot / 9
        val c = slot % 9
        if (r !in 1..4 || c !in 1..7) return null
        return (r - 1) * 7 + (c - 1)
    }

    private fun button(material: Material, name: String, lore: List<String> = []): ItemStack =
        ItemStack(material).apply {
            editMeta { meta ->
                meta.customName(name.toComponent().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                if (lore.isNotEmpty()) {
                    meta.lore(lore.map { it.toComponent().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE) })
                }
            }
        }
}
