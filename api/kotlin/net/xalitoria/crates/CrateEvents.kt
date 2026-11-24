package net.xalitoria.crates

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.Collections
import java.util.UUID

/** Fired before a player opens a crate. Cancelling prevents the key/cost from being consumed and cancels the opening. */
class CratePreOpenEvent(
    val player: Player,
    val crateId: String,
    val virtual: Boolean,
    val skipAnimation: Boolean,
) : Event(), Cancellable {
    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** Fired after rewards have been selected and granted to [player] for opening [crateId]. */
class CrateOpenEvent(
    val player: Player,
    val crateId: String,
    rewards: List<RewardInfo>,
    val openedCount: Int,
    val virtual: Boolean,
) : Event() {
    val rewards: List<RewardInfo> = Collections.unmodifiableList(ArrayList(rewards))

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** Fired when keys are given to a player (physical or virtual). */
class KeyGiveEvent(
    val playerUuid: UUID,
    val crateId: String,
    val amount: Int,
    val virtual: Boolean,
) : Event() {
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** Fired before a player consumes a key to open a crate. Cancelling prevents consumption. */
class KeyUseEvent(
    val player: Player,
    val crateId: String,
    val virtual: Boolean,
) : Event(), Cancellable {
    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}

/** Fired when a lucky chest is about to spawn from breaking a block. Cancelling prevents it from spawning. */
class LuckyChestSpawnEvent(
    val player: Player,
    val crateId: String,
    val block: Block,
    private val spawnLocation: Location,
) : Event(), Cancellable {
    private var cancelled = false

    fun getLocation(): Location = spawnLocation.clone()
    override fun isCancelled(): Boolean = cancelled
    override fun setCancelled(cancel: Boolean) { cancelled = cancel }
    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()
        @JvmStatic fun getHandlerList(): HandlerList = HANDLERS
    }
}
