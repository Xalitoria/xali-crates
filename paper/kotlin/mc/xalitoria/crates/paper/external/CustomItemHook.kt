package mc.xalitoria.crates.paper.external

import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack

/**
 * Isolated optional hooks for Nexo, ItemsAdder, and HeadDatabase so those classes are never loaded unless the plugin is installed.
 */
object CustomItemHook {
    fun resolve(spec: String): ItemStack? {
        val lower = spec.lowercase()
        return when {
            lower.startsWith("nexo:") -> resolveNexo(spec.substringAfter(':'))
            lower.startsWith("itemsadder:") || lower.startsWith("ia:") -> resolveItemsAdder(spec.substringAfter(':'))
            lower.startsWith("hdb:") || lower.startsWith("headdatabase:") -> resolveHeadDatabase(spec.substringAfter(':'))
            else -> null
        }
    }

    private fun resolveNexo(id: String): ItemStack? {
        if (!Bukkit.getPluginManager().isPluginEnabled("Nexo")) return null
        return runCatching { NexoBridge.item(id) }.getOrNull()
    }

    private fun resolveItemsAdder(id: String): ItemStack? {
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null
        return runCatching { ItemsAdderBridge.item(id) }.getOrNull()
    }

    private fun resolveHeadDatabase(id: String): ItemStack? {
        if (!Bukkit.getPluginManager().isPluginEnabled("HeadDatabase")) return null
        return runCatching { HeadDatabaseBridge.item(id) }.getOrNull()
    }

    fun identify(stack: ItemStack): String? {
        if (Bukkit.getPluginManager().isPluginEnabled("Nexo")) {
            runCatching { NexoBridge.idOf(stack) }.getOrNull()?.let { return "nexo:$it" }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            runCatching { ItemsAdderBridge.idOf(stack) }.getOrNull()?.let { return "itemsadder:$it" }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("HeadDatabase")) {
            runCatching { HeadDatabaseBridge.idOf(stack) }.getOrNull()?.let { return "hdb:$it" }
        }
        return null
    }
}

private object NexoBridge {
    fun item(id: String): ItemStack? = com.nexomc.nexo.api.NexoItems.itemFromId(id)?.build()
    fun idOf(stack: ItemStack): String? = com.nexomc.nexo.api.NexoItems.idFromItem(stack)
}

private object ItemsAdderBridge {
    fun item(id: String): ItemStack? = dev.lone.itemsadder.api.CustomStack.getInstance(id)?.itemStack?.clone()
    fun idOf(stack: ItemStack): String? = dev.lone.itemsadder.api.CustomStack.byItemStack(stack)?.namespacedID
}

private object HeadDatabaseBridge {
    private val api by lazy { me.arcaniax.hdb.api.HeadDatabaseAPI() }
    fun item(id: String): ItemStack? = api.getItemHead(id)
    fun idOf(stack: ItemStack): String? = api.getItemID(stack)?.takeIf { it.isNotBlank() }
}
