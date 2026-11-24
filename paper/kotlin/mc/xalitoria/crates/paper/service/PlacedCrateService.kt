package mc.xalitoria.crates.paper.service

import com.xalitoria.util.bukkit.runTask
import com.xalitoria.util.bukkit.runTaskLater
import com.xalitoria.util.bukkit.runTaskTimer
import com.xalitoria.util.kotlin.parseDuration
import com.xalitoria.util.message.toComponent
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.launch
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.db.repository.BlockPos
import mc.xalitoria.crates.paper.data.db.repository.PlacedCrateRecord
import mc.xalitoria.crates.paper.data.db.repository.PlacedCrateRepository
import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.CrateDisplayKind
import mc.xalitoria.crates.paper.data.model.HologramAnimationType
import mc.xalitoria.crates.paper.data.model.ObtainMethod
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages placed crates in worlds, Folia-safe ambient particles, native Paper [TextDisplay] holograms
 * (with animated prefixes & temporary reward holograms), entity displays, and lucky chest despawning.
 */
class PlacedCrateService(private val plugin: XaliCrates) {
    private val placed = ConcurrentHashMap<BlockPos, PlacedCrateRecord>()
    private val holograms = ConcurrentHashMap<BlockPos, UUID>()
    private val displayEntities = ConcurrentHashMap<BlockPos, UUID>()
    private val rewardHoloUntil = ConcurrentHashMap<BlockPos, Long>()
    private val tickCounter = AtomicLong(0L)
    private var tickTask: ScheduledTask? = null

    val holoMarkerKey = NamespacedKey(plugin, "hologram")
    val entityMarkerKey = NamespacedKey(plugin, "crate_entity")

    @Volatile
    var particlesEnabled: Boolean = true

    fun count(): Int = placed.size

    fun all(): List<PlacedCrateRecord> = placed.values.toList()

    fun countOf(crateId: String): Int = placed.values.count { it.crateId.equals(crateId, ignoreCase = true) }

    fun at(location: Location): PlacedCrateRecord? {
        val world = location.world?.name ?: return null
        return placed[BlockPos(world, location.blockX, location.blockY, location.blockZ)]
    }

    fun byEntity(entity: Entity): PlacedCrateRecord? {
        val posKey = entity.persistentDataContainer.get(entityMarkerKey, PersistentDataType.STRING) ?: return null
        return placed.values.firstOrNull { it.blockKey.asKey() == posKey }
    }

    suspend fun loadAllFromDb() {
        val records = PlacedCrateRepository.all()
        placed.clear()
        for (rec in records) {
            placed[rec.blockKey] = rec
            spawnVisuals(rec)
        }
        startTicker()
    }

    fun place(location: Location, crateId: String, expiresAt: Long = 0L, setBlock: Boolean = true): PlacedCrateRecord? {
        val world = location.world ?: return null
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: return null
        val pos = BlockPos(world.name, location.blockX, location.blockY, location.blockZ)
        val now = System.currentTimeMillis()
        val effectiveExpires = if (expiresAt > 0L) expiresAt else computeExpiry(crate, now)
        val provisional = PlacedCrateRecord(0L, world.name, pos.x, pos.y, pos.z, id, now, effectiveExpires)
        placed[pos] = provisional
        if (setBlock) {
            applyWorldDisplay(location, crate, pos)
        } else {
            spawnHologram(location, crate, pos)
        }
        plugin.scope.launch {
            val saved = PlacedCrateRepository.put(world.name, pos.x, pos.y, pos.z, id, now, effectiveExpires)
            placed[pos] = saved
        }
        return provisional
    }

    fun removeAt(location: Location, clearBlock: Boolean = false): PlacedCrateRecord? {
        val world = location.world ?: return null
        val pos = BlockPos(world.name, location.blockX, location.blockY, location.blockZ)
        val removed = placed.remove(pos) ?: return null
        cleanupVisuals(pos, location, clearBlock)
        plugin.scope.launch {
            PlacedCrateRepository.deleteAt(pos.world, pos.x, pos.y, pos.z)
        }
        return removed
    }

    fun removeAllOf(crateId: String, clearBlock: Boolean = false): Int {
        val id = crateId.lowercase()
        val targets = placed.values.filter { it.crateId == id }
        for (rec in targets) {
            placed.remove(rec.blockKey)
            val world = Bukkit.getWorld(rec.world)
            val loc = world?.let { Location(it, rec.x.toDouble(), rec.y.toDouble(), rec.z.toDouble()) }
            cleanupVisuals(rec.blockKey, loc, clearBlock)
        }
        plugin.scope.launch { PlacedCrateRepository.deleteByCrate(id) }
        return targets.size
    }

