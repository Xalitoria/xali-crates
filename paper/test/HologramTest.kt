package mc.xalitoria.crates.paper

import mc.xalitoria.crates.paper.hologram.DataField
import mc.xalitoria.crates.paper.hologram.DisplayData
import mc.xalitoria.crates.paper.hologram.HologramProvider
import mc.xalitoria.crates.paper.hologram.HologramProviders
import mc.xalitoria.crates.paper.hologram.HologramStyles
import mc.xalitoria.crates.paper.hologram.HologramText
import mc.xalitoria.crates.paper.hologram.PacketHologram
import mc.xalitoria.crates.paper.hologram.PacketSender
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class HologramTest {
    private fun installed(vararg providers: HologramProvider) = providers.toSet()

    @Test
    fun autoTakesTheFirstInstalledHostInOrder() {
        val all = installed(
            HologramProvider.PROTOCOLLIB, HologramProvider.PACKETEVENTS, HologramProvider.HOLOGRAPHICDISPLAYS,
            HologramProvider.CMI, HologramProvider.DECENTHOLOGRAMS,
        )
        assertEquals(HologramProvider.DECENTHOLOGRAMS, HologramProviders.choose(HologramProvider.AUTO, all).provider)
        assertEquals(HologramProvider.CMI, HologramProviders.choose(HologramProvider.AUTO, all - HologramProvider.DECENTHOLOGRAMS).provider)
        assertEquals(
            HologramProvider.HOLOGRAPHICDISPLAYS,
            HologramProviders.choose(HologramProvider.AUTO, installed(HologramProvider.HOLOGRAPHICDISPLAYS, HologramProvider.PACKETEVENTS)).provider,
        )
    }

    @Test
    fun packetEventsIsPreferredOverProtocolLibAndNativeIsTheLastResort() {
        assertEquals(
            HologramProvider.PACKETEVENTS,
            HologramProviders.choose(HologramProvider.AUTO, installed(HologramProvider.PROTOCOLLIB, HologramProvider.PACKETEVENTS)).provider,
        )
        assertEquals(
            HologramProvider.PROTOCOLLIB,
            HologramProviders.choose(HologramProvider.AUTO, installed(HologramProvider.PROTOCOLLIB)).provider,
        )
        val none = HologramProviders.choose(HologramProvider.AUTO, installed())
        assertEquals(HologramProvider.NATIVE, none.provider)
        assertNull(none.from)
    }

    @Test
    fun aNamedProviderIsUsedWhenInstalledAndFallsBackWhenNot() {
        val have = installed(HologramProvider.DECENTHOLOGRAMS, HologramProvider.PROTOCOLLIB)
        assertEquals(HologramProvider.PROTOCOLLIB, HologramProviders.choose(HologramProvider.PROTOCOLLIB, have).provider)
        assertEquals(HologramProvider.NATIVE, HologramProviders.choose(HologramProvider.NATIVE, have).provider)
        val missing = HologramProviders.choose(HologramProvider.CMI, have)
        assertEquals(HologramProvider.DECENTHOLOGRAMS, missing.provider)
        assertEquals(HologramProvider.CMI, missing.from)
    }

    @Test
    fun everyProviderNamesItsPluginExceptTheOnesThatNeedNone() {
        assertEquals(
            setOf(HologramProvider.AUTO, HologramProvider.NATIVE),
            HologramProvider.entries.filter { it.plugin == null }.toSet(),
        )
        assertEquals(HologramProvider.entries.size - 1, HologramProvider.AUTO_ORDER.size)
    }

    @Test
    fun dueFiresOncePerPeriodEvenWhenThePeriodIsNoMultipleOfTheTick() {
        // A tick of 2 against a period of 5: the old `(step * 2) % 5 == 0` fired only every fifth tick.
        val fired = (1L..50L).count { HologramProviders.due(it, 2, 5) }
        assertEquals(20, fired)
        assertEquals(5, (1L..100L).count { HologramProviders.due(it, 2, 40) })
        assertTrue(HologramProviders.due(10, 2, 20))
        assertFalse(HologramProviders.due(9, 2, 20))
        assertFalse(HologramProviders.due(1, 2, 0))
    }

    @Test
    fun namesAreSafeForEveryHost() {
        assertEquals("xalicrates_world_10_64_n20", HologramProviders.name("world", 10, 64, -20))
        assertEquals("xalicrates_world_the_end_0_0_0", HologramProviders.name("World-The End", 0, 0, 0))
        assertEquals("xalicrates_w_n2147483648_0_0", HologramProviders.name("w", Int.MIN_VALUE, 0, 0))
        assertTrue(HologramProviders.name("any world!", -1, -1, -1).all { it.isLetterOrDigit() || it == '_' })
    }

    @Test
    fun colorsAreOnlyThePluginsOwnFormats() {
        val lines = HologramText.lines(["&aGreen", "&#ff8800Orange", "<red>Red</red>"])
        assertEquals(3, lines.size)
        val plain = PlainTextComponentSerializer.plainText()
        assertEquals(["Green", "Orange", "Red"], lines.map(plain::serialize))
        val legacy = HologramText.legacy(lines)
        assertEquals("§aGreen", legacy[0])
        assertEquals("§x§f§f§8§8§0§0Orange", legacy[1])
        assertTrue(legacy[2].startsWith("§c"), legacy[2])
        assertTrue(legacy.none { it.contains('&') || it.contains('<') })
    }

    @Test
    fun aColorNeverRunsIntoTheNextLine() {
        val lines = HologramText.lines(["&cfirst", "second"])
        assertEquals("§cfirst", HologramText.legacy(lines[0]))
        assertEquals("second", HologramText.legacy(lines[1]))
        val joined = HologramText.joined(lines)
        assertEquals("first\nsecond", PlainTextComponentSerializer.plainText().serialize(joined))
    }

    @Test
    fun displayDataHasTheFieldsOfATextDisplay() {
        val style = HologramStyles.default(32f)
        assertEquals(0.5f, style.viewRange)
        val fields = DisplayData.full(Component.text("hi"), style)
        assertEquals([DisplayData.BILLBOARD, DisplayData.VIEW_RANGE, DisplayData.TEXT, DisplayData.LINE_WIDTH, DisplayData.BACKGROUND, DisplayData.FLAGS], fields.map { it.index })
        assertEquals(DisplayData.BILLBOARD_CENTER, fields.first { it.index == DisplayData.BILLBOARD }.value)
        assertEquals(0x50000000, fields.first { it.index == DisplayData.BACKGROUND }.value)
        assertEquals(1.toByte(), DisplayData.flags(style))
        assertEquals(3.toByte(), DisplayData.flags(style.copy(seeThrough = true)))
        assertEquals([DataField(DisplayData.TEXT, Component.text("x"))], DisplayData.text(Component.text("x")))
        assertEquals(16f, HologramStyles.viewRangeFactor(100_000f))
    }

    // -- PacketHologram, with a recording sender and players that are only an id, a place and an online flag --

    private class Recording : PacketSender {
        override val provider = HologramProvider.PACKETEVENTS
        val events = ArrayList<String>()
        override fun spawn(viewer: Player, entityId: Int, uuid: UUID, at: Location, fields: List<DataField>) {
            events += "spawn ${viewer.name} $entityId"
        }
        override fun update(viewer: Player, entityId: Int, fields: List<DataField>) {
            events += "update ${viewer.name}"
        }
        override fun destroy(viewer: Player, entityId: Int) {
            events += "destroy ${viewer.name} $entityId"
        }
    }

    private val world: World = Proxy.newProxyInstance(World::class.java.classLoader, [World::class.java]) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> 1
            "equals" -> proxy === args?.get(0)
            "toString" -> "world"
            else -> null
        }
    } as World

    private class Who(val name: String, var x: Double, var online: Boolean = true) {
        val id: UUID = UUID.nameUUIDFromBytes(name.toByteArray())
    }

    private fun player(who: Who, world: World): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, [Player::class.java]) { proxy, method, args ->
            when (method.name) {
                "getName" -> who.name
                "getUniqueId" -> who.id
                "isOnline" -> who.online
                "getLocation" -> Location(world, who.x, 64.0, 0.0)
                "hashCode" -> who.id.hashCode()
                "equals" -> proxy === args?.get(0)
                "toString" -> who.name
                else -> null
            }
        } as Player

    private fun hologram(sender: Recording, range: Double = 10.0, removed: MutableList<Any> = ArrayList()) = PacketHologram(
        sender = sender,
        entityId = 77,
        uuid = UUID.randomUUID(),
        at = Location(world, 0.0, 64.0, 0.0),
        range = range,
        style = HologramStyles.default(32f),
        text = Component.text("crate"),
        nearby = { _, _ -> [] },
        onRemove = { removed += it },
    )

    @Test
    fun playersInRangeGetTheHologramOnceAndLoseItWhenTheyLeave() {
        val sender = Recording()
        val hologram = hologram(sender)
        val ana = Who("ana", 3.0)
        val bob = Who("bob", 30.0)
        val a = player(ana, world)
        val b = player(bob, world)

        hologram.update([a, b])
        assertEquals(["spawn ana 77"], sender.events)
        assertEquals(1, hologram.viewerCount())

        hologram.update([a, b])
        assertEquals(1, sender.events.size, "a viewer is not sent the hologram twice")

        ana.x = 80.0
        sender.events.clear()
        hologram.update([a])
        assertEquals(["destroy ana 77"], sender.events)
        assertEquals(0, hologram.viewerCount())
    }

    @Test
    fun theEdgeDoesNotBlink() {
        val sender = Recording()
        val hologram = hologram(sender, range = 10.0)
        val ana = Who("ana", 9.0)
        val a = player(ana, world)
        hologram.update([a])
        sender.events.clear()

        ana.x = 12.0 // past the range, inside the margin
        hologram.update([a])
        assertTrue(sender.events.isEmpty(), sender.events.toString())

        ana.x = 15.0 // past the margin
        hologram.update([a])
        assertEquals(["destroy ana 77"], sender.events)

        ana.x = 12.0 // not shown again until back inside the range itself
        sender.events.clear()
        hologram.update([a])
        assertTrue(sender.events.isEmpty())
    }

    @Test
    fun anOfflinePlayerIsNeverSentAnything() {
        val sender = Recording()
        val hologram = hologram(sender)
        val ana = Who("ana", 1.0)
        val a = player(ana, world)
        hologram.update([a])
        sender.events.clear()

        ana.online = false
        hologram.update([a])
        assertTrue(sender.events.isEmpty())
        assertEquals(0, hologram.viewerCount())

        hologram.update([a])
        assertTrue(sender.events.isEmpty())
    }

    @Test
    fun aForgottenPlayerIsSentTheHologramAgain() {
        val sender = Recording()
        val hologram = hologram(sender)
        val ana = Who("ana", 1.0)
        val a = player(ana, world)
        hologram.update([a])
        hologram.forget(ana.id)
        sender.events.clear()
        hologram.update([a])
        assertEquals(["spawn ana 77"], sender.events)
    }

    @Test
    fun newTextReachesEveryViewerAndRemoveDestroysForEach() {
        val sender = Recording()
        val removed = ArrayList<Any>()
        val hologram = hologram(sender, removed = removed)
        val a = player(Who("ana", 1.0), world)
        val b = player(Who("bob", 2.0), world)
        hologram.update([a, b])
        sender.events.clear()

        hologram.lines([Component.text("one"), Component.text("two")])
        assertEquals(2, sender.events.count { it.startsWith("update") })

        sender.events.clear()
        hologram.remove()
        assertEquals(setOf("destroy ana 77", "destroy bob 77"), sender.events.toSet())
        assertEquals(1, removed.size)
        assertEquals(0, hologram.viewerCount())
    }
}
