package mc.xalitoria.crates.paper.hologram

import com.xalitoria.util.bukkit.runTask
import mc.xalitoria.crates.paper.XaliCrates
import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType

/** Paper's own `TextDisplay` entity: needs no plugin, so it is where [HologramProvider.AUTO] ends. */
internal class NativeBackend(private val plugin: XaliCrates) : HologramBackend {
    override val provider = HologramProvider.NATIVE
    override val needsLoadedChunk = true

    private val marker = NamespacedKey(plugin, "hologram")

    override fun create(name: String, location: Location, lines: List<Component>): Hologram? {
        val world = location.world ?: return null
        val blocks = plugin.config.data.holograms.viewRange
        val display = world.spawn(location, TextDisplay::class.java) { td ->
            td.billboard = Display.Billboard.CENTER
            td.isShadowed = true
            td.isSeeThrough = false
            td.isPersistent = false
            td.backgroundColor = Color.fromARGB(HologramStyles.BACKGROUND_ALPHA, 0, 0, 0)
            td.viewRange = HologramStyles.viewRangeFactor(blocks)
            td.text(HologramText.joined(lines))
            td.persistentDataContainer.set(marker, PersistentDataType.STRING, name)
        }
        return object : Hologram {
            override val valid get() = display.isValid

            override fun lines(lines: List<Component>) {
                if (display.isValid) display.text(HologramText.joined(lines))
            }

            override fun remove() {
                // A plugin that is being disabled cannot schedule; the display is not persistent and goes with the server.
                if (display.isValid) runCatching { display.runTask(plugin) { display.remove() } }
            }
        }
    }
}
