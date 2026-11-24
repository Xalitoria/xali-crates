package mc.xalitoria.crates.paper.listener

import com.xalitoria.util.bukkit.runConsoleCommand
import com.xalitoria.util.bukkit.runTask
import com.xalitoria.util.bukkit.runTaskLater
import com.xalitoria.util.message.send
import io.papermc.paper.event.player.AsyncChatEvent
import kotlinx.coroutines.launch
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.constants.Permissions
import mc.xalitoria.crates.paper.data.model.ObtainMethod
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameMode
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * All Folia-safe world, inventory, chat and connection listeners for XaliCrates.
 */
class CrateListeners(private val plugin: XaliCrates) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        plugin.scope.launch {
            runCatching {
                plugin.keys.load(uuid)
                plugin.openService.loadPlayer(uuid)
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        plugin.animations.handleQuit(player)
        plugin.editorGui.forget(player.uniqueId)
        plugin.keys.forget(player.uniqueId)
        plugin.openService.forgetPlayer(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!plugin.editorGui.hasChatPrompt(player.uniqueId)) return
        event.isCancelled = true
        val plain = PlainTextComponentSerializer.plainText().serialize(event.message())
        plugin.editorGui.handleChat(player, plain)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (plugin.animations.isAnimationInventory(event.inventory)) {
            plugin.animations.handleClick(event)
            return
        }
        if (plugin.editorGui.isEditorInventory(event.inventory)) {
            plugin.editorGui.handleClick(event)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (plugin.animations.isAnimationInventory(event.inventory) || plugin.editorGui.isEditorInventory(event.inventory)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        if (plugin.animations.isAnimationInventory(event.inventory)) {
            plugin.animations.handleClose(event)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand == EquipmentSlot.OFF_HAND) return
        val player = event.player
        val action = event.action
        val item = event.item

        // Clicked a placed crate block
        if ((action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) && event.clickedBlock != null) {
            val block = event.clickedBlock!!
            val placed = plugin.placed.at(block.location)
            if (placed != null) {
                event.isCancelled = true
                if (action == Action.RIGHT_CLICK_BLOCK) {
                    plugin.openService.open(
                        player = player,
                        crateId = placed.crateId,
                        placed = placed,
                        force = false,
                        skipAnimation = false,
                        virtual = false,
                        openAllShiftClick = player.isSneaking,
                    )
                } else {
                    val crate = plugin.crates[placed.crateId]
                    if (crate != null && !crate.isMultiCrate) {
                        plugin.menuHook.openPreview(player, placed.crateId)
                    }
                }
                return
            }
        }

        // Open physical crate in hand (right-click air)
        if (action == Action.RIGHT_CLICK_AIR && item != null && plugin.config.data.crates.openInHand) {
            if (!plugin.config.data.crates.sneakForOpenInHand || player.isSneaking) {
                val crateId = plugin.items.crateIdOf(item)
                if (crateId != null) {
                    event.isCancelled = true
                    val crate = plugin.crates[crateId] ?: return
                    if (crate.isMultiCrate) {
                        plugin.menuHook.openCratesHub(player, crateId)
                    } else if (plugin.openService.open(player, crateId, placed = null, force = false, skipAnimation = false, virtual = false)) {
                        if (item.amount <= 1) player.inventory.setItemInMainHand(null) else item.amount -= 1
                    }
                    return
                }
            }
        }

        // Physical key click actions & left-click preview
        if (item != null) {
            val keyCrateId = plugin.items.keyCrateIdOf(item)
            if (keyCrateId != null) {
                val crate = plugin.crates[keyCrateId] ?: return
                if (!plugin.config.data.keys.allowLeftClickInteraction || action != Action.LEFT_CLICK_BLOCK) {
                    event.isCancelled = true
                }
                if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
                    if (crate.key.leftClickPreview && !crate.isMultiCrate) {
                        plugin.menuHook.openPreview(player, keyCrateId)
                    }
                    val cmds = if (player.isSneaking) crate.key.commands.shiftLeftClick else crate.key.commands.leftClick
                    runKeyCommands(player, cmds)
                } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                    val cmds = if (player.isSneaking) crate.key.commands.shiftRightClick else crate.key.commands.rightClick
                    runKeyCommands(player, cmds)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (event.hand == EquipmentSlot.OFF_HAND) return
        val placed = plugin.placed.byEntity(event.rightClicked) ?: return
        event.isCancelled = true
        plugin.openService.open(
            player = event.player,
            crateId = placed.crateId,
            placed = placed,
            force = false,
            skipAnimation = false,
            virtual = false,
            openAllShiftClick = event.player.isSneaking,
        )
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityLeftClick(event: EntityDamageByEntityEvent) {
        val placed = plugin.placed.byEntity(event.entity) ?: return
        event.isCancelled = true
        val player = event.damager as? Player ?: return
        val crate = plugin.crates[placed.crateId] ?: return
        if (!crate.isMultiCrate) plugin.menuHook.openPreview(player, placed.crateId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val crateId = plugin.items.crateIdOf(event.itemInHand)
        if (crateId == null) {
            plugin.luckyChests.recordPlacedBlock(event.block)
            return
        }
        val crate = plugin.crates[crateId] ?: return
        val msg = plugin.messageConfig.data
        if (!crate.enabled) {
            event.isCancelled = true
            player send msg.crate.disabled
            return
        }
        if (!event.block.getRelative(BlockFace.UP).type.isAir) {
            event.isCancelled = true
            player send msg.crate.deniedPlace
            return
        }
        if (player.gameMode == GameMode.CREATIVE && !plugin.config.data.crates.placeCreative) {
            event.isCancelled = true
            player send msg.crate.denyCreative
            return
        }
        if (crate.obtain == ObtainMethod.STATIC &&
            !player.hasPermission(Permissions.ADMIN) &&
            !player.hasPermission(Permissions.PLACE_BYPASS)
        ) {
            event.isCancelled = true
            player send msg.crate.deniedPlaceStatic
            return
        }
        val placed = plugin.placed.place(event.block.location, crateId, setBlock = false) ?: return
        player send msg.crate.placedSuccess(plugin.displayNameOf(crateId, crate), "${event.block.x}, ${event.block.y}, ${event.block.z}")
        if (crate.obtain == ObtainMethod.DYNAMIC && !crate.key.required) {
            player.runTaskLater(plugin, 1L) {
                plugin.openService.open(player, crateId, placed, force = false, skipAnimation = false, virtual = false)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        val placed = plugin.placed.at(block.location)
        if (placed != null) {
            event.isCancelled = true
            return
        }
        if (plugin.luckyChests.handleBlockBreak(event.player, block)) {
            event.isCancelled = true
            event.isDropItems = false
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        val iter = event.blockList().iterator()
        while (iter.hasNext()) {
            val block = iter.next()
            val placed = plugin.placed.at(block.location) ?: continue
            val crate = plugin.crates[placed.crateId]
            if (crate != null && crate.obtain == ObtainMethod.DYNAMIC && plugin.config.data.crates.explosionsDestroyDynamic) {
                plugin.placed.removeAt(block.location, clearBlock = true)
            } else {
                iter.remove()
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val iter = event.blockList().iterator()
        while (iter.hasNext()) {
            val block = iter.next()
            val placed = plugin.placed.at(block.location) ?: continue
            val crate = plugin.crates[placed.crateId]
            if (crate != null && crate.obtain == ObtainMethod.DYNAMIC && plugin.config.data.crates.explosionsDestroyDynamic) {
                plugin.placed.removeAt(block.location, clearBlock = true)
            } else {
                iter.remove()
            }
        }
    }

    private fun runKeyCommands(player: Player, commands: List<String>) {
        for (raw in commands) {
            val expanded = raw.replace("%player%", player.name).replace("{player}", player.name)
            if (expanded.startsWith("player:", ignoreCase = true)) {
                val cmd = expanded.substringAfter(':').trim().removePrefix("/")
                player.runTask(plugin) { player.performCommand(cmd) }
            } else {
                plugin.runConsoleCommand(expanded.removePrefix("/"))
            }
        }
    }
}
