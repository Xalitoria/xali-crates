package mc.xalitoria.crates.paper.service

import com.xalitoria.util.bukkit.runConsoleCommand
import com.xalitoria.util.bukkit.runTask
import com.xalitoria.util.kotlin.parseDuration
import com.xalitoria.util.message.send
import com.xalitoria.util.message.toComponent
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.model.CrateActionConfig
import mc.xalitoria.crates.paper.data.model.CrateActionType
import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.FireworkConfig
import mc.xalitoria.crates.paper.data.model.ParticleAnimation
import mc.xalitoria.crates.paper.data.model.ParticleConfig
import mc.xalitoria.crates.paper.data.model.SoundConfig
import mc.xalitoria.crates.paper.external.CratesExpansion
import net.kyori.adventure.title.Title
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import java.time.Duration
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Plays Folia-safe particles (including all 7 geometric animation patterns), sounds, fireworks, and actions
 * (messages, broadcasts, titles, actionbars, console/player commands) with tier override resolution.
 */
class EffectPlayer(private val plugin: XaliCrates) {
    fun playPreOpen(player: Player, crateId: String, crate: CrateConfig) {
        val pre = crate.effects.actions.filter { it.type == CrateActionType.PRE_BROADCAST }
        if (pre.isEmpty()) return
        executeActions(player, crateId, crate, pre, emptyList())
    }

    fun playOpenEffects(player: Player, location: Location, crateId: String, crate: CrateConfig, won: List<NamedReward>) {
        val rarity = won.firstOrNull()?.config?.rarity?.lowercase() ?: "default"
        val tier = crate.tiers.entries.firstOrNull { it.key.equals(rarity, ignoreCase = true) }?.value

        val actions = mutableListOf<CrateActionConfig>()
        val sounds = mutableListOf<SoundConfig>()
        val particles = mutableListOf<ParticleConfig>()
        val fireworks = mutableListOf<FireworkConfig>()

        val includeDefaults = tier == null || !tier.overrideDefaults
        if (includeDefaults) {
            actions.addAll(crate.effects.actions.filter { it.type != CrateActionType.PRE_BROADCAST })
            sounds.addAll(crate.effects.sounds)
            particles.addAll(crate.effects.particles)
            fireworks.addAll(crate.effects.fireworks)
        }
        if (tier != null) {
            actions.addAll(tier.actions.filter { it.type != CrateActionType.PRE_BROADCAST })
            sounds.addAll(tier.sounds)
            particles.addAll(tier.particles)
            fireworks.addAll(tier.fireworks)
        }

        executeActions(player, crateId, crate, actions, won)
        sounds.forEach { playSound(player, location, it) }
        location.runTask(plugin) {
            particles.forEach { spawnParticle(location, it, step = 0L) }
            fireworks.forEach { launchFirework(location, it) }
        }
    }

    fun playSound(player: Player, location: Location, sound: SoundConfig) {
        val key = sound.sound.trim().lowercase()
        if (key.isEmpty()) return
        player.playSound(location, key, sound.volume.coerceAtLeast(0f), sound.pitch.coerceIn(0f, 2f))
    }

    fun spawnParticle(blockLoc: Location, cfg: ParticleConfig, step: Long) {
        val world = blockLoc.world ?: return
        val particle = resolveParticle(cfg.type) ?: return
        val maxDist = plugin.config.data.particles.viewDistance
        val maxDistSq = maxDist * maxDist
        val center = blockLoc.clone().add(0.5 + cfg.offsetX, 0.5 + cfg.offsetY, 0.5 + cfg.offsetZ)

        val nearby = world.players.any { it.location.distanceSquared(center) <= maxDistSq }
        if (!nearby) return

        val data: Any? = if (particle == Particle.DUST) {
            val c = cfg.color?.let(ItemFactory::parseColor) ?: Color.RED
            Particle.DustOptions(c, 1.0f)
        } else null

        val points = computePatternPoints(center, cfg, step)
        for (pt in points) {
            if (cfg.animation == ParticleAnimation.NONE) {
                world.spawnParticle(particle, pt, cfg.amount, cfg.rangeX, cfg.rangeY, cfg.rangeZ, cfg.speed, data)
            } else {
                world.spawnParticle(particle, pt, cfg.amount, 0.0, 0.0, 0.0, cfg.speed, data)
            }
        }
    }

    fun launchFirework(blockLoc: Location, cfg: FireworkConfig) {
        val world = blockLoc.world ?: return
        val loc = blockLoc.clone().add(0.5, 1.0, 0.5)
        world.spawn(loc, Firework::class.java) { fw ->
            val meta = fw.fireworkMeta
            meta.power = cfg.power.coerceIn(0, 4)
            meta.addEffect(ItemFactory.buildFireworkEffect(cfg))
            fw.fireworkMeta = meta
        }
    }

