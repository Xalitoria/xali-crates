package mc.xalitoria.crates.paper.external

import eu.decentsoftware.holograms.api.DHAPI
import mc.xalitoria.crates.paper.hologram.Hologram
import mc.xalitoria.crates.paper.hologram.HologramBackend
import mc.xalitoria.crates.paper.hologram.HologramProvider
import mc.xalitoria.crates.paper.hologram.HologramText
import mc.xalitoria.crates.paper.hologram.topOfLines
import net.kyori.adventure.text.Component
import org.bukkit.Location

/** Crate holograms as DecentHolograms holograms. Not saved to its files (`saveToFile = false`), so nothing outlives the server. */
internal class DecentHologramsHook : HologramBackend {
    override val provider = HologramProvider.DECENTHOLOGRAMS

    override fun create(name: String, location: Location, lines: List<Component>): Hologram? {
        // A name is unique: take over one that an earlier run (or a reload) left behind.
        runCatching { DHAPI.removeHologram(name) }
        val hologram = DHAPI.createHologram(name, location.topOfLines(lines.size), false, HologramText.legacy(lines))
        return object : Hologram {
            override fun lines(lines: List<Component>) {
                DHAPI.setHologramLines(hologram, HologramText.legacy(lines))
            }

            override fun remove() {
                runCatching { DHAPI.removeHologram(name) }
            }
        }
    }
}