    /** Temporarily swaps the crate's hologram to show the won reward text. */
    fun showRewardHologram(record: PlacedCrateRecord, crate: CrateConfig, rewardText: String) {
        if (!crate.hologram.enabled || crate.hologram.rewardLine.isBlank()) return
        val world = Bukkit.getWorld(record.world) ?: return
        val loc = Location(world, record.x.toDouble(), record.y.toDouble(), record.z.toDouble())
        val durationMs = runCatching { parseDuration("reward-duration", crate.hologram.rewardDuration).inWholeMilliseconds }.getOrDefault(3000L)
        if (durationMs <= 0L) return
        val pos = record.blockKey
        rewardHoloUntil[pos] = System.currentTimeMillis() + durationMs
        loc.runTask(plugin) {
            val display = holograms[pos]?.let { Bukkit.getEntity(it) as? TextDisplay }
            if (display != null && display.isValid) {
                display.text(rewardText.toComponent())
            }
        }
        val ticks = (durationMs / 50L).coerceAtLeast(1L)
        loc.runTaskLater(plugin, ticks) {
            rewardHoloUntil.remove(pos)
            val current = placed[pos] ?: return@runTaskLater
            val c = plugin.crates[current.crateId] ?: return@runTaskLater
            val display = holograms[pos]?.let { Bukkit.getEntity(it) as? TextDisplay }
            if (display != null && display.isValid) {
                display.text(renderHologramLines(c, 0L))
            }
        }
    }

    fun rebuildVisuals() {
        removeAllVisuals()
        for (rec in placed.values) spawnVisuals(rec)
    }

    fun shutdown() {
        tickTask?.cancel()
        tickTask = null
        removeAllVisuals()
    }

