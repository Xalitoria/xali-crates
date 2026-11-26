package mc.xalitoria.crates.paper.external

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.PacketContainer
import com.comphenix.protocol.wrappers.WrappedChatComponent
import com.comphenix.protocol.wrappers.WrappedDataValue
import com.comphenix.protocol.wrappers.WrappedDataWatcher.Registry
import com.xalitoria.util.message.GCOMP
import mc.xalitoria.crates.paper.hologram.DataField
import mc.xalitoria.crates.paper.hologram.HologramProvider
import mc.xalitoria.crates.paper.hologram.PacketSender
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import java.util.UUID

/** The packets of crate holograms, written through ProtocolLib (the fallback when PacketEvents is not installed). */
internal class ProtocolLibSender : PacketSender {
    override val provider = HologramProvider.PROTOCOLLIB

    private val manager = ProtocolLibrary.getProtocolManager()

    override fun spawn(viewer: Player, entityId: Int, uuid: UUID, at: Location, fields: List<DataField>) {
        val packet = PacketContainer(PacketType.Play.Server.SPAWN_ENTITY)
        packet.modifier.writeDefaults()
        packet.integers.write(0, entityId)
        packet.uuiDs.write(0, uuid)
        packet.entityTypeModifier.write(0, EntityType.TEXT_DISPLAY)
        packet.doubles.write(0, at.x).write(1, at.y).write(2, at.z)
        manager.sendServerPacket(viewer, packet)
        update(viewer, entityId, fields)
    }

    override fun update(viewer: Player, entityId: Int, fields: List<DataField>) {
        val packet = PacketContainer(PacketType.Play.Server.ENTITY_METADATA)
        packet.integers.write(0, entityId)
        packet.dataValueCollectionModifier.write(0, fields.map(::value))
        manager.sendServerPacket(viewer, packet)
    }

    override fun destroy(viewer: Player, entityId: Int) {
        val packet = PacketContainer(PacketType.Play.Server.ENTITY_DESTROY)
        packet.intLists.write(0, listOf(entityId))
        manager.sendServerPacket(viewer, packet)
    }

    private fun value(field: DataField): WrappedDataValue = when (val value = field.value) {
        is Byte -> WrappedDataValue(field.index, Registry.get(Byte::class.javaObjectType), value)
        is Int -> WrappedDataValue(field.index, Registry.get(Int::class.javaObjectType), value)
        is Float -> WrappedDataValue(field.index, Registry.get(Float::class.javaObjectType), value)
        is Component -> WrappedDataValue(
            field.index,
            Registry.getChatComponentSerializer(false),
            WrappedChatComponent.fromJson(GCOMP.serialize(value)).handle,
        )
        else -> error("No entity data type for ${value::class.simpleName}")
    }
}
