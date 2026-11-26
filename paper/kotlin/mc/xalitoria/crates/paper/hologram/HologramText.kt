package mc.xalitoria.crates.paper.hologram

import com.xalitoria.util.message.toComponent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Location

/**
 * Hologram text. Only the plugin's own formats are read (`&` codes, `&#rrggbb`, MiniMessage tags, through util's
 * `toComponent`); the many colorizers a whole server may have are XaliChat's business, not this plugin's.
 *
 * A host that takes strings (DecentHolograms, CMI, HolographicDisplays) is given the legacy form with `§x§r§r§g§g§b§b` hex,
 * which every one of them passes through untouched; a host that takes components gets them as they are.
 */
internal object HologramText {
    private val LEGACY = LegacyComponentSerializer.builder()
        .character('§')
        .hexColors()
        .useUnusualXRepeatedCharacterHexFormat()
        .build()

    /** One component per configured line, each colored on its own so a color never runs into the next line. */
    fun lines(lines: List<String>): List<Component> = lines.map { it.toComponent() }

    fun legacy(line: Component): String = LEGACY.serialize(line)

    fun legacy(lines: List<Component>): List<String> = lines.map(::legacy)

    /** All lines as one multi-line component, for a host that draws a single text display. */
    fun joined(lines: List<Component>): Component = Component.join(JoinConfiguration.newlines(), lines)
}

/** The vertical spacing of the lines of DecentHolograms, CMI and HolographicDisplays, about a quarter of a block. */
private const val LINE_SPACING = 0.25

/**
 * Where to put the first (top) line of a hologram of [lines] lines so that the whole stack is centered on this point, as
 * a single text display is: those hosts stack their lines downward from the location they are given.
 */
internal fun Location.topOfLines(lines: Int): Location = clone().add(0.0, (lines - 1).coerceAtLeast(0) * LINE_SPACING / 2, 0.0)
