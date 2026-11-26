package mc.xalitoria.crates.paper.external

import com.Zrips.CMI.CMI
import com.Zrips.CMI.Modules.Holograms.CMIHologram
import mc.xalitoria.crates.paper.hologram.Hologram
import mc.xalitoria.crates.paper.hologram.HologramBackend
import mc.xalitoria.crates.paper.hologram.HologramProvider
import mc.xalitoria.crates.paper.hologram.HologramProviders
import mc.xalitoria.crates.paper.hologram.HologramText
import mc.xalitoria.crates.paper.hologram.topOfLines
import net.kyori.adventure.text.Component
import org.bukkit.Location

/**
 * Crate holograms as CMI holograms. CMI saves the holograms it holds, so each one is removed again when the plugin
 * stops, and [purge] takes out what a crash left under this plugin's [HologramProviders.NAME_PREFIX].
 */
internal class CmiHologramsHook : HologramBackend {
    override val provider = HologramProvider.CMI

    private val manager get() = CMI.getInstance().hologramManager

    override fun create(name: String, location: Location, lines: List<Component>): Hologram? {
        manager.getByName(name)?.let { runCatching { manager.remove(it) } }
        val hologram = CMIHologram(name, location.topOfLines(lines.size))
        hologram.pages.setLines(HologramText.legacy(lines))
        manager.add(hologram)
        hologram.requestFullUpdate()
        return object : Hologram {
            override fun lines(lines: List<Component>) {
                hologram.pages.setLines(HologramText.legacy(lines))
                hologram.requestTextUpdate()
            }

            override fun remove() {
                runCatching { manager.remove(hologram) }
            }
        }
    }

    override fun purge() {
        val stale = manager.holograms.keys.filter { it.startsWith(HologramProviders.NAME_PREFIX) }
        for (name in stale) manager.getByName(name)?.let { runCatching { manager.remove(it) } }
    }
}
