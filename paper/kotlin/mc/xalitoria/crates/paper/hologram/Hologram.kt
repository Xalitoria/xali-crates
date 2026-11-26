package mc.xalitoria.crates.paper.hologram

import net.kyori.adventure.text.Component
import org.bukkit.Location

/**
 * Where crate holograms are drawn. [AUTO] takes the first installed of [HologramProvider.AUTO_ORDER]; every other value
 * names one host and falls back to [AUTO] (with a warning) when that host is not installed.
 */
enum class HologramProvider(
    /** Name of the Bukkit plugin that hosts this provider, or `null` for the ones that need none. */
    val plugin: String?,
) {
    AUTO(null),
    DECENTHOLOGRAMS("DecentHolograms"),
    CMI("CMI"),
    HOLOGRAPHICDISPLAYS("HolographicDisplays"),
    PACKETEVENTS("packetevents"),
    PROTOCOLLIB("ProtocolLib"),

    /** Paper's own `TextDisplay` entities: nothing to install, and the last resort of [AUTO]. */
    NATIVE(null),
    ;

    companion object {
        /** What [AUTO] tries, in order; [NATIVE] is the end of the list, so a server always gets a hologram. */
        val AUTO_ORDER: List<HologramProvider> = listOf(DECENTHOLOGRAMS, CMI, HOLOGRAPHICDISPLAYS, PACKETEVENTS, PROTOCOLLIB, NATIVE)
    }
}

/** A hologram host chosen by [HologramProviders.choose]; [from] is what was asked for when it could not be had. */
data class HologramChoice(val provider: HologramProvider, val from: HologramProvider? = null)

object HologramProviders {
    /**
     * Picks the provider for [preferred] given the [installed] ones ([HologramProvider.NATIVE] needs nothing, so it is
     * always available). A named provider that is not installed falls back to [HologramProvider.AUTO]'s choice.
     */
    fun choose(preferred: HologramProvider, installed: Set<HologramProvider>): HologramChoice {
        fun available(provider: HologramProvider) = provider == HologramProvider.NATIVE || provider in installed
        if (preferred != HologramProvider.AUTO && available(preferred)) return HologramChoice(preferred)
        val auto = HologramProvider.AUTO_ORDER.first(::available)
        return HologramChoice(auto, from = preferred.takeIf { it != HologramProvider.AUTO })
    }

    /** True when a tick of [intervalTicks] at [step] crosses a multiple of [periodTicks] (so a period that is no multiple of the tick still fires). */
    fun due(step: Long, intervalTicks: Long, periodTicks: Long): Boolean {
        if (periodTicks <= 0L || intervalTicks <= 0L) return false
        return (step * intervalTicks) / periodTicks != ((step - 1) * intervalTicks) / periodTicks
    }

    /** The name a host knows a placed crate's hologram by: stable, and safe for every host (letters, digits, `_`). */
    fun name(world: String, x: Int, y: Int, z: Int): String {
        fun axis(value: Int) = if (value < 0) "n${-(value.toLong())}" else value.toString()
        val cleanWorld = world.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("")
        return "$NAME_PREFIX${cleanWorld}_${axis(x)}_${axis(y)}_${axis(z)}"
    }

    const val NAME_PREFIX = "xalicrates_"
}

/** One hologram above a placed crate. Every call is made on the region thread that owns its location. */
interface Hologram {
    /** Replaces the text, one component per line. */
    fun lines(lines: List<Component>)

    /** False once the host has lost the hologram (a `TextDisplay` goes with its chunk), so it has to be made again. */
    val valid: Boolean get() = true

    /** Re-checks who can see the hologram. Only a provider that sends packets itself needs it (see [HologramBackend.needsRefresh]). */
    fun refresh() {}

    fun remove()
}

/** Draws holograms through one provider. */
interface HologramBackend {
    val provider: HologramProvider

    /** Whether [Hologram.refresh] has to be called regularly (the packet providers, which decide who sees a hologram themselves). */
    val needsRefresh: Boolean get() = false

    /** Whether a hologram is an entity in the world, which exists only while its chunk is loaded (only [HologramProvider.NATIVE]). */
    val needsLoadedChunk: Boolean get() = false

    /** Makes a hologram called [name] at [location] (a block corner; the host puts it above), or `null` when the host refused. */
    fun create(name: String, location: Location, lines: List<Component>): Hologram?

    /** Removes what an earlier run may have left behind (a host that saves its holograms). */
    fun purge() {}

    fun close() {}
}
