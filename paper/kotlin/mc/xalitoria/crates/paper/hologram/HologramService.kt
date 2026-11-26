package mc.xalitoria.crates.paper.hologram

import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.external.CmiHologramsHook
import mc.xalitoria.crates.paper.external.DecentHologramsHook
import mc.xalitoria.crates.paper.external.HolographicDisplaysHook
import mc.xalitoria.crates.paper.external.PacketEventsSender
import mc.xalitoria.crates.paper.external.ProtocolLibSender
import net.kyori.adventure.text.Component
import org.bukkit.Location

/**
 * Chooses where crate holograms are drawn (`holograms.provider`) and makes them there. The classes of a host plugin
 * are loaded only once that plugin is found enabled, and a host that fails to start is skipped for the next one.
 */
class HologramService(private val plugin: XaliCrates) {
    @Volatile
    private var backend: HologramBackend = NativeBackend(plugin)

    val provider: HologramProvider get() = backend.provider

    /** True when a hologram can only be made while its chunk is loaded (see [HologramBackend.needsLoadedChunk]). */
    val needsLoadedChunk: Boolean get() = backend.needsLoadedChunk

    /** True when holograms have to be refreshed regularly (see [HologramBackend.needsRefresh]). */
    val needsRefresh: Boolean get() = backend.needsRefresh

    /** (Re)opens the provider the config asks for, closing the one in use. Call [close] first for holograms already made. */
    fun open() {
        backend.close()
        val manager = plugin.server.pluginManager
        val installed = HologramProvider.entries
            .filter { it.plugin != null && manager.isPluginEnabled(it.plugin) }
            .toMutableSet()
        var preferred = plugin.config.data.holograms.provider
        while (true) {
            val choice = HologramProviders.choose(preferred, installed)
            choice.from?.let {
                plugin.logger.warning("Hologram provider $it is not installed; using ${choice.provider} instead.")
            }
            val opened = if (choice.provider == HologramProvider.NATIVE) NativeBackend(plugin) else runCatching { build(choice.provider) }
                .onFailure { plugin.logger.warning("Hologram provider ${choice.provider} could not start: ${it.message}") }
                .getOrNull()
            if (opened != null) {
                runCatching { opened.purge() }
                backend = opened
                plugin.logger.info("Crate holograms: ${opened.provider}")
                return
            }
            installed.remove(choice.provider)
            preferred = HologramProvider.AUTO
        }
    }

    fun create(name: String, location: Location, lines: List<Component>): Hologram? =
        runCatching { backend.create(name, location, lines) }
            .onFailure { plugin.logger.warning("Hologram $name could not be made through ${backend.provider}: ${it.message}") }
            .getOrNull()

    fun close() {
        runCatching { backend.close() }
    }

    private fun build(provider: HologramProvider): HologramBackend = when (provider) {
        HologramProvider.DECENTHOLOGRAMS -> DecentHologramsHook()
        HologramProvider.CMI -> CmiHologramsHook()
        HologramProvider.HOLOGRAPHICDISPLAYS -> HolographicDisplaysHook(plugin)
        HologramProvider.PACKETEVENTS -> PacketBackend(plugin, PacketEventsSender())
        HologramProvider.PROTOCOLLIB -> PacketBackend(plugin, ProtocolLibSender())
        HologramProvider.NATIVE, HologramProvider.AUTO -> NativeBackend(plugin)
    }
}
