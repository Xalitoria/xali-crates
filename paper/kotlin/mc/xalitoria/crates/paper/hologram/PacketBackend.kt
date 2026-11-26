package mc.xalitoria.crates.paper.hologram

import com.xalitoria.util.bukkit.runTaskLater
import mc.xalitoria.crates.paper.XaliCrates
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Holograms that exist only in packets: one fake `TextDisplay` per hologram, sent to the players near it. Nothing is
 * stored in a world, so there is nothing to clean up after a crash. [sender] is PacketEvents' or ProtocolLib's.
 */
internal class PacketBackend(private val plugin: XaliCrates, private val sender: PacketSender) : HologramBackend, Listener {
    override val provider get() = sender.provider
    override val needsRefresh = true

    private val holograms = ConcurrentHashMap.newKeySet<PacketHologram>()

    init {
        plugin.server.pluginManager.registerEvents(this, plugin)
    }

    override fun create(name: String, location: Location, lines: List<Component>): Hologram? {
        val world = location.world ?: return null
        val settings = plugin.config.data.holograms
        val hologram = PacketHologram(
            sender = sender,
            entityId = sender.nextEntityId(world),
            uuid = UUID.randomUUID(),
            at = location.clone(),
            range = settings.viewRange.toDouble(),
            style = HologramStyles.default(settings.viewRange),
            text = HologramText.joined(lines),
            nearby = { at, range -> at.world.getNearbyPlayers(at, range) },
            onRemove = { holograms.remove(it) },
        )
        holograms.add(hologram)
        hologram.refresh()
        return hologram
    }

    override fun close() {
        for (hologram in holograms.toList()) runCatching { hologram.remove() }
        holograms.clear()
        org.bukkit.event.HandlerList.unregisterAll(this)
    }

    private fun forget(player: UUID) {
        for (hologram in holograms) hologram.forget(player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = forget(event.player.uniqueId)

    /** A new world is a new client level: every display is gone, so every hologram has to be sent again. */
    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) = forget(event.player.uniqueId)

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        // The client rebuilds its level a moment after the event, so forgetting at once could send into the old one.
        player.runTaskLater(plugin, RESPAWN_DELAY_TICKS) { forget(player.uniqueId) }
    }

    private companion object {
        const val RESPAWN_DELAY_TICKS = 10L
    }
}

/** One fake text display and the players it has been sent to. */
internal class PacketHologram(
    private val sender: PacketSender,
    private val entityId: Int,
    private val uuid: UUID,
    private val at: Location,
    private val range: Double,
    private val style: DisplayStyle,
    text: Component,
    private val nearby: (Location, Double) -> Collection<Player>,
    private val onRemove: (PacketHologram) -> Unit,
) : Hologram {
    @Volatile
    private var text: Component = text
    private val viewers = ConcurrentHashMap<UUID, Player>()

    override fun lines(lines: List<Component>) {
        text = HologramText.joined(lines)
        val fields = DisplayData.text(text)
        for (viewer in viewers.values) sender.update(viewer, entityId, fields)
    }

    override fun refresh() = update(nearby(at, range + HIDE_MARGIN))

    /**
     * Shows the hologram to the [candidates] within `range` and hides it from the ones that left `range` plus a margin
     * (so someone standing on the edge does not make it blink).
     */
    fun update(candidates: Collection<Player>) {
        val limit = range * range
        val keep = (range + HIDE_MARGIN) * (range + HIDE_MARGIN)
        val inside = HashSet<UUID>()
        for (player in candidates) {
            if (!player.isOnline) continue
            val distance = player.location.distanceSquared(at)
            val id = player.uniqueId
            if (viewers.containsKey(id)) {
                if (distance <= keep) inside.add(id)
            } else if (distance <= limit) {
                inside.add(id)
                if (viewers.putIfAbsent(id, player) == null) {
                    sender.spawn(player, entityId, uuid, at, DisplayData.full(text, style))
                }
            }
        }
        for ([id, player] in viewers.entries.toList()) {
            if (id in inside || viewers.remove(id) == null) continue
            if (player.isOnline) sender.destroy(player, entityId)
        }
    }

    /** Drops [player] without telling their client (it is gone, or about to forget the display itself). */
    fun forget(player: UUID) {
        viewers.remove(player)
    }

    fun viewerCount(): Int = viewers.size

    override fun remove() {
        for (player in viewers.values) if (player.isOnline) sender.destroy(player, entityId)
        viewers.clear()
        onRemove(this)
    }

    private companion object {
        const val HIDE_MARGIN = 4.0
    }
}
