package mc.xalitoria.crates.paper.external

import net.milkbowl.vault2.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.math.BigDecimal

/**
 * Isolated optional economy hook using VaultUnlocked (`net.milkbowl.vault2.economy.Economy`), matching XaliMenu.
 */
class EconomyHook private constructor(
    private val unlocked: Economy,
) {
    fun has(player: Player, amount: Double, currency: String = ""): Boolean {
        if (amount <= 0.0) return true
        val world = player.world.name
        val value = BigDecimal.valueOf(amount)
        return if (currency.isNotBlank() && unlocked.hasMultiCurrencySupport()) {
            unlocked.has(PLUGIN_ID, player.uniqueId, world, currency, value)
        } else {
            unlocked.has(PLUGIN_ID, player.uniqueId, world, value)
        }
    }

    fun withdraw(player: Player, amount: Double, currency: String = ""): Boolean {
        if (amount <= 0.0) return true
        val world = player.world.name
        val value = BigDecimal.valueOf(amount)
        val response = if (currency.isNotBlank() && unlocked.hasMultiCurrencySupport()) {
            unlocked.withdraw(PLUGIN_ID, player.uniqueId, world, currency, value)
        } else {
            unlocked.withdraw(PLUGIN_ID, player.uniqueId, world, value)
        }
        return response.transactionSuccess()
    }

    fun deposit(player: Player, amount: Double, currency: String = ""): Boolean {
        if (amount <= 0.0) return true
        val world = player.world.name
        val value = BigDecimal.valueOf(amount)
        val response = if (currency.isNotBlank() && unlocked.hasMultiCurrencySupport()) {
            unlocked.deposit(PLUGIN_ID, player.uniqueId, world, currency, value)
        } else {
            unlocked.deposit(PLUGIN_ID, player.uniqueId, world, value)
        }
        return response.transactionSuccess()
    }

    fun format(amount: Double, currency: String = ""): String {
        val value = BigDecimal.valueOf(amount)
        return if (currency.isNotBlank() && unlocked.hasMultiCurrencySupport()) {
            unlocked.format(PLUGIN_ID, value, currency)
        } else {
            unlocked.format(PLUGIN_ID, value)
        }
    }

    companion object {
        private const val PLUGIN_ID = "XaliCrates"

        fun create(): EconomyHook? {
            val unlocked = runCatching {
                Bukkit.getServicesManager().getRegistration(Economy::class.java)?.provider
            }.getOrNull() ?: return null
            return EconomyHook(unlocked)
        }
    }
}
