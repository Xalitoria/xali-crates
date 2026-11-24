package mc.xalitoria.crates.paper.editor

import io.papermc.paper.datacomponent.DataComponentTypes
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.external.CustomItemHook
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.extension

/**
 * Discovers Nexo, Oraxen, and ItemsAdder resource packs, plugin glyphs, and custom item model metadata
 * for the XaliCrates web editor (mirroring `menu/paper/.../editor/ResourcePacks.kt`).
 */
internal class EditorPacks(private val plugin: XaliCrates) {
    fun pack(): Path? {
        val plugins = plugin.server.pluginsFolder.toPath()
        return listOf(
            plugins.resolve("Nexo/pack/pack.zip"),
            plugins.resolve("Oraxen/pack/pack.zip"),
            plugins.resolve("ItemsAdder/output/generated.zip"),
        ).firstOrNull { it.exists() }
    }

    fun glyphs(): List<GlyphDto> {
        val plugins = plugin.server.pluginsFolder.toPath()
        val found = mutableListOf<GlyphDto>()
        for (name in listOf("Nexo", "Oraxen")) {
            val folder = plugins.resolve("$name/glyphs")
            if (!Files.isDirectory(folder)) continue
            Files.walk(folder).use { files ->
                files.filter { it.extension == "yml" }.forEach { file ->
                    val yaml = YamlConfiguration.loadConfiguration(file.toFile())
                    for (key in yaml.getKeys(false)) {
                        val character = yaml.getString("$key.char")?.takeIf { it.isNotEmpty() }
                            ?: yaml.getStringList("$key.chars").firstOrNull()?.takeIf { it.isNotEmpty() }
                            ?: continue
                        val font = yaml.getString("$key.font")?.takeIf { it.isNotBlank() }
                        found.add(GlyphDto(key, character, name, font))
                    }
                }
            }
        }
        return found
    }

    fun items(materials: List<String>): Map<String, ItemInfoDto> {
        val done = CompletableFuture<Map<String, ItemInfoDto>>()
        plugin.server.globalRegionScheduler.run(plugin) {
            done.complete(
                materials.mapNotNull { material ->
                    val stack = CustomItemHook.resolve(material)
                        ?: Material.matchMaterial(material.removePrefix("minecraft:"))?.takeIf { it.isItem && !it.isLegacy }?.let { ItemStack(it) }
                        ?: return@mapNotNull null
                    val isCustom = ':' in material && !material.startsWith("minecraft:", ignoreCase = true)
                    material to ItemInfoDto(
                        material = stack.type.key.key,
                        itemModel = stack.getData(DataComponentTypes.ITEM_MODEL)?.asString()?.takeIf { isCustom },
                        customModelData = stack.getData(DataComponentTypes.CUSTOM_MODEL_DATA)?.floats()?.map { it.toDouble() },
                        maxDamage = stack.getData(DataComponentTypes.MAX_DAMAGE) ?: 0,
                    )
                }.toMap(),
            )
        }
        return runCatching { done.get(3, TimeUnit.SECONDS) }.getOrDefault(emptyMap())
    }
}
