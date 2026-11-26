package mc.xalitoria.crates.paper.external

import me.filoghost.holographicdisplays.api.HolographicDisplaysAPI
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.hologram.Hologram
import mc.xalitoria.crates.paper.hologram.HologramBackend
import mc.xalitoria.crates.paper.hologram.HologramProvider
import mc.xalitoria.crates.paper.hologram.HologramText
import mc.xalitoria.crates.paper.hologram.topOfLines
import net.kyori.adventure.text.Component
import org.bukkit.Location

/**
 * Crate holograms through HolographicDisplays' API, the one for servers that cannot run the newer hosts. Holograms made
 * through the API belong to this plugin and are never saved.
 */
internal class HolographicDisplaysHook(plugin: XaliCrates) : HologramBackend {
    override val provider = HologramProvider.HOLOGRAPHICDISPLAYS

    private val api = HolographicDisplaysAPI.get(plugin)

    override fun create(name: String, location: Location, lines: List<Component>): Hologram? {
        val hologram = api.createHologram(location.topOfLines(lines.size))
        fun fill(text: List<Component>) {
            hologram.lines.clear()
            for (line in HologramText.legacy(text)) hologram.lines.appendText(line)
        }
        fill(lines)
        return object : Hologram {
            override fun lines(lines: List<Component>) {
                if (!hologram.isDeleted) fill(lines)
            }

            override fun remove() {
                if (!hologram.isDeleted) hologram.delete()
            }
        }
    }

    override fun close() {
        runCatching { api.deleteHolograms() }
    }
}
