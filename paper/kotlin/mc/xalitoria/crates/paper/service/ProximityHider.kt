package mc.xalitoria.crates.paper.service

import com.xalitoria.util.bukkit.runTask
import com.xalitoria.util.bukkit.runTaskTimer
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import mc.xalitoria.crates.paper.XaliCrates
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Hides the players who stand close to a placed crate from each other (`display.hide-radius` of the crate, off at 0).
 *
 * Folia-safe: every player is looked at on their own thread, which is also where they are told to hide or show the others.
 * Each player publishes the crates they stand within the radius of ([near]) and hides whoever else is within one of the same
 * crates. Both sides do this, so the hiding is mutual a tick or two apart. Only what this plugin hid is ever shown again
 * (`hidePlayer(plugin, ...)` is per plugin, so a vanish plugin is left alone).
 */
class ProximityHider(private val plugin: XaliCrates) {
    private val near = ConcurrentHashMap<UUID, Set<String>>()
    private val hidden = ConcurrentHashMap<UUID, MutableSet<UUID>>()
    private var task: ScheduledTask? = null

    fun start() {
        task?.cancel()
        task = plugin.runTaskTimer(INTERVAL_TICKS, INTERVAL_TICKS) { step() }
    }

    fun shutdown() {
        task?.cancel()
        task = null
        for (player in Bukkit.getOnlinePlayers()) {
            player.runTask(plugin) { release(player) }
        }
        near.clear()
    }

    /** A player has left: nothing of theirs is kept (the others drop them on their next look). */
    fun forget(uuid: UUID) {
        near.remove(uuid)
        hidden.remove(uuid)
    }

    private fun step() {
        val spots = spots()
        for (player in Bukkit.getOnlinePlayers()) {
            if (spots.isEmpty() && near[player.uniqueId].isNullOrEmpty() && hidden[player.uniqueId].isNullOrEmpty()) continue
            player.runTask(plugin) { update(player, spots) }
        }
    }

    private fun spots(): List<ProximityRules.Spot> {
        val result = ArrayList<ProximityRules.Spot>()
        for (rec in plugin.placed.all()) {
            val crate = plugin.crates[rec.crateId] ?: continue
            val radius = crate.display.hideRadius
            if (!crate.enabled || !ProximityRules.active(radius)) continue
            result.add(ProximityRules.Spot(rec.blockKey.asKey(), rec.world, rec.x + 0.5, rec.y + 0.5, rec.z + 0.5, radius))
        }
        return result
    }

    /** Runs on [viewer]'s own thread. */
    private fun update(viewer: Player, spots: List<ProximityRules.Spot>) {
        val loc = viewer.location
        val mine = ProximityRules.within(spots, loc.world.name, loc.x, loc.y, loc.z)
        near[viewer.uniqueId] = mine

        val current = hidden.computeIfAbsent(viewer.uniqueId) { ConcurrentHashMap.newKeySet() }
        val wanted = HashSet<UUID>()
        if (mine.isNotEmpty()) {
            for (other in Bukkit.getOnlinePlayers()) {
                if (other.uniqueId == viewer.uniqueId) continue
                if (ProximityRules.hidesFrom(mine, near[other.uniqueId] ?: emptySet())) wanted.add(other.uniqueId)
            }
        }
        for (id in wanted) {
            if (current.add(id)) Bukkit.getPlayer(id)?.let { viewer.hidePlayer(plugin, it) } ?: current.remove(id)
        }
        val iterator = current.iterator()
        while (iterator.hasNext()) {
            val id = iterator.next()
            if (id in wanted) continue
            iterator.remove()
            Bukkit.getPlayer(id)?.let { viewer.showPlayer(plugin, it) }
        }
    }

    private fun release(viewer: Player) {
        val current = hidden.remove(viewer.uniqueId) ?: return
        for (id in current) Bukkit.getPlayer(id)?.let { viewer.showPlayer(plugin, it) }
    }

    private companion object {
        const val INTERVAL_TICKS = 5L
    }
}