    private fun executeActions(
        player: Player,
        crateId: String,
        crate: CrateConfig,
        actions: List<CrateActionConfig>,
        won: List<NamedReward>,
    ) {
        var titleText: String? = null
        var subtitleText: String? = null
        for (action in actions) {
            val formatted = fillPlaceholders(player, crateId, crate, won, action.value)
            when (action.type) {
                CrateActionType.MESSAGE -> player send formatted
                CrateActionType.BROADCAST, CrateActionType.PRE_BROADCAST -> {
                    val comp = formatted.toComponent()
                    plugin.server.onlinePlayers.forEach { it.sendMessage(comp) }
                }
                CrateActionType.ACTIONBAR -> player.sendActionBar(formatted.toComponent())
                CrateActionType.TITLE -> titleText = formatted
                CrateActionType.SUBTITLE -> subtitleText = formatted
                CrateActionType.COMMAND -> plugin.runConsoleCommand(formatted.removePrefix("/"))
                CrateActionType.PLAYER_COMMAND -> player.runTask(plugin) { player.performCommand(formatted.removePrefix("/")) }
            }
        }
        if (titleText != null || subtitleText != null) {
            val c = plugin.config.data.crates
            val fadeIn = runCatching { parseDuration("title-fade-in", c.titleFadeIn).inWholeMilliseconds }.getOrDefault(0L)
            val stay = runCatching { parseDuration("title-stay", c.titleStay).inWholeMilliseconds }.getOrDefault(4000L)
            val fadeOut = runCatching { parseDuration("title-fade-out", c.titleFadeOut).inWholeMilliseconds }.getOrDefault(1000L)
            val times = Title.Times.times(Duration.ofMillis(fadeIn), Duration.ofMillis(stay), Duration.ofMillis(fadeOut))
            val title = Title.title((titleText ?: "").toComponent(), (subtitleText ?: "").toComponent(), times)
            player.showTitle(title)
        }
    }

    fun fillPlaceholders(player: Player, crateId: String, crate: CrateConfig, won: List<NamedReward>, text: String): String {
        val rewardNames = won.joinToString(", ") { it.config.displayItem.name ?: it.id }
        val rarityKey = won.firstOrNull()?.config?.rarity ?: "default"
        val rarityDisplay = crate.tiers[rarityKey]?.displayName ?: rarityKey
        val keyName = crate.key.item.name ?: crate.key.item.material
        val result = text
            .replace("%player%", player.name)
            .replace("%name%", player.name)
            .replace("{player}", player.name)
            .replace("{name}", player.name)
            .replace("%displayname%", player.name)
            .replace("%crate%", crateId)
            .replace("%cratename%", plugin.displayNameOf(crateId, crate))
            .replace("%key%", keyName)
            .replace("%keyname%", keyName)
            .replace("%reward%", rewardNames)
            .replace("%rewards%", rewardNames)
            .replace("%rarity%", rarityDisplay)
            .replace("%tier%", rarityDisplay)
        return if (plugin.hasPlaceholders) CratesExpansion.parse(player, result) else result
    }

    companion object {
        /** Pure math helper computing particle coordinates for each of the 7 animation patterns. */
        fun computePatternPoints(center: Location, cfg: ParticleConfig, step: Long): List<Location> {
            val radius = cfg.rangeX.coerceAtLeast(0.3)
            val height = cfg.rangeY.coerceAtLeast(1.0)
            val angle = (step % 40) * (2.0 * PI / 40.0)
            return when (cfg.animation) {
                ParticleAnimation.NONE -> listOf(center)
                ParticleAnimation.CIRCLE -> listOf(
                    center.clone().add(cos(angle) * radius, 0.0, sin(angle) * radius),
                )
                ParticleAnimation.SPIRAL -> {
                    val y = ((step % 40) / 40.0) * height
                    listOf(center.clone().add(cos(angle * 2) * radius, y, sin(angle * 2) * radius))
                }
                ParticleAnimation.DOUBLE_SPIRAL -> {
                    val y = ((step % 40) / 40.0) * height
                    listOf(
                        center.clone().add(cos(angle * 2) * radius, y, sin(angle * 2) * radius),
                        center.clone().add(cos(angle * 2 + PI) * radius, y, sin(angle * 2 + PI) * radius),
                    )
                }
                ParticleAnimation.GROWING_SPIRAL -> {
                    val frac = (step % 40) / 40.0
                    val r = radius * (0.2 + 0.8 * frac)
                    listOf(center.clone().add(cos(angle * 2) * r, frac * height, sin(angle * 2) * r))
                }
                ParticleAnimation.TILTED_RINGS -> {
                    val tilt = PI / 4.0
                    val x = cos(angle) * radius
                    val y = sin(angle) * radius * sin(tilt)
                    val z = sin(angle) * radius * cos(tilt)
                    listOf(center.clone().add(x, y, z))
                }
                ParticleAnimation.OFFSET_TILTED_RINGS -> {
                    val tilt = PI / 4.0
                    val x1 = cos(angle) * radius
                    val y1 = sin(angle) * radius * sin(tilt)
                    val z1 = sin(angle) * radius * cos(tilt)
                    val x2 = cos(angle + PI) * radius
                    val y2 = -sin(angle + PI) * radius * sin(tilt)
                    val z2 = sin(angle + PI) * radius * cos(tilt)
                    listOf(center.clone().add(x1, y1, z1), center.clone().add(x2, y2, z2))
                }
            }
        }

        fun resolveParticle(raw: String): Particle? {
            val normalized = raw.trim().lowercase().removePrefix("minecraft:")
            val legacyMap = mapOf(
                "villager_happy" to "happy_villager",
                "villager_angry" to "angry_villager",
                "smoke_normal" to "smoke",
                "crit_magic" to "enchanted_hit",
                "explosion_huge" to "explosion_emitter",
                "spell_witch" to "witch",
                "spell_instant" to "instant_effect",
                "fireworks_spark" to "firework",
                "redstone" to "dust",
                "suspended_depth" to "underwater",
            )
            val key = NamespacedKey.minecraft(legacyMap[normalized] ?: normalized)
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.PARTICLE_TYPE).get(key)
        }
    }
}
