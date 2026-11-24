package mc.xalitoria.crates.paper.external

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.math.BigDecimal

/**
 * Isolated optional economy hook supporting VaultUnlocked (multi-currency) and legacy Vault.
 */
class EconomyHook private constructor(
    private val unlocked: net.milkbowl.vault2.economy.Economy?,
    private val legacy: Economy?,
) {
    fun has(player: Player, amount: Double, currency: String = ""): Boolean {
        if (amount <= 0.0) return true
        unlocked?.let { eco ->
            val world = player.world.name
            val value = BigDecimal.valueOf(amount)
            return if (currency.isNotBlank() && eco.hasMultiCurrencySupport()) {
                eco.has(PLUGIN_ID, player.uniqueId, world, currency, value)
            } else {
                eco.has(PLUGIN_ID, player.uniqueId, world, value)
            }
        }
        return legacy?.has(player, amount) ?: false
    }

    fun withdraw(player: Player, amount: Double, currency: String = ""): Boolean {
        if (amount <= 0.0) return true
        unlocked?.let { eco ->
            val world = player.world.name
            val value = BigDecimal.valueOf(amount)
            val response = if (currency.isNotBlank() && eco.hasMultiCurrencySupport()) {
                eco.withdraw(PLUGIN_ID, player.uniqueId, world, currency, value)
            } else {
                eco.withdraw(PLUGIN_ID, player.uniqueId, world, value)
            }
            return response.transactionSuccess()
        }
        return legacy?.withdrawPlayer(player, amount)?.transactionSuccess() ?: false
    }

    fun deposit(player: Player, amount: Double, currency: String = ""): Boolean {
        if (amount <= 0.0) return true
        unlocked?.let { eco ->
            val world = player.world.name
            val value = BigDecimal.valueOf(amount)
            val response = if (currency.isNotBlank() && eco.hasMultiCurrencySupport()) {
                eco.deposit(PLUGIN_ID, player.uniqueId, world, currency, value)
            } else {
                eco.deposit(PLUGIN_ID, player.uniqueId, world, value)
            }
            return response.transactionSuccess()
        }
        return legacy?.depositPlayer(player, amount)?.transactionSuccess() ?: false
    }

    fun format(amount: Double, currency: String = ""): String {
        unlocked?.let { eco ->
            val value = BigDecimal.valueOf(amount)
            return if (currency.isNotBlank() && eco.hasMultiCurrencySupport()) {
                eco.format(PLUGIN_ID, value, currency)
            } else {
                eco.format(PLUGIN_ID, value)
            }
        }
        return legacy?.format(amount) ?: String.format("%.2f", amount)
    }

    companion object {
        private const val PLUGIN_ID = "XaliCrates"

        fun create(): EconomyHook? {
            val sm = Bukkit.getServicesManager()
            val unlocked = runCatching { sm.getRegistration(net.milkbowl.vault2.economy.Economy::class.java)?.provider }.getOrNull()
            val legacy = runCatching { sm.getRegistration(Economy::class.java)?.provider }.getOrNull()
            if (unlocked == null && legacy == null) return null
            return EconomyHook(unlocked, legacy)
        }
    }
}
