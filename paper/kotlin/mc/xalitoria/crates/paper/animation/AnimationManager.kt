package mc.xalitoria.crates.paper.animation

import com.xalitoria.util.bukkit.runTask
import com.xalitoria.util.bukkit.runTaskLater
import com.xalitoria.util.message.toComponent
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.db.repository.PlacedCrateRecord
import mc.xalitoria.crates.paper.data.model.AnimationConfig
import mc.xalitoria.crates.paper.data.model.AnimationItem
import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.CrateItem
import mc.xalitoria.crates.paper.data.model.SoundConfig
import mc.xalitoria.crates.paper.data.model.StageInventoryChange
import mc.xalitoria.crates.paper.service.NamedReward
import mc.xalitoria.crates.paper.service.RewardRoller
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.EntityEffect
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Lidded
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Marker holder identifying an active XaliCrates opening animation inventory. */
class CrateAnimationHolder(val playerId: UUID) : InventoryHolder {
    private lateinit var inv: Inventory
    fun attach(inventory: Inventory) { inv = inventory }
    override fun getInventory(): Inventory = inv
}

/**
 * Folia-safe engine for all built-in animations (`open-chest`, `roulette`, `csgo`, `menu`, `enclose`, `discover`,
 * `discover-eula`) and custom stage-based animations from `animations/<id>.yml`.
 */
class AnimationManager(private val plugin: XaliCrates) {
    private val sessions = ConcurrentHashMap<UUID, ActiveSession>()
    private val floatingItems = ConcurrentHashMap.newKeySet<UUID>()

    fun isInAnimation(playerId: UUID): Boolean = sessions.containsKey(playerId)

    fun isAnimationInventory(inventory: Inventory?): Boolean = inventory?.holder is CrateAnimationHolder

    fun start(
        player: Player,
        crateId: String,
        crate: CrateConfig,
        placed: PlacedCrateRecord?,
        openLoc: Location,
        virtual: Boolean,
        onComplete: (List<NamedReward>) -> Unit,
    ) {
        val animId = crate.opening.animation.lowercase()
        val custom = plugin.animationConfigs[animId]
        val session: ActiveSession = when {
            custom != null -> CustomStageSession(plugin, player, crateId, crate, openLoc, custom, onComplete)
            animId == "open-chest" -> OpenChestSession(plugin, player, crateId, crate, placed, openLoc, floatingItems, onComplete)
            animId == "roulette" -> RouletteSession(plugin, player, crateId, crate, openLoc, onComplete)
            animId == "csgo" -> CsgoSession(plugin, player, crateId, crate, openLoc, onComplete)
            animId == "menu" -> MenuSession(plugin, player, crateId, crate, openLoc, onComplete)
            animId == "enclose" -> EncloseSession(plugin, player, crateId, crate, openLoc, onComplete)
            animId == "discover" -> DiscoverSession(plugin, player, crateId, crate, openLoc, eulaFriendly = false, onComplete)
            animId == "discover-eula" -> DiscoverSession(plugin, player, crateId, crate, openLoc, eulaFriendly = true, onComplete)
            else -> CsgoSession(plugin, player, crateId, crate, openLoc, onComplete)
        }
        sessions[player.uniqueId] = session
        session.onCleanup = { sessions.remove(player.uniqueId, session) }
        session.start()
    }

