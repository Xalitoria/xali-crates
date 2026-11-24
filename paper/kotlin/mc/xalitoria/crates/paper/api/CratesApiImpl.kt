package mc.xalitoria.crates.paper.api

import com.xalitoria.util.kotlin.parseDuration
import kotlinx.coroutines.launch
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.db.repository.VirtualBalanceRepository
import mc.xalitoria.crates.paper.service.RewardRoller
import net.xalitoria.crates.CrateInfo
import net.xalitoria.crates.PlacedCrateInfo
import net.xalitoria.crates.RewardInfo
import net.xalitoria.crates.XaliCratesApi
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** Implementation of [XaliCratesApi] registered in Bukkit's services manager. */
class CratesApiImpl(private val plugin: XaliCrates) : XaliCratesApi {
    override fun getCrateIds(): Set<String> = plugin.crates.keys.toSet()

    override fun getCrate(crateId: String): CrateInfo? {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: return null
        val rewards = crate.rewards.entries.map { [rId, r] ->
            RewardInfo(
                id = rId,
                displayName = r.displayItem.name ?: rId,
                weight = r.weight,
                probabilityPercent = RewardRoller.probabilityPercent(crate, rId),
                rarity = r.rarity,
                guaranteed = r.guaranteed,
                fallbackOnly = r.fallbackOnly,
                displayStack = plugin.items.buildRewardPreviewItem(id, rId),
            )
        }
        val cdSec = runCatching { parseDuration("cooldown", crate.cooldown.duration).inWholeSeconds }.getOrDefault(0L)
        return CrateInfo(
            id = id,
            displayName = plugin.displayNameOf(id, crate),
            enabled = crate.enabled,
            multiCrate = crate.isMultiCrate,
            requireKey = crate.key.required,
            animation = crate.opening.animation,
            cost = crate.cost.amount,
            cooldownSeconds = cdSec,
            rewards = rewards,
        )
    }

    override fun getPlacedCrates(): List<PlacedCrateInfo> = plugin.placed.all().map { rec ->
        val loc = Bukkit.getWorld(rec.world)?.let { Location(it, rec.x.toDouble(), rec.y.toDouble(), rec.z.toDouble()) }
        PlacedCrateInfo(rec.id, rec.crateId, rec.world, rec.x, rec.y, rec.z, rec.placedAt, rec.expiresAt, loc)
    }

    override fun getPlacedCrateAt(location: Location): PlacedCrateInfo? {
        val rec = plugin.placed.at(location) ?: return null
        return PlacedCrateInfo(rec.id, rec.crateId, rec.world, rec.x, rec.y, rec.z, rec.placedAt, rec.expiresAt, location)
    }

    override fun getVirtualKeys(player: UUID, crateId: String): CompletableFuture<Int> = future {
        VirtualBalanceRepository.get(player, crateId.lowercase()).keys
    }

    override fun giveVirtualKeys(player: UUID, crateId: String, amount: Int): CompletableFuture<Int> = future {
        plugin.keys.giveVirtualKeys(player, crateId.lowercase(), amount)
    }

    override fun takeVirtualKeys(player: UUID, crateId: String, amount: Int): CompletableFuture<Int> = future {
        plugin.keys.takeVirtualKeys(player, crateId.lowercase(), amount)
    }

    override fun getVirtualCrates(player: UUID, crateId: String): CompletableFuture<Int> = future {
        VirtualBalanceRepository.get(player, crateId.lowercase()).crates
    }

    override fun giveVirtualCrates(player: UUID, crateId: String, amount: Int): CompletableFuture<Int> = future {
        plugin.keys.giveVirtualCrates(player, crateId.lowercase(), amount)
    }

    override fun takeVirtualCrates(player: UUID, crateId: String, amount: Int): CompletableFuture<Int> = future {
        plugin.keys.takeVirtualCrates(player, crateId.lowercase(), amount)
    }

    override fun createKeyItem(crateId: String, amount: Int): ItemStack? = plugin.items.buildKeyItem(crateId, amount)

    override fun createCrateItem(crateId: String, amount: Int): ItemStack? = plugin.items.buildCrateItem(crateId, amount)

    override fun givePhysicalKeys(player: Player, crateId: String, amount: Int): Boolean =
        plugin.keys.givePhysicalKeys(player, crateId, amount)

    override fun givePhysicalCrates(player: Player, crateId: String, amount: Int): Boolean =
        plugin.keys.givePhysicalCrates(player, crateId, amount)

    override fun openCrate(player: Player, crateId: String, force: Boolean, skipAnimation: Boolean): Boolean =
        plugin.openService.open(player, crateId, placed = null, force = force, skipAnimation = skipAnimation, virtual = !force)

    override fun openPreview(player: Player, crateId: String): Boolean = plugin.menuHook.openPreview(player, crateId)

    override fun reload(): Int = plugin.reload().first

    private fun <T> future(block: suspend () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        plugin.scope.launch {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result
    }
}
