package mc.xalitoria.crates.paper.external

import mc.xalitoria.crates.paper.XaliCrates
import net.xalitoria.redeem.PendingReward
import net.xalitoria.redeem.XaliRedeemApi
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Soft-dependency hook into XaliRedeem (`net.xalitoria.redeem.XaliRedeemApi`).
 *
 * Only instantiated after verifying XaliRedeem is enabled, so XaliCrates runs cleanly without XaliRedeem.
 * Handles:
 * 1. Storing overflow items when a player's inventory is full during crate openings or key/crate grants.
 * 2. Storing dynamic/ad-hoc crate rewards (items, commands, messages) that aren't defined in XaliRedeem's config.
 * 3. Granting static XaliRedeem vouchers when a reward explicitly specifies `redeem.voucher`.
 */
class RedeemHook(private val plugin: XaliCrates) {
    private val api: XaliRedeemApi = XaliRedeemApi.get()

    val vouchers: Set<String> get() = api.getVouchers()

    fun hasVoucher(id: String): Boolean = api.hasVoucher(id)

    fun giveVoucher(player: UUID, voucherId: String, amount: Int): CompletableFuture<Int> =
        api.giveVoucher(player, voucherId, amount)

    /** Stores overflow [items] as a claimable dynamic pending reward in XaliRedeem. */
    fun storeOverflow(
        player: UUID,
        crateId: String,
        crateName: String,
        rewardName: String,
        items: List<ItemStack>,
        icon: ItemStack? = items.firstOrNull(),
    ): CompletableFuture<Long> {
        val cfg = plugin.config.data.redeem
        val title = cfg.overflowTitle.replace("%crate%", crateName).replace("%reward%", rewardName)
        val description = cfg.overflowDescription.map { it.replace("%crate%", crateName).replace("%reward%", rewardName) }
        val pending = PendingReward.builder(title)
            .description(description)
            .icon(icon)
            .items(items)
            .source("xalicrates:$crateId")
            .notifyPlayer(cfg.notifyPlayer)
            .build()
        return api.giveReward(player, pending)
    }

    /** Stores an entire ad-hoc crate reward (items + commands + messages) in XaliRedeem without needing a static voucher file. */
    fun storeReward(
        player: UUID,
        crateId: String,
        title: String,
        description: List<String>,
        icon: ItemStack?,
        items: List<ItemStack>,
        commands: List<String>,
        playerCommands: List<String>,
    ): CompletableFuture<Long> {
        val cfg = plugin.config.data.redeem
        val pending = PendingReward.builder(title)
            .description(description)
            .icon(icon)
            .items(items)
            .commands(commands)
            .playerCommands(playerCommands)
            .source("xalicrates:$crateId")
            .notifyPlayer(cfg.notifyPlayer)
            .build()
        return api.giveReward(player, pending)
    }
}
