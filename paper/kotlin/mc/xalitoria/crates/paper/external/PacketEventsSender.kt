package mc.xalitoria.crates.paper.external

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.protocol.entity.data.EntityData
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes
import com.github.retrooper.packetevents.util.Vector3d
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity
import mc.xalitoria.crates.paper.hologram.DataField
import mc.xalitoria.crates.paper.hologram.HologramProvider
import mc.xalitoria.crates.paper.hologram.PacketSender
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.Optional
import java.util.UUID

/** The packets of crate holograms, written through PacketEvents (the preferred packet library). */
internal class PacketEventsSender : PacketSender {
    override val provider = HologramProvider.PACKETEVENTS

    init {
        checkNotNull(PacketEvents.getAPI()) { "PacketEvents is not loaded" }
    }

    private val players get() = PacketEvents.getAPI().playerManager

    override fun spawn(viewer: Player, entityId: Int, uuid: UUID, at: Location, fields: List<DataField>) {
        players.sendPacket(
            viewer,
            WrapperPlayServerSpawnEntity(
                entityId,
                Optional.of(uuid),
                EntityTypes.TEXT_DISPLAY,
                Vector3d(at.x, at.y, at.z),
                0f,
                0f,
                0f,
                0,
                Optional.empty(),
            ),
        )
        update(viewer, entityId, fields)
    }

    override fun update(viewer: Player, entityId: Int, fields: List<DataField>) {
        players.sendPacket(viewer, WrapperPlayServerEntityMetadata(entityId, fields.map(::data)))
    }

    override fun destroy(viewer: Player, entityId: Int) {
        players.sendPacket(viewer, WrapperPlayServerDestroyEntities(entityId))
    }

    private fun data(field: DataField): EntityData<*> = when (val value = field.value) {
        is Byte -> EntityData(field.index, EntityDataTypes.BYTE, value)
        is Int -> EntityData(field.index, EntityDataTypes.INT, value)
        is Float -> EntityData(field.index, EntityDataTypes.FLOAT, value)
        is Component -> EntityData(field.index, EntityDataTypes.ADV_COMPONENT, value)
        else -> error("No entity data type for ${value::class.simpleName}")
    }
}
