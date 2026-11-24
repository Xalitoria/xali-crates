package mc.xalitoria.crates.paper.service

import com.xalitoria.util.bukkit.runTask
import com.xalitoria.util.message.fail
import com.xalitoria.util.message.send
import kotlinx.coroutines.launch
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.db.repository.VirtualBalance
import mc.xalitoria.crates.paper.data.db.repository.VirtualBalanceRepository
import mc.xalitoria.crates.paper.data.model.FullInventoryPolicy
import net.xalitoria.crates.KeyGiveEvent
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages physical and virtual crate keys and virtual crates, with an online memory cache backed by atomic DB queries.
 */
class KeyService(private val plugin: XaliCrates) {
    private val cache = ConcurrentHashMap<UUID, Map<String, VirtualBalance>>()

    suspend fun load(player: UUID) {
        val data = VirtualBalanceRepository.of(player)
        if (plugin.server.getPlayer(player) != null) cache[player] = data
    }

    fun refresh(player: UUID) {
        if (plugin.server.getPlayer(player) != null) {
            plugin.scope.launch { runCatching { load(player) } }
        }
    }

    fun forget(player: UUID) {
        cache.remove(player)
    }

    fun cachedAll(player: UUID): Map<String, VirtualBalance> = cache[player].orEmpty()

    fun cachedBalance(player: UUID, crateId: String): VirtualBalance = cache[player]?.get(crateId.lowercase()) ?: VirtualBalance.ZERO

    fun totalVirtualKeys(player: UUID): Int = cache[player]?.values?.sumOf { it.keys } ?: 0

    fun countPhysicalKeys(player: Player, crateId: String): Int {
        val id = crateId.lowercase()
        var total = 0
        for (stack in player.inventory.storageContents) {
            if (stack != null && plugin.items.keyCrateIdOf(stack) == id) total += stack.amount
        }
        return total
    }

    fun hasAnyKey(player: Player, crateId: String): Boolean =
        countPhysicalKeys(player, crateId) > 0 || cachedBalance(player.uniqueId, crateId).keys > 0

    /** Consumes 1 physical key from [player]'s inventory (preferring main hand); returns true if taken. */
    fun consumePhysicalKey(player: Player, crateId: String): Boolean {
        val id = crateId.lowercase()
        val mainHand = player.inventory.itemInMainHand
        if (plugin.items.keyCrateIdOf(mainHand) == id) {
            if (mainHand.amount <= 1) player.inventory.setItemInMainHand(null) else mainHand.amount -= 1
            return true
        }
        val contents = player.inventory.storageContents
        for (i in contents.indices) {
            val stack = contents[i] ?: continue
            if (plugin.items.keyCrateIdOf(stack) == id) {
                if (stack.amount <= 1) player.inventory.setItem(i, null) else stack.amount -= 1
                return true
            }
        }
        return false
    }

    /** Gives physical keys to [player], respecting `keys.virtual-on-full-inventory` and XaliRedeem overflow. */
    fun givePhysicalKeys(player: Player, crateId: String, amount: Int): Boolean {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: return false
        val stack = plugin.items.buildKeyItem(id, amount) ?: return false
        val overflow = player.inventory.addItem(stack).values.toList()
        if (overflow.isNotEmpty()) {
            val overflowCount = overflow.sumOf { it.amount }
            val redeem = plugin.redeemHook
            when {
                plugin.config.data.keys.virtualOnFullInventory -> {
                    plugin.scope.launch {
                        VirtualBalanceRepository.giveKeys(player.uniqueId, id, overflowCount)
                        refresh(player.uniqueId)
                    }
                }
                redeem != null && plugin.config.data.redeem.enabled && plugin.config.data.crates.fullInventory == FullInventoryPolicy.REDEEM -> {
                    redeem.storeOverflow(
                        player = player.uniqueId,
                        crateId = id,
                        crateName = plugin.displayNameOf(id, crate),
                        rewardName = crate.key.item.name ?: "$id Key",
                        items = overflow,
                        icon = overflow.first(),
                    )
                }
                else -> overflow.forEach { player.world.dropItemNaturally(player.location, it) }
            }
        }
        plugin.server.pluginManager.callEvent(KeyGiveEvent(player.uniqueId, id, amount, virtual = false))
        return true
    }

