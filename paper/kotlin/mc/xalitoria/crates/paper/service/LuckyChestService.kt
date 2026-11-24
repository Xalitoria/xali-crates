package mc.xalitoria.crates.paper.service

import com.xalitoria.util.message.send
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.constants.Permissions
import mc.xalitoria.crates.paper.data.db.repository.BlockPos
import mc.xalitoria.crates.paper.data.model.BlockFilterMode
import mc.xalitoria.crates.paper.data.model.ObtainMethod
import net.xalitoria.crates.LuckyChestSpawnEvent
import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Handles mining-spawned lucky chests (`obtain: LUCKY_CHEST`), tracking player-placed blocks in memory so players
 * cannot place and re-break blocks to farm lucky chests.
 */
class LuckyChestService(private val plugin: XaliCrates) {
    private val playerPlacedBlocks = ConcurrentHashMap.newKeySet<BlockPos>()
    private val disabledPlayers = ConcurrentHashMap.newKeySet<UUID>()

    fun recordPlacedBlock(block: Block) {
        if (plugin.config.data.luckyChest.allowPlacedBlocks) return
        playerPlacedBlocks.add(BlockPos(block.world.name, block.x, block.y, block.z))
    }

    fun clearPlacedBlock(block: Block): Boolean =
        playerPlacedBlocks.remove(BlockPos(block.world.name, block.x, block.y, block.z))

    fun isEnabledFor(player: UUID): Boolean = player !in disabledPlayers

    fun togglePlayer(player: Player): Boolean {
        val nowEnabled = if (disabledPlayers.remove(player.uniqueId)) true else {
            disabledPlayers.add(player.uniqueId)
            false
        }
        player send plugin.messageConfig.data.lucky.toggle.get(nowEnabled)
        return nowEnabled
    }

    /** Called on [org.bukkit.event.block.BlockBreakEvent] (on the block/player region thread). */
    fun handleBlockBreak(player: Player, block: Block, random: Random = Random.Default): Boolean {
        val wasPlaced = clearPlacedBlock(block)
        if (wasPlaced && !plugin.config.data.luckyChest.allowPlacedBlocks) return false
        if (!isEnabledFor(player.uniqueId)) return false
        if (player.gameMode == GameMode.CREATIVE && !plugin.config.data.luckyChest.allowCreative) return false

        val blockMat = block.type.name
        val worldName = block.world.name

        for ([id, crate] in plugin.crates) {
            if (!crate.enabled || crate.obtain != ObtainMethod.LUCKY_CHEST) continue
            val lc = crate.luckyChest ?: continue
            if (lc.requirePermission && !player.hasPermission(Permissions.LUCKY_CHEST) && !player.hasPermission("${Permissions.LUCKY_CHEST}.$id")) {
                continue
            }
            if (lc.worlds.isNotEmpty() && lc.worlds.none { it.equals(worldName, ignoreCase = true) }) continue
            if (lc.blocks.isNotEmpty()) {
                val listed = lc.blocks.any { it.equals(blockMat, ignoreCase = true) }
                val allowed = if (lc.blockFilter == BlockFilterMode.WHITELIST) listed else !listed
                if (!allowed) continue
            }
            if (lc.chance <= 0.0 || random.nextDouble(0.0, 100.0) >= lc.chance) continue

            val event = LuckyChestSpawnEvent(player, id, block, block.location)
            plugin.server.pluginManager.callEvent(event)
            if (event.isCancelled) continue

            val giveItem = lc.giveItemInstead ?: plugin.config.data.luckyChest.giveItemInstead
            if (giveItem) {
                plugin.keys.givePhysicalCrates(player, id, 1)
            } else {
                plugin.placed.place(block.location, id, setBlock = true)
            }
            player send plugin.messageConfig.data.lucky.spawned(plugin.displayNameOf(id, crate))
            return !giveItem
        }
        return false
    }
}
