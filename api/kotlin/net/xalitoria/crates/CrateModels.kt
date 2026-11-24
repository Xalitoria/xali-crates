package net.xalitoria.crates

import org.bukkit.Location
import org.bukkit.inventory.ItemStack
import java.util.Collections

/**
 * Immutable snapshot of a loaded reward belonging to a crate.
 */
class RewardInfo(
    val id: String,
    val displayName: String,
    val weight: Double,
    val probabilityPercent: Double,
    val rarity: String,
    val guaranteed: Boolean,
    val fallbackOnly: Boolean,
    private val displayStack: ItemStack?,
) {
    fun getDisplayItem(): ItemStack? = displayStack?.clone()
}

/**
 * Immutable snapshot of a loaded crate definition.
 */
class CrateInfo(
    val id: String,
    val displayName: String,
    val enabled: Boolean,
    val multiCrate: Boolean,
    val requireKey: Boolean,
    val animation: String,
    val cost: Double,
    val cooldownSeconds: Long,
    rewards: List<RewardInfo>,
) {
    val rewards: List<RewardInfo> = Collections.unmodifiableList(ArrayList(rewards))
}

/**
 * Immutable snapshot of a crate placed in a world.
 */
class PlacedCrateInfo(
    val id: Long,
    val crateId: String,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val placedAt: Long,
    val expiresAt: Long,
    private val loc: Location?,
) {
    fun getLocation(): Location? = loc?.clone()
}