    fun handleClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? CrateAnimationHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        sessions[holder.playerId]?.onClick(event.slot)
    }

    fun handleClose(event: InventoryCloseEvent) {
        val holder = event.inventory.holder as? CrateAnimationHolder ?: return
        sessions[holder.playerId]?.onClose(event.player as? Player ?: return)
    }

    fun handleQuit(player: Player) {
        sessions.remove(player.uniqueId)?.finishImmediately(closeInv = false)
    }

    fun shutdown() {
        for (session in sessions.values.toList()) {
            session.finishImmediately(closeInv = true)
        }
        sessions.clear()
        for (itemId in floatingItems.toList()) {
            floatingItems.remove(itemId)
            Bukkit.getEntity(itemId)?.let { entity -> entity.runTask(plugin) { entity.remove() } }
        }
    }

    internal abstract class ActiveSession(
        protected val plugin: XaliCrates,
        protected val player: Player,
        protected val crateId: String,
        protected val crate: CrateConfig,
        protected val openLoc: Location,
        private val onComplete: (List<NamedReward>) -> Unit,
    ) {
        var onCleanup: () -> Unit = {}
        @Volatile protected var finished = false
        @Volatile protected var awarded = false
        protected var inventory: Inventory? = null
        protected val won = mutableListOf<NamedReward>()

        abstract fun start()
        open fun onClick(slot: Int) {}

        open fun onClose(viewer: Player) {
            if (finished) {
                onCleanup()
                return
            }
            if (crate.opening.allowSkip) {
                finishImmediately(closeInv = false)
            } else {
                val inv = inventory ?: return
                viewer.runTaskLater(plugin, 1L) {
                    if (!finished && viewer.isOnline) viewer.openInventory(inv)
                }
            }
        }

        open fun finishImmediately(closeInv: Boolean) {
            if (finished) return
            finished = true
            if (won.isEmpty()) {
                RewardRoller.roll(crate)?.let { won.add(it) }
            }
            deliverOnce()
            if (closeInv && player.isOnline && inventory != null && player.openInventory.topInventory == inventory) {
                player.closeInventory()
            }
            onCleanup()
        }

        protected fun createInventory(size: Int): Inventory {
            val holder = CrateAnimationHolder(player.uniqueId)
            val title = crate.opening.inventoryTitle
                .replace("%crate%", plugin.displayNameOf(crateId, crate))
                .replace("%cratename%", plugin.displayNameOf(crateId, crate))
                .toComponent()
            val inv = Bukkit.createInventory(holder, size.coerceIn(9, 54), title)
            holder.attach(inv)
            inventory = inv
            return inv
        }

        protected fun rewardStack(reward: NamedReward): ItemStack =
            plugin.items.buildRewardPreviewItem(crateId, reward.id) ?: ItemStack(Material.DIAMOND)

        protected fun fillerStack(materialName: String, name: String = " "): ItemStack {
            val mat = Material.matchMaterial(materialName.substringBefore(';')) ?: Material.GRAY_STAINED_GLASS_PANE
            val stack = ItemStack(if (mat.isItem) mat else Material.GRAY_STAINED_GLASS_PANE)
            stack.editMeta { it.customName(name.toComponent().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)) }
            return stack
        }

        protected fun buildAnimItem(item: AnimationItem, slot: Int = 0): ItemStack {
            val spec = CrateItem(
                material = item.material,
                amount = if (item.amountFromSlot) (slot + 1).coerceIn(1, 64) else 1,
                name = item.name,
                lore = item.lore,
                glow = item.glow,
                customModelData = item.customModelData,
                itemModel = item.itemModel,
            )
            return plugin.items.build(spec)
        }

        protected fun deliverOnce() {
            if (awarded) return
            awarded = true
            val total = won + RewardRoller.guaranteed(crate)
            onComplete(total)
        }

        protected fun scheduleAutoClose(delayTicks: Long) {
            if (!crate.opening.autoClose) {
                onCleanup()
                return
            }
            player.runTaskLater(plugin, delayTicks.coerceAtLeast(1L)) {
                if (player.isOnline && inventory != null && player.openInventory.topInventory == inventory) {
                    player.closeInventory()
                }
                onCleanup()
            }
        }
    }

    /** Block chest lid opens, reward floats above it with pickup disabled, then closes and awards. */
    private class OpenChestSession(
        plugin: XaliCrates,
        player: Player,
        crateId: String,
        crate: CrateConfig,
        private val placed: PlacedCrateRecord?,
        openLoc: Location,
        private val floatingItems: MutableSet<UUID>,
        onComplete: (List<NamedReward>) -> Unit,
    ) : ActiveSession(plugin, player, crateId, crate, openLoc, onComplete) {
        private var itemId: UUID? = null

        override fun start() {
            val picked = RewardRoller.roll(crate) ?: run { finishImmediately(false); return }
            won.add(picked)
            val cfg = plugin.config.data.animations.openChest
            val duration = cfg.openDurationTicks.coerceAtLeast(10).toLong()

            openLoc.runTask(plugin) {
                val state = openLoc.block.state
                if (state is Lidded) state.open()
                val spawnLoc = openLoc.clone().add(0.5, 1.05, 0.5)
                val stack = rewardStack(picked)
                val drop: Item = openLoc.world.dropItem(spawnLoc, stack)
                drop.pickupDelay = Int.MAX_VALUE
                drop.isUnlimitedLifetime = true
                drop.velocity = Vector(0.0, 0.08, 0.0)
                drop.customName((picked.config.displayItem.name ?: picked.id).toComponent())
                drop.isCustomNameVisible = true
                itemId = drop.uniqueId
                floatingItems.add(drop.uniqueId)

                if (cfg.earlyRewardHologram && placed != null && crate.hologram.rewardLine.isNotBlank()) {
                    val text = plugin.effects.fillPlaceholders(player, crateId, crate, won, crate.hologram.rewardLine)
                    plugin.placed.showRewardHologram(placed, crate, text)
                }

                openLoc.runTaskLater(plugin, duration) {
                    val currentState = openLoc.block.state
                    if (currentState is Lidded) currentState.close()
                    itemId?.let { id ->
                        floatingItems.remove(id)
                        Bukkit.getEntity(id)?.remove()
                    }
                    player.runTask(plugin) {
                        if (!finished) {
                            finished = true
                            deliverOnce()
                            onCleanup()
                        }
                    }
                }
            }
        }

        override fun finishImmediately(closeInv: Boolean) {
            itemId?.let { id ->
                floatingItems.remove(id)
                Bukkit.getEntity(id)?.let { entity -> entity.runTask(plugin) { entity.remove() } }
            }
            super.finishImmediately(closeInv)
        }
    }

    /** Cycling border panes with a spinning reward in the center slot that decelerates to the winning reward. */
    private class RouletteSession(
        plugin: XaliCrates,
        player: Player,
        crateId: String,
        crate: CrateConfig,
        openLoc: Location,
        onComplete: (List<NamedReward>) -> Unit,
    ) : ActiveSession(plugin, player, crateId, crate, openLoc, onComplete) {
        private val cfg = plugin.config.data.animations.roulette
        private var currentDelay = cfg.tileUpdateTicks.coerceAtLeast(1)
        private var stepsAtSpeed = 0
        private var currentShown: NamedReward? = null

        override fun start() {
            val inv = createInventory(27)
            player.openInventory(inv)
            step()
        }

        private fun step() {
            if (finished || !player.isOnline) return
            val inv = inventory ?: return
            val fillers = cfg.fillerBlocks.ifEmpty { listOf("gray_stained_glass_pane") }
            for (slot in 0 until 27) {
                if (slot != 13) inv.setItem(slot, fillerStack(fillers.random()))
            }
            val next = RewardRoller.roll(crate)
            if (next != null) {
                currentShown = next
                inv.setItem(13, rewardStack(next))
            }
            plugin.effects.playSound(player, player.location, cfg.tickSound)

            stepsAtSpeed++
            if (stepsAtSpeed >= cfg.stepsPerSpeed.coerceAtLeast(1)) {
                stepsAtSpeed = 0
                currentDelay++
            }
            if (currentDelay >= cfg.finalTickLength.coerceAtLeast(2)) {
                finished = true
                currentShown?.let { won.add(it) }
                deliverOnce()
                scheduleAutoClose(40L)
            } else {
                player.runTaskLater(plugin, currentDelay.toLong()) { step() }
            }
        }
    }

    /** CS:GO horizontal scrolling reel across row 2 with top & bottom indicator markers. */
    private class CsgoSession(
        plugin: XaliCrates,
        player: Player,
        crateId: String,
        crate: CrateConfig,
        openLoc: Location,
        onComplete: (List<NamedReward>) -> Unit,
    ) : ActiveSession(plugin, player, crateId, crate, openLoc, onComplete) {
        private val cfg = plugin.config.data.animations.csgo
        private val reel = ArrayDeque<NamedReward>()
        private var currentDelay = cfg.tileUpdateTicks.coerceAtLeast(1)
        private var stepsAtSpeed = 0

        override fun start() {
            val inv = createInventory(27)
            val indicator = fillerStack(cfg.indicatorMaterial, "&e▼ Winner ▼")
            inv.setItem(4, indicator)
            inv.setItem(22, fillerStack(cfg.indicatorMaterial, "&e▲ Winner ▲"))
            repeat(9) { RewardRoller.roll(crate)?.let { reel.addLast(it) } }
            player.openInventory(inv)
            step()
        }

        private fun step() {
            if (finished || !player.isOnline) return
            val inv = inventory ?: return
            val fillers = cfg.fillerBlocks.ifEmpty { listOf("gray_stained_glass_pane") }
            for (slot in 0 until 27) {
                if (slot in 9..17 || slot == 4 || slot == 22) continue
                inv.setItem(slot, fillerStack(fillers.random()))
            }
            if (reel.isNotEmpty()) {
                reel.removeFirst()
                RewardRoller.roll(crate)?.let { reel.addLast(it) }
                reel.forEachIndexed { idx, reward -> inv.setItem(9 + idx, rewardStack(reward)) }
            }
            plugin.effects.playSound(player, player.location, cfg.tickSound)

            stepsAtSpeed++
            if (stepsAtSpeed >= cfg.stepsPerSpeed.coerceAtLeast(1)) {
                stepsAtSpeed = 0
                currentDelay++
            }
            if (currentDelay >= cfg.finalTickLength.coerceAtLeast(2)) {
                finished = true
                reel.getOrNull(4)?.let { won.add(it) }
                deliverOnce()
                scheduleAutoClose(cfg.closeDelayTicks.toLong().coerceAtLeast(20L))
            } else {
                player.runTaskLater(plugin, currentDelay.toLong()) { step() }
            }
        }
    }

    /** Instant chest menu revealing 1..N rolled rewards at once. */
    private class MenuSession(
        plugin: XaliCrates,
        player: Player,
        crateId: String,
        crate: CrateConfig,
        openLoc: Location,
        onComplete: (List<NamedReward>) -> Unit,
    ) : ActiveSession(plugin, player, crateId, crate, openLoc, onComplete) {
        override fun start() {
            val cfg = plugin.config.data.animations.menu
            val rows = cfg.rows.coerceIn(1, 6)
            val inv = createInventory(rows * 9)
            val min = cfg.minRewards.coerceAtLeast(1)
            val max = cfg.maxRewards.coerceAtLeast(min)
            val count = if (min == max) min else Random.nextInt(min, max + 1)
            val slots = (0 until inv.size).shuffled().take(count)
            for (slot in slots) {
                val r = RewardRoller.roll(crate, won) ?: continue
                won.add(r)
                inv.setItem(slot, rewardStack(r))
            }
            player.openInventory(inv)
            finished = true
            deliverOnce()
            scheduleAutoClose(60L)
        }
    }

    /** Outer slots progressively enclose inward until only the final reward slot(s) remain. */
    private class EncloseSession(
        plugin: XaliCrates,
        player: Player,
        crateId: String,
        crate: CrateConfig,
        openLoc: Location,
        onComplete: (List<NamedReward>) -> Unit,
    ) : ActiveSession(plugin, player, crateId, crate, openLoc, onComplete) {
        private val cfg = plugin.config.data.animations.enclose
        private val openSlots = mutableListOf<Int>()
        private val slotRewards = HashMap<Int, NamedReward>()

        override fun start() {
            val rows = cfg.rows.coerceIn(1, 6)
            val inv = createInventory(rows * 9)
            for (slot in 0 until inv.size) {
                val r = RewardRoller.roll(crate) ?: continue
                slotRewards[slot] = r
                inv.setItem(slot, rewardStack(r))
                openSlots.add(slot)
            }
            openSlots.shuffle()
            player.openInventory(inv)
            step()
        }

        private fun step() {
            if (finished || !player.isOnline) return
            val inv = inventory ?: return
            val keep = cfg.rewardAmount.coerceIn(1, inv.size)
            if (openSlots.size <= keep) {
                finished = true
                for (slot in openSlots) slotRewards[slot]?.let { won.add(it) }
                deliverOnce()
                scheduleAutoClose(40L)
                return
            }
            val removeSlot = openSlots.removeAt(0)
            inv.setItem(removeSlot, fillerStack(cfg.fillMaterial))
            plugin.effects.playSound(player, player.location, cfg.tickSound)
            player.runTaskLater(plugin, cfg.updateTicks.coerceAtLeast(1).toLong()) { step() }
        }
    }

    /** Interactive pick-your-chests animation (`discover` and `discover-eula`). */
    private class DiscoverSession(
        plugin: XaliCrates,
        player: Player,
        crateId: String,
        crate: CrateConfig,
        openLoc: Location,
        private val eulaFriendly: Boolean,
        onComplete: (List<NamedReward>) -> Unit,
    ) : ActiveSession(plugin, player, crateId, crate, openLoc, onComplete) {
        private val cfg = plugin.config.data.animations.discover
        private val targetPicks = run {
            val min = cfg.minRewards.coerceAtLeast(1)
            val max = cfg.maxRewards.coerceAtLeast(min)
            if (min == max) min else Random.nextInt(min, max + 1)
        }
        private val pickedSlots = LinkedHashMap<Int, NamedReward>()
        private val revealedSlots = HashSet<Int>()
        private var revealPhase = false

        override fun start() {
            val rows = cfg.rows.coerceIn(1, 6)
            val inv = createInventory(rows * 9)
            drawCovers(inv)
            player.openInventory(inv)
        }

        private fun drawCovers(inv: Inventory) {
            val remaining = (targetPicks - pickedSlots.size).coerceAtLeast(0)
            for (slot in 0 until inv.size) {
                if (slot in pickedSlots) continue
                val stack = plugin.items.build(
                    CrateItem(
                        material = cfg.coverMaterial,
                        amount = if (cfg.showSlotCount) (slot + 1).coerceIn(1, 64) else 1,
                        name = cfg.coverName.replace("%number%", (slot + 1).toString()),
                        lore = [cfg.coverLore.replace("%remaining%", remaining.toString())],
                    ),
                )
                inv.setItem(slot, stack)
            }
        }

        override fun onClick(slot: Int) {
            if (finished) return
            val inv = inventory ?: return
            if (slot !in 0 until inv.size) return
            if (!revealPhase) {
                if (slot in pickedSlots) return
                val reward = RewardRoller.roll(crate, pickedSlots.values.toList()) ?: return
                pickedSlots[slot] = reward
                plugin.effects.playSound(player, player.location, cfg.clickSound)
                if (pickedSlots.size < targetPicks) {
                    inv.setItem(slot, fillerStack(cfg.pickedMaterial, cfg.pickedName))
                    drawCovers(inv)
                } else {
                    revealPhase = true
                    if (eulaFriendly) {
                        for (s in 0 until inv.size) {
                            if (s !in pickedSlots) inv.setItem(s, null)
                        }
                    }
                    for (s in pickedSlots.keys) {
                        inv.setItem(s, fillerStack(cfg.pickedMaterial, cfg.unlockName))
                    }
                }
            } else {
                val reward = pickedSlots[slot] ?: return
                if (!revealedSlots.add(slot)) return
                inv.setItem(slot, rewardStack(reward))
                plugin.effects.playSound(player, player.location, cfg.uncoverSound)
                if (revealedSlots.size >= pickedSlots.size) {
                    finished = true
                    won.addAll(pickedSlots.values)
                    deliverOnce()
                    scheduleAutoClose(60L)
                }
            }
        }

        override fun finishImmediately(closeInv: Boolean) {
            if (!finished) {
                if (pickedSlots.isNotEmpty()) won.addAll(pickedSlots.values)
                else repeat(targetPicks) { RewardRoller.roll(crate, won)?.let { won.add(it) } }
            }
            super.finishImmediately(closeInv)
        }
    }

    /** Executes a custom stage-based YAML animation from `animations/<id>.yml`. */
    private class CustomStageSession(
        plugin: XaliCrates,
        player: Player,
        crateId: String,
        crate: CrateConfig,
        openLoc: Location,
        private val anim: AnimationConfig,
        onComplete: (List<NamedReward>) -> Unit,
    ) : ActiveSession(plugin, player, crateId, crate, openLoc, onComplete) {
        private var stageIndex = 0 // 0-based into anim.stages
        private var currentGrid: List<List<String>> = emptyList()
        private val persistentRewards = HashMap<String, NamedReward>()
        private val shownAtSlot = HashMap<Int, NamedReward>()
        private val selectedSlots = LinkedHashMap<Int, NamedReward>()
        private val revealedSelected = HashSet<Int>()
        private val revealedPool = ArrayDeque<NamedReward>()
        private var temporarilyClosed = false

        override fun start() {
            if (anim.stages.isEmpty()) {
                finishImmediately(false)
                return
            }
            val inv = createInventory(anim.inventorySize)
            player.openInventory(inv)
            runStage(0, isRepeat = false)
        }

        override fun onClose(viewer: Player) {
            if (temporarilyClosed) return
            super.onClose(viewer)
        }

        private fun runStage(index: Int, isRepeat: Boolean) {
            if (finished || !player.isOnline) return
            val stage = anim.stages.getOrNull(index) ?: run { finishStageAnimation(); return }
            if (!isRepeat) stageIndex = index

            for (resetSym in stage.resetRewards) persistentRewards.remove(resetSym)
            if (stage.grid.isNotEmpty()) renderGrid(stage.grid)

            when (stage.inventoryChange) {
                StageInventoryChange.CLOSE -> {
                    temporarilyClosed = true
                    player.closeInventory()
                }
                StageInventoryChange.OPEN -> {
                    temporarilyClosed = false
                    inventory?.let { player.openInventory(it) }
                }
                StageInventoryChange.NONE -> {}
            }

            if (stage.totemEffect) player.playEffect(EntityEffect.PROTECTED_FROM_DEATH)
            for (pot in stage.potionEffects) {
                val type = io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.MOB_EFFECT)
                    .get(NamespacedKey.minecraft(pot.effect.lowercase())) ?: continue
                player.addPotionEffect(PotionEffect(type, pot.duration, pot.amplifier))
            }

            val sound = stage.sound ?: anim.sounds.stage
            if (sound != null) plugin.effects.playSound(player, player.location, sound)

            val stay = if (stage.stayTicks == 0) anim.defaultFrameTicks else stage.stayTicks
            if (stage.repeatStage > 0 && stage.repeatTimes > 0) {
                val target = (stage.repeatStage - 1).coerceIn(0, anim.stages.lastIndex)
                val stepDelay = stay.coerceAtLeast(1)
                for (i in 0 until stage.repeatTimes) {
                    player.runTaskLater(plugin, (stepDelay * i).toLong().coerceAtLeast(1L)) {
                        runStage(target, isRepeat = true)
                    }
                }
                if (!isRepeat) {
                    val nextDelay = (stepDelay * stage.repeatTimes).toLong().coerceAtLeast(1L)
                    scheduleNextOrEnd(index, nextDelay)
                }
                return
            }

            if (!isRepeat) {
                if (stay < 0) return // wait for player interaction (select/reveal/next-stage)
                scheduleNextOrEnd(index, stay.toLong().coerceAtLeast(1L))
            }
        }

        private fun scheduleNextOrEnd(currentIndex: Int, delayTicks: Long) {
            if (currentIndex + 1 < anim.stages.size) {
                player.runTaskLater(plugin, delayTicks) { runStage(currentIndex + 1, isRepeat = false) }
            } else {
                player.runTaskLater(plugin, delayTicks) { finishStageAnimation() }
            }
        }

        private fun renderGrid(rows: List<String>) {
            val inv = inventory ?: return
            inv.clear()
            shownAtSlot.clear()
            val parsed = rows.map { row -> row.split(',').map { it.trim() } }
            currentGrid = parsed
            val sym = anim.symbols
            for (r in parsed.indices) {
                val cols = parsed[r]
                for (c in cols.indices) {
                    val slot = r * 9 + c
                    if (slot >= inv.size) continue
                    val token = cols[c]
                    if (token.isEmpty() || token.equals(sym.air, ignoreCase = true)) continue
                    val stack = resolveSymbol(token, slot)
                    if (stack != null) inv.setItem(slot, stack)
                }
            }
        }

        private fun resolveSymbol(token: String, slot: Int): ItemStack? {
            val sym = anim.symbols
            anim.fillers[token]?.let { return buildAnimItem(it, slot) }
            return when {
                token.equals(sym.randomFiller, ignoreCase = true) -> {
                    val values = anim.fillers.values.toList()
                    if (values.isEmpty()) null else buildAnimItem(values.random(), slot)
                }
                token.equals(sym.randomReward, ignoreCase = true) -> {
                    val reward = RewardRoller.roll(crate) ?: return null
                    shownAtSlot[slot] = reward
                    rewardStack(reward)
                }
                token in sym.rewardSymbols -> {
                    val reward = persistentRewards.getOrPut(token) { RewardRoller.roll(crate) ?: return null }
                    shownAtSlot[slot] = reward
                    rewardStack(reward)
                }
                token.equals(sym.selectReward, ignoreCase = true) -> {
                    if (slot in selectedSlots) buildAnimItem(anim.selectedItem, slot)
                    else buildAnimItem(anim.closedSelectItem, slot)
                }
                token.equals(sym.openSelectedReward, ignoreCase = true) -> {
                    val reward = selectedSlots[slot]
                    if (reward == null) {
                        resolveUnselectedItem(slot)
                    } else if (anim.openedSelectedItem.revealInstantly || slot in revealedSelected) {
                        shownAtSlot[slot] = reward
                        rewardStack(reward)
                    } else {
                        val byRarity = anim.openedSelectedItem.byRarity.entries
                            .firstOrNull { it.key.equals(reward.config.rarity, ignoreCase = true) }?.value
                        buildAnimItem(byRarity ?: anim.openedSelectedItem.item, slot)
                    }
                }
                token.equals(sym.revealReward, ignoreCase = true) -> buildAnimItem(anim.revealItem, slot)
                token.equals(sym.selectedRevealReward, ignoreCase = true) -> {
                    val reward = revealedPool.removeFirstOrNull() ?: return null
                    shownAtSlot[slot] = reward
                    rewardStack(reward)
                }
                token.equals(sym.nextStage, ignoreCase = true) -> buildAnimItem(anim.nextStageItem, slot)
                else -> null
            }
        }

        private fun resolveUnselectedItem(slot: Int): ItemStack? {
            val u = anim.unselectedItem
            if (u.randomFiller && anim.fillers.isNotEmpty()) return buildAnimItem(anim.fillers.values.random(), slot)
            if (!u.filler.isNullOrBlank()) anim.fillers[u.filler]?.let { return buildAnimItem(it, slot) }
            if (u.item.material.equals("air", ignoreCase = true)) return null
            return buildAnimItem(u.item, slot)
        }

        override fun onClick(slot: Int) {
            if (finished) return
            val inv = inventory ?: return
            val row = slot / 9
            val col = slot % 9
            val token = currentGrid.getOrNull(row)?.getOrNull(col) ?: return
            val sym = anim.symbols

            when {
                token.equals(sym.selectReward, ignoreCase = true) -> {
                    if (slot in selectedSlots) return
                    val reward = RewardRoller.roll(crate, selectedSlots.values.toList()) ?: return
                    selectedSlots[slot] = reward
                    anim.sounds.click?.let { plugin.effects.playSound(player, player.location, it) }
                    inv.setItem(slot, buildAnimItem(anim.selectedItem, slot))
                    if (selectedSlots.size >= anim.minSelect) {
                        advanceInteractiveStage()
                    }
                }
                token.equals(sym.openSelectedReward, ignoreCase = true) -> {
                    val reward = selectedSlots[slot] ?: return
                    if (!revealedSelected.add(slot)) return
                    anim.sounds.click?.let { plugin.effects.playSound(player, player.location, it) }
                    shownAtSlot[slot] = reward
                    inv.setItem(slot, rewardStack(reward))
                    if (revealedSelected.size >= selectedSlots.size) {
                        advanceInteractiveStage()
                    }
                }
                token.equals(sym.revealReward, ignoreCase = true) -> {
                    if (slot in shownAtSlot) return
                    val reward = RewardRoller.roll(crate, revealedPool.toList()) ?: return
                    revealedPool.addLast(reward)
                    shownAtSlot[slot] = reward
                    anim.sounds.click?.let { plugin.effects.playSound(player, player.location, it) }
                    inv.setItem(slot, rewardStack(reward))
                }
                token.equals(sym.nextStage, ignoreCase = true) -> {
                    anim.sounds.click?.let { plugin.effects.playSound(player, player.location, it) }
                    advanceInteractiveStage()
                }
            }
        }

        private fun advanceInteractiveStage() {
            if (stageIndex + 1 < anim.stages.size) {
                runStage(stageIndex + 1, isRepeat = false)
            } else {
                finishStageAnimation()
            }
        }

        private fun finishStageAnimation() {
            if (finished) return
            finished = true
            collectStageRewards()
            anim.sounds.end?.let { plugin.effects.playSound(player, player.location, it) }
            for (p in anim.endEffects.particles) {
                plugin.effects.spawnParticle(player.location, p, step = 0L)
            }
            deliverOnce()
            scheduleAutoClose(anim.autoCloseTicks.toLong())
        }

        private fun collectStageRewards() {
            if (selectedSlots.isNotEmpty()) {
                won.addAll(selectedSlots.values)
                return
            }
            val finalStage = anim.stages.getOrNull(stageIndex)
            if (finalStage != null && finalStage.rewardSlots.isNotEmpty()) {
                for (coord in finalStage.rewardSlots) {
                    val parts = coord.split(',').map { it.trim().toIntOrNull() }
                    if (parts.size == 2 && parts[0] != null && parts[1] != null) {
                        val slot = (parts[0]!! - 1) * 9 + (parts[1]!! - 1)
                        shownAtSlot[slot]?.let { won.add(it) }
                    }
                }
            }
            if (won.isEmpty() && revealedPool.isNotEmpty()) won.addAll(revealedPool)
            if (won.isEmpty()) RewardRoller.roll(crate)?.let { won.add(it) }
        }
    }
}