    private fun startTicker() {
        tickTask?.cancel()
        val interval = plugin.config.data.particles.tickInterval.coerceAtLeast(1).toLong()
        particlesEnabled = plugin.config.data.particles.enabled
        tickTask = plugin.runTaskTimer(interval, interval) {
            val step = tickCounter.incrementAndGet()
            val now = System.currentTimeMillis()
            for (rec in placed.values) {
                if (rec.expiresAt > 0L && now >= rec.expiresAt) {
                    val world = Bukkit.getWorld(rec.world)
                    if (world != null) {
                        val loc = Location(world, rec.x.toDouble(), rec.y.toDouble(), rec.z.toDouble())
                        loc.runTask(plugin) { removeAt(loc, clearBlock = true) }
                    }
                    continue
                }
                val crate = plugin.crates[rec.crateId] ?: continue
                if (!crate.enabled) continue
                val world = Bukkit.getWorld(rec.world) ?: continue
                val loc = Location(world, rec.x.toDouble(), rec.y.toDouble(), rec.z.toDouble())
                val needParticles = particlesEnabled && crate.ambient.particles.isNotEmpty()
                val holoAnim = crate.hologram.animation
                val needHoloUpdate = crate.hologram.enabled &&
                    holoAnim.type != HologramAnimationType.NONE &&
                    holoAnim.prefixes.isNotEmpty() &&
                    (step * interval) % holoAnim.interval.coerceAtLeast(1) == 0L &&
                    !rewardHoloUntil.containsKey(rec.blockKey)

                if (needParticles || needHoloUpdate) {
                    loc.runTask(plugin) {
                        if (!loc.isChunkLoaded) return@runTask
                        if (needParticles) {
                            for (p in crate.ambient.particles) {
                                plugin.effects.spawnParticle(loc, p, step)
                            }
                        }
                        if (needHoloUpdate) {
                            val display = holograms[rec.blockKey]?.let { Bukkit.getEntity(it) as? TextDisplay }
                            if (display != null && display.isValid) {
                                display.text(renderHologramLines(crate, step))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun spawnVisuals(rec: PlacedCrateRecord) {
        val world = Bukkit.getWorld(rec.world) ?: return
        val crate = plugin.crates[rec.crateId] ?: return
        val loc = Location(world, rec.x.toDouble(), rec.y.toDouble(), rec.z.toDouble())
        loc.runTask(plugin) {
            if (!loc.isChunkLoaded) return@runTask
            if (plugin.config.data.crates.keepBlockConsistent) {
                applyWorldDisplay(loc, crate, rec.blockKey)
            } else {
                spawnHologram(loc, crate, rec.blockKey)
            }
        }
    }

    private fun applyWorldDisplay(loc: Location, crate: CrateConfig, pos: BlockPos) {
        loc.runTask(plugin) {
            when (crate.display.kind) {
                CrateDisplayKind.BLOCK -> {
                    val mat = Material.matchMaterial(crate.crateItem.material.substringBefore(';'))
                        ?.takeIf { it.isBlock } ?: Material.CHEST
                    if (loc.block.type != mat) loc.block.type = mat
                }
                CrateDisplayKind.ENTITY -> {
                    val existing = displayEntities[pos]?.let { Bukkit.getEntity(it) }
                    if (existing == null || !existing.isValid) {
                        val type = crate.display.entity?.let { runCatching { EntityType.valueOf(it.uppercase()) }.getOrNull() }
                            ?: EntityType.ARMOR_STAND
                        val spawnLoc = loc.clone().add(0.5, 0.0, 0.5)
                        val entity = loc.world.spawnEntity(spawnLoc, type)
                        entity.isInvulnerable = true
                        entity.isPersistent = false
                        entity.setGravity(false)
                        if (entity is LivingEntity) {
                            entity.setAI(false)
                            entity.isCollidable = false
                        }
                        crate.display.entityName?.let {
                            entity.customName(it.toComponent())
                            entity.isCustomNameVisible = true
                        }
                        entity.persistentDataContainer.set(entityMarkerKey, PersistentDataType.STRING, pos.asKey())
                        displayEntities[pos] = entity.uniqueId
                    }
                }
            }
            spawnHologram(loc, crate, pos)
        }
    }

    private fun spawnHologram(loc: Location, crate: CrateConfig, pos: BlockPos) {
        if (!crate.hologram.enabled || crate.hologram.lines.isEmpty()) return
        loc.runTask(plugin) {
            holograms.remove(pos)?.let { Bukkit.getEntity(it)?.remove() }
            val yOffset = 1.35 + plugin.config.data.holograms.offset + crate.hologram.offset
            val holoLoc = loc.clone().add(0.5, yOffset, 0.5)
            val display = loc.world.spawn(holoLoc, TextDisplay::class.java) { td ->
                td.billboard = Display.Billboard.CENTER
                td.isShadowed = true
                td.isSeeThrough = false
                td.isPersistent = false
                td.backgroundColor = Color.fromARGB(80, 0, 0, 0)
                td.viewRange = plugin.config.data.holograms.viewRange
                td.text(renderHologramLines(crate, 0L))
                td.persistentDataContainer.set(holoMarkerKey, PersistentDataType.STRING, pos.asKey())
            }
            holograms[pos] = display.uniqueId
        }
    }

    private fun renderHologramLines(crate: CrateConfig, step: Long): Component {
        val lines = crate.hologram.lines
        val anim = crate.hologram.animation
        val rendered = if (anim.type == HologramAnimationType.NONE || anim.prefixes.isEmpty()) {
            lines
        } else {
            val idx = ((step / anim.interval.coerceAtLeast(1)) % anim.prefixes.size).toInt()
            when (anim.type) {
                HologramAnimationType.TEXT_CHANGE -> {
                    val prefix = anim.prefixes[idx]
                    lines.mapIndexed { i, line -> if (i == 0) "$prefix$line" else line }
                }
                HologramAnimationType.MULTI_LINE_CHANGE -> lines.mapIndexed { i, line ->
                    val prefix = anim.prefixes[(idx + i) % anim.prefixes.size]
                    "$prefix$line"
                }
                HologramAnimationType.NONE -> lines
            }
        }
        return rendered.joinToString("\n").toComponent()
    }

    private fun cleanupVisuals(pos: BlockPos, loc: Location?, clearBlock: Boolean) {
        val holoId = holograms.remove(pos)
        val entId = displayEntities.remove(pos)
        rewardHoloUntil.remove(pos)
        if (loc != null) {
            loc.runTask(plugin) {
                holoId?.let { Bukkit.getEntity(it)?.remove() }
                entId?.let { Bukkit.getEntity(it)?.remove() }
                if (clearBlock) loc.block.type = Material.AIR
            }
        }
    }

    private fun removeAllVisuals() {
        for ([pos, id] in holograms.entries.toList()) {
            holograms.remove(pos)
            Bukkit.getEntity(id)?.let { entity -> entity.runTask(plugin) { entity.remove() } }
        }
        for ([pos, id] in displayEntities.entries.toList()) {
            displayEntities.remove(pos)
            Bukkit.getEntity(id)?.let { entity -> entity.runTask(plugin) { entity.remove() } }
        }
    }

    private fun computeExpiry(crate: CrateConfig, now: Long): Long {
        if (crate.obtain != ObtainMethod.LUCKY_CHEST) return 0L
        val raw = crate.luckyChest?.despawnAfter ?: plugin.config.data.luckyChest.despawnAfter
        val ms = runCatching { parseDuration("despawn-after", raw).inWholeMilliseconds }.getOrDefault(0L)
        return if (ms > 0L) now + ms else 0L
    }
}
