package mc.xalitoria.crates.paper.hologram

import net.kyori.adventure.text.Component

/** How a packet-drawn text display looks; the same values the native `TextDisplay` is given. */
internal data class DisplayStyle(
    /** The display's own view range, a factor of the client's entity distance (see [HologramStyles.viewRangeFactor]). */
    val viewRange: Float,
    /** Background color as ARGB. */
    val background: Int,
    val shadow: Boolean,
    val seeThrough: Boolean,
)

internal object HologramStyles {
    /** Alpha (of 255) of the dark background behind hologram text. */
    const val BACKGROUND_ALPHA = 80

    /** A client draws entities up to 64 blocks times the display's view range, so `view-range` (in blocks) is divided by 64. */
    fun viewRangeFactor(blocks: Float): Float = (blocks / 64f).coerceIn(0.05f, 16f)

    fun default(blocks: Float) = DisplayStyle(
        viewRange = viewRangeFactor(blocks),
        background = BACKGROUND_ALPHA shl 24,
        shadow = true,
        seeThrough = false,
    )
}

/** One value of an entity's metadata: what [PacketSender]s turn into their library's own type. [value] is a `Byte`, `Int`, `Float` or [Component]. */
internal data class DataField(val index: Int, val value: Any)

/**
 * Metadata indices of a text display (Minecraft 1.20.2 and later, which is all that XaliCrates runs on): the entity's
 * own 0 to 7, then the display's 8 to 22 and the text's 23 to 27.
 */
internal object DisplayData {
    const val BILLBOARD = 15
    const val VIEW_RANGE = 17
    const val TEXT = 23
    const val LINE_WIDTH = 24
    const val BACKGROUND = 25
    const val FLAGS = 27

    /** The billboard mode that turns the text toward the viewer on every axis. */
    const val BILLBOARD_CENTER: Byte = 3

    private const val FLAG_SHADOW = 0x01
    private const val FLAG_SEE_THROUGH = 0x02

    /** Wide enough that a line of a hologram never wraps. */
    private const val LINE_WIDTH_PX = 1000

    fun flags(style: DisplayStyle): Byte =
        ((if (style.shadow) FLAG_SHADOW else 0) or (if (style.seeThrough) FLAG_SEE_THROUGH else 0)).toByte()

    /** Everything a viewer needs when the display appears. */
    fun full(text: Component, style: DisplayStyle): List<DataField> = listOf(
        DataField(BILLBOARD, BILLBOARD_CENTER),
        DataField(VIEW_RANGE, style.viewRange),
        DataField(TEXT, text),
        DataField(LINE_WIDTH, LINE_WIDTH_PX),
        DataField(BACKGROUND, style.background),
        DataField(FLAGS, flags(style)),
    )

    fun text(text: Component): List<DataField> = listOf(DataField(TEXT, text))
}

/** The packets of one library (PacketEvents or ProtocolLib) that [PacketHologram] needs; `viewer` is always online. */
internal interface PacketSender {
    val provider: HologramProvider

    /** An entity id the server will not give to a real entity. */
    @Suppress("DEPRECATION")
    fun nextEntityId(world: org.bukkit.World): Int = org.bukkit.Bukkit.getUnsafe().nextEntityId(world)

    /** Makes the display appear for [viewer] at [at], with all of [DisplayData.full]. */
    fun spawn(viewer: org.bukkit.entity.Player, entityId: Int, uuid: java.util.UUID, at: org.bukkit.Location, fields: List<DataField>)

    fun update(viewer: org.bukkit.entity.Player, entityId: Int, fields: List<DataField>)

    fun destroy(viewer: org.bukkit.entity.Player, entityId: Int)
}