    fun givePhysicalCrates(player: Player, crateId: String, amount: Int): Boolean {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: return false
        val stack = plugin.items.buildCrateItem(id, amount) ?: return false
        val overflow = player.inventory.addItem(stack).values.toList()
        if (overflow.isNotEmpty()) {
            val redeem = plugin.redeemHook
            if (redeem != null && plugin.config.data.redeem.enabled && plugin.config.data.crates.fullInventory == FullInventoryPolicy.REDEEM) {
                redeem.storeOverflow(
                    player = player.uniqueId,
                    crateId = id,
                    crateName = plugin.displayNameOf(id, crate),
                    rewardName = plugin.displayNameOf(id, crate),
                    items = overflow,
                    icon = overflow.first(),
                )
            } else {
                overflow.forEach { player.world.dropItemNaturally(player.location, it) }
            }
        }
        return true
    }

    suspend fun giveVirtualKeys(player: UUID, crateId: String, amount: Int): Int {
        val id = crateId.lowercase()
        val total = VirtualBalanceRepository.giveKeys(player, id, amount)
        refresh(player)
        plugin.runTask {
            plugin.server.pluginManager.callEvent(KeyGiveEvent(player, id, amount, virtual = true))
        }
        return total
    }

    suspend fun takeVirtualKeys(player: UUID, crateId: String, amount: Int): Int {
        val id = crateId.lowercase()
        val taken = VirtualBalanceRepository.takeKeys(player, id, amount)
        refresh(player)
        return taken
    }

    suspend fun giveVirtualCrates(player: UUID, crateId: String, amount: Int): Int {
        val id = crateId.lowercase()
        val total = VirtualBalanceRepository.giveCrates(player, id, amount)
        refresh(player)
        return total
    }

    suspend fun takeVirtualCrates(player: UUID, crateId: String, amount: Int): Int {
        val id = crateId.lowercase()
        val taken = VirtualBalanceRepository.takeCrates(player, id, amount)
        refresh(player)
        return taken
    }

    /** Withdraws [amount] virtual keys into physical key items. */
    fun claimKey(player: Player, crateId: String, amount: Int, after: Runnable? = null) {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: run {
            player fail plugin.messageConfig.data.commands.unknownCrate(crateId)
            after?.run()
            return
        }
        val msg = plugin.messageConfig.data
        plugin.scope.launch {
            val taken = VirtualBalanceRepository.takeKeys(player.uniqueId, id, amount.coerceAtLeast(1))
            refresh(player.uniqueId)
            player.runTask(plugin) {
                if (taken <= 0) {
                    player send msg.keys.notEnough(amount, plugin.displayNameOf(id, crate))
                } else {
                    givePhysicalKeys(player, id, taken)
                    player send msg.keys.claimed(taken, plugin.displayNameOf(id, crate))
                }
                after?.run()
            }
        }
    }

    /** Deposits up to [amount] physical keys from [player]'s inventory into virtual keys. */
    fun depositKey(player: Player, crateId: String, amount: Int, after: Runnable? = null) {
        val msg = plugin.messageConfig.data
        if (!plugin.config.data.virtual.allowKeyDeposit) {
            player send msg.keys.depositDisabled
            after?.run()
            return
        }
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: run {
            player fail msg.commands.unknownCrate(crateId)
            after?.run()
            return
        }
        var removed = 0
        val want = amount.coerceAtLeast(1)
        val contents = player.inventory.storageContents
        for (i in contents.indices) {
            if (removed >= want) break
            val stack = contents[i] ?: continue
            if (plugin.items.keyCrateIdOf(stack) != id) continue
            val take = minOf(stack.amount, want - removed)
            if (take >= stack.amount) player.inventory.setItem(i, null) else stack.amount -= take
            removed += take
        }
        if (removed == 0) {
            player send msg.keys.noneToDeposit(plugin.displayNameOf(id, crate))
            after?.run()
            return
        }
        val deposited = removed
        plugin.scope.launch {
            VirtualBalanceRepository.giveKeys(player.uniqueId, id, deposited)
            refresh(player.uniqueId)
            player.runTask(plugin) {
                player send msg.keys.deposited(deposited, plugin.displayNameOf(id, crate))
                after?.run()
            }
        }
    }
}
