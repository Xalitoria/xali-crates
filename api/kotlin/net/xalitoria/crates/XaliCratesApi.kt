package net.xalitoria.crates

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * The public API of XaliCrates, registered in Bukkit's services manager.
 * Uses JDK and Bukkit types only so plugins carrying their own Kotlin runtime can call it safely.
 */
interface XaliCratesApi {
    /** All loaded crate identifiers. */
    fun getCrateIds(): Set<String>

    /** Returns the [CrateInfo] for [crateId], or null if no crate with that id is loaded. */
    fun getCrate(crateId: String): CrateInfo?

    /** All currently placed crates across loaded worlds. */
    fun getPlacedCrates(): List<PlacedCrateInfo>

    /** Returns the placed crate at [location], or null if none is there. */
    fun getPlacedCrateAt(location: Location): PlacedCrateInfo?

    /** Reads how many virtual keys [player] holds for [crateId]. */
    fun getVirtualKeys(player: UUID, crateId: String): CompletableFuture<Int>

    /** Gives [amount] virtual keys for [crateId] to [player]; completes with the new balance. */
    fun giveVirtualKeys(player: UUID, crateId: String, amount: Int): CompletableFuture<Int>

    /** Takes up to [amount] virtual keys for [crateId] from [player]; completes with how many were taken. */
    fun takeVirtualKeys(player: UUID, crateId: String, amount: Int): CompletableFuture<Int>

    /** Reads how many virtual crates [player] holds for [crateId]. */
    fun getVirtualCrates(player: UUID, crateId: String): CompletableFuture<Int>

    /** Gives [amount] virtual crates for [crateId] to [player]; completes with the new balance. */
    fun giveVirtualCrates(player: UUID, crateId: String, amount: Int): CompletableFuture<Int>

    /** Takes up to [amount] virtual crates for [crateId] from [player]; completes with how many were taken. */
    fun takeVirtualCrates(player: UUID, crateId: String, amount: Int): CompletableFuture<Int>

    /** Builds a physical key [ItemStack] for [crateId], or null if the crate does not exist. */
    fun createKeyItem(crateId: String, amount: Int): ItemStack?

    /** Builds a physical crate [ItemStack] for [crateId], or null if the crate does not exist. */
    fun createCrateItem(crateId: String, amount: Int): ItemStack?

    /** Gives [amount] physical keys to [player] (falling back to virtual/redeem/drop according to config). */
    fun givePhysicalKeys(player: Player, crateId: String, amount: Int): Boolean

    /** Gives [amount] physical crates to [player]. */
    fun givePhysicalCrates(player: Player, crateId: String, amount: Int): Boolean

    /** Opens [crateId] for [player]. When [force] is true, bypasses key, cost, cooldown and permission checks. */
    fun openCrate(player: Player, crateId: String, force: Boolean, skipAnimation: Boolean): Boolean

    /** Opens the XaliMenu reward preview menu for [crateId] for [player]. */
    fun openPreview(player: Player, crateId: String): Boolean

    /** Reloads configuration, crates and animations; returns the number of loaded crates. */
    fun reload(): Int

    companion object {
        @JvmStatic
        fun find(): XaliCratesApi? = Bukkit.getServicesManager().load(XaliCratesApi::class.java)

        @JvmStatic
        fun get(): XaliCratesApi = find()
            ?: error("XaliCrates is not enabled; add it as a dependency of your plugin")
    }
}
