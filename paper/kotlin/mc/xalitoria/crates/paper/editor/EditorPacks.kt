package mc.xalitoria.crates.paper.editor

import io.papermc.paper.datacomponent.DataComponentTypes
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.external.CustomItemHook
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.extension

/**
 * Discovers Nexo, Oraxen, and ItemsAdder resource packs, plugin glyphs, and custom item model metadata
 * for the XaliCrates web editor (mirroring `menu/paper/.../editor/ResourcePacks.kt`).
 */
internal class EditorPacks(private val plugin: XaliCrates) {
    @Volatile
    private var cachedHash: Triple<Path, Long, String>? = null

    fun pack(): Path? {
        val configured = plugin.config.data.editor.pack.trim()
        if (configured.isNotEmpty()) return Path.of(configured).takeIf { it.exists() }
        val plugins = plugin.server.pluginsFolder.toPath()
        return listOf(
            plugins.resolve("Nexo/pack/pack.zip"),
            plugins.resolve("Oraxen/pack/pack.zip"),
            plugins.resolve("ItemsAdder/output/generated.zip"),
            plugins.resolve("ItemsAdder/output/pack.zip"),
            plugins.resolve("ItemsAdder/data/resource_pack/generated.zip"),
        ).firstOrNull { it.exists() }
    }

    /** Cached SHA-256 hex digest of the local [pack] file so the browser can reuse IndexedDB chunks across sessions. */
    fun packHash(): String? {
        val file = pack() ?: return null
        val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() xor Files.size(file) }.getOrNull() ?: return null
        cachedHash?.let { if (it.first == file && it.second == stamp) return it.third }
        val digest = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    md.update(buf, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull() ?: return null
        cachedHash = Triple(file, stamp, digest)
        return digest
    }

    fun packUrl(): String? {
        val configured = plugin.config.data.editor.packUrl.trim()
        if (configured.isNotEmpty()) {
            return configured.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }
        return null
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
