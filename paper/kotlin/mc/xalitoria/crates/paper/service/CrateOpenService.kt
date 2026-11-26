package mc.xalitoria.crates.paper.service

import com.xalitoria.util.bukkit.runConsoleCommand
import com.xalitoria.util.bukkit.runTask
import com.xalitoria.util.kotlin.parseDuration
import com.xalitoria.util.message.send
import kotlinx.coroutines.launch
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.constants.Permissions
import mc.xalitoria.crates.paper.data.db.repository.CooldownRepository
import mc.xalitoria.crates.paper.data.db.repository.HistoryRecord
import mc.xalitoria.crates.paper.data.db.repository.HistoryRepository
import mc.xalitoria.crates.paper.data.db.repository.PlacedCrateRecord
import mc.xalitoria.crates.paper.data.db.repository.VirtualBalanceRepository
import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.FullInventoryPolicy
import mc.xalitoria.crates.paper.data.model.ObtainMethod
import mc.xalitoria.crates.paper.data.model.RedeemDeliveryMode
import net.xalitoria.crates.CrateOpenEvent
import net.xalitoria.crates.CratePreOpenEvent
import net.xalitoria.crates.KeyUseEvent
import net.xalitoria.crates.RewardInfo
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates crate opening checks, confirmations, cooldowns, key & economy consumption, batch shift-click opening,
 * animation dispatch, reward delivery (with soft XaliRedeem overflow & ad-hoc reward routing), and history logging.
 */
class CrateOpenService(private val plugin: XaliCrates) {
    private val cooldowns = ConcurrentHashMap<UUID, ConcurrentHashMap<Pair<String, String>, Long>>()
    private val historyCache = ConcurrentHashMap<UUID, MutableList<HistoryRecord>>()
    private val confirmUntil = ConcurrentHashMap<Pair<UUID, String>, Long>()
    private val lastClickMs = ConcurrentHashMap<UUID, Long>()
    private val lastPlayerByCrate = ConcurrentHashMap<String, String>()
    private val lastRewardIdByCrate = ConcurrentHashMap<String, String>()
    private val lastRewardNameByCrate = ConcurrentHashMap<String, String>()
    private val activeOpenings = AtomicInteger(0)

    suspend fun loadPlayer(player: UUID) {
        val activeCooldowns = CooldownRepository.activeOf(player)
        val recentHistory = HistoryRepository.recent(player, 50)
        if (plugin.server.getPlayer(player) != null) {
            cooldowns[player] = ConcurrentHashMap(activeCooldowns)
            historyCache[player] = recentHistory.toMutableList()
        }
    }

    fun forgetPlayer(player: UUID) {
        cooldowns.remove(player)
        historyCache.remove(player)
        lastClickMs.remove(player)
        confirmUntil.keys.removeIf { it.first == player }
    }

    fun cachedHistory(player: UUID): List<HistoryRecord> = historyCache[player].orEmpty()

    fun lastOpenedBy(crateId: String): String? = lastPlayerByCrate[crateId.lowercase()]
    fun lastRewardId(crateId: String): String? = lastRewardIdByCrate[crateId.lowercase()]
    fun lastRewardName(crateId: String): String? = lastRewardNameByCrate[crateId.lowercase()]

    fun remainingCooldownSeconds(player: Player, crateId: String, placed: PlacedCrateRecord?): Long {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: return 0L
        val bypass = crate.cooldown.bypassPermission
        if (!bypass.isNullOrBlank() && player.hasPermission(bypass)) return 0L
        val locKey = if (crate.cooldown.perLocation && placed != null) placed.blockKey.asKey() else ""
        val exp = cooldowns[player.uniqueId]?.get(id to locKey) ?: return 0L
        val rem = exp - System.currentTimeMillis()
        return if (rem <= 0L) 0L else (rem + 999L) / 1000L
    }

    fun canOpenNow(player: Player, crateId: String): Boolean {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: return false
        if (!crate.enabled || crate.isMultiCrate) return false
        if (!crate.permission.isNullOrBlank() && !player.hasPermission(crate.permission)) return false
        if (remainingCooldownSeconds(player, id, null) > 0L) return false
        val hasKey = !crate.key.required || plugin.keys.hasAnyKey(player, id)
        val hasMoney = crate.cost.amount <= 0.0 || (plugin.economyHook?.has(player, crate.cost.amount, crate.cost.currency) == true)
        return if (crate.key.required && crate.cost.amount > 0.0 && crate.cost.keyOrEconomy) {
            hasKey || hasMoney
        } else {
            hasKey && hasMoney
        }
    }

    /**
     * Entry point for opening a crate (from clicking a placed crate, XaliMenu, `/xcrates open`, or `/xcrates forceopen`).
     */
    fun open(
        player: Player,
        crateId: String,
        placed: PlacedCrateRecord?,
        force: Boolean = false,
        skipAnimation: Boolean = false,
        virtual: Boolean = false,
        openAllShiftClick: Boolean = false,
    ): Boolean {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: return false
        val msg = plugin.messageConfig.data
        val crateDisplay = plugin.displayNameOf(id, crate)

        if (!force) {
            val now = System.currentTimeMillis()
            val prev = lastClickMs.put(player.uniqueId, now) ?: 0L
            if (now - prev < 300L) return false
        }

        if (!crate.enabled) {
            player send msg.crate.disabled
            if (player.hasPermission(Permissions.ADMIN)) player send msg.crate.disabledAdmin
            return false
        }

        if (crate.isMultiCrate) {
            return plugin.menuHook.openCratesHub(player, id)
        }

        if (plugin.animations.isInAnimation(player.uniqueId)) {
            player send msg.crate.alreadyOpening
            return false
        }

        if (!force && activeOpenings.get() >= plugin.config.data.crates.maxConcurrentOpenings) {
            player send msg.crate.maxOpening
            return false
        }

        if (!force) {
            if (player.gameMode == GameMode.CREATIVE && !plugin.config.data.crates.openCreative) {
                player send msg.crate.denyCreative
                pushBack(player, placed != null)
                return false
            }
            if (!crate.permission.isNullOrBlank() && !player.hasPermission(crate.permission)) {
                player send msg.crate.noPermission
                pushBack(player, placed != null)
                return false
            }
            val rem = remainingCooldownSeconds(player, id, placed)
            if (rem > 0L) {
                player send msg.crate.cooldown(msg.words.duration(rem), crateDisplay)
                pushBack(player, placed != null)
                return false
            }
            val policy = effectiveFullInventoryPolicy(crate)
            val neededSlots = if (crate.opening.requiredSlots >= 0) crate.opening.requiredSlots else plugin.config.data.crates.requiredSlots
            if (policy == FullInventoryPolicy.DENY && freeSlots(player) < neededSlots) {
                player send msg.crate.inventoryFull(neededSlots)
                pushBack(player, placed != null)
                return false
            }
            if (virtual && plugin.config.data.virtual.requireVirtualCrateAndKey &&
                plugin.keys.cachedBalance(player.uniqueId, id).crates <= 0
            ) {
                player send msg.crate.missingVirtualCrate(crateDisplay)
                return false
            }
            // Confirmation flow for normal open or shift-click open-all
            val needsConfirm = if (openAllShiftClick && plugin.config.data.keys.shiftClickOpenAll && crate.obtain == ObtainMethod.STATIC) {
                plugin.config.data.keys.shiftClickConfirm
            } else {
                plugin.config.data.crates.confirmOpen
            }
            if (needsConfirm) {
                val key = player.uniqueId to "$id:${openAllShiftClick}"
                val now = System.currentTimeMillis()
                val existing = confirmUntil[key]
                if (existing == null || existing < now) {
                    val timeoutMs = runCatching { parseDuration("confirm-timeout", plugin.config.data.crates.confirmTimeout).inWholeMilliseconds }.getOrDefault(3000L)
                    confirmUntil[key] = now + timeoutMs
                    val timeLabel = msg.words.duration((timeoutMs + 999L) / 1000L)
                    player send if (openAllShiftClick) msg.crate.confirmOpenAll(timeLabel, crateDisplay) else msg.crate.confirmOpen(timeLabel, crateDisplay)
                    return false
                }
                confirmUntil.remove(key)
            }
        }

        val preEvent = CratePreOpenEvent(player, id, virtual, skipAnimation || openAllShiftClick)
        plugin.server.pluginManager.callEvent(preEvent)
        if (preEvent.isCancelled) return false

        // Shift-click open-all batch mode on static crates
        if (!force && openAllShiftClick && plugin.config.data.keys.shiftClickOpenAll && crate.obtain == ObtainMethod.STATIC) {
            return openBatch(player, id, crate, placed, virtual)
        }

        val openLoc = placed?.let { rec ->
            Bukkit.getWorld(rec.world)?.let { Location(it, rec.x.toDouble(), rec.y.toDouble(), rec.z.toDouble()) }
        } ?: player.location

        if (force) {
            startOpening(player, id, crate, placed, openLoc, skipAnimation, virtual)
            return true
        }

        // Consume payment (key and/or economy) cleanly
        consumePaymentAndStart(player, id, crate, placed, openLoc, skipAnimation, virtual)
        return true
    }

    private fun consumePaymentAndStart(
        player: Player,
        crateId: String,
        crate: CrateConfig,
        placed: PlacedCrateRecord?,
        openLoc: Location,
        skipAnimation: Boolean,
        virtual: Boolean,
    ) {
        val msg = plugin.messageConfig.data
        val crateDisplay = plugin.displayNameOf(crateId, crate)
        val hasPhysical = plugin.keys.countPhysicalKeys(player, crateId) > 0
        val hasVirtual = plugin.keys.cachedBalance(player.uniqueId, crateId).keys > 0
        val hasKey = hasPhysical || hasVirtual
        val cost = crate.cost.amount
        val eco = plugin.economyHook

        val keyRequired = crate.key.required
        val keyOrEco = keyRequired && cost > 0.0 && crate.cost.keyOrEconomy

        val useKey = keyRequired && (!keyOrEco || hasKey)
        val useEco = cost > 0.0 && (!keyOrEco || !hasKey)

        if (useKey && !hasKey) {
            player send msg.crate.missingKey(crateDisplay, crate.key.item.name ?: crate.key.item.material)
            pushBack(player, placed != null)
            return
        }
        if (useEco) {
            if (eco == null || !eco.has(player, cost, crate.cost.currency)) {
                player send msg.crate.insufficientFunds(eco?.format(cost, crate.cost.currency) ?: cost.toString(), crateDisplay)
                pushBack(player, placed != null)
                return
            }
        }

        val preferPhysical = plugin.config.data.keys.prioritizePhysical
        val takeVirtualKey = useKey && (!preferPhysical && hasVirtual || !hasPhysical)

        if (useKey) {
            val keyEvent = KeyUseEvent(player, crateId, takeVirtualKey)
            plugin.server.pluginManager.callEvent(keyEvent)
            if (keyEvent.isCancelled) return
        }

        if (useEco && !eco!!.withdraw(player, cost, crate.cost.currency)) {
            player send msg.crate.insufficientFunds(eco.format(cost, crate.cost.currency), crateDisplay)
            return
        }

        val consumeVirtualCrate = virtual && plugin.config.data.virtual.requireVirtualCrateAndKey
        if (!takeVirtualKey && !consumeVirtualCrate) {
            if (useKey && !plugin.keys.consumePhysicalKey(player, crateId)) {
                if (useEco) eco?.deposit(player, cost, crate.cost.currency)
                player send msg.crate.missingKey(crateDisplay, crate.key.item.name ?: crate.key.item.material)
                return
            }
            applyCooldown(player, crateId, crate, placed)
            startOpening(player, crateId, crate, placed, openLoc, skipAnimation, virtual)
            return
        }

        // Atomic async DB consumption for virtual key / virtual crate
        plugin.scope.launch {
            val keyOk = if (takeVirtualKey) VirtualBalanceRepository.consumeKeys(player.uniqueId, crateId, 1) else true
            val crateOk = if (keyOk && consumeVirtualCrate) VirtualBalanceRepository.consumeCrates(player.uniqueId, crateId, 1) else keyOk
            if (keyOk && !crateOk && takeVirtualKey) VirtualBalanceRepository.giveKeys(player.uniqueId, crateId, 1)
            plugin.keys.refresh(player.uniqueId)

            player.runTask(plugin) {
                if (!keyOk || !crateOk) {
                    if (useEco) eco?.deposit(player, cost, crate.cost.currency)
                    if (!keyOk) player send msg.crate.missingKey(crateDisplay, crate.key.item.name ?: crate.key.item.material)
                    else player send msg.crate.missingVirtualCrate(crateDisplay)
                    return@runTask
                }
                if (useKey && !takeVirtualKey && !plugin.keys.consumePhysicalKey(player, crateId)) {
                    if (useEco) eco?.deposit(player, cost, crate.cost.currency)
                    if (consumeVirtualCrate) plugin.scope.launch { VirtualBalanceRepository.giveCrates(player.uniqueId, crateId, 1); plugin.keys.refresh(player.uniqueId) }
                    player send msg.crate.missingKey(crateDisplay, crate.key.item.name ?: crate.key.item.material)
                    return@runTask
                }
                applyCooldown(player, crateId, crate, placed)
                startOpening(player, crateId, crate, placed, openLoc, skipAnimation, virtual)
            }
        }
    }

    private fun openBatch(
        player: Player,
        crateId: String,
        crate: CrateConfig,
        placed: PlacedCrateRecord?,
        virtual: Boolean,
    ): Boolean {
        val msg = plugin.messageConfig.data
        val crateDisplay = plugin.displayNameOf(crateId, crate)
        val maxBatch = plugin.config.data.keys.maxShiftClickOpening
        val physicalAvail = plugin.keys.countPhysicalKeys(player, crateId)
        val virtualAvail = plugin.keys.cachedBalance(player.uniqueId, crateId).keys
        val openLoc = placed?.let { rec ->
            Bukkit.getWorld(rec.world)?.let { Location(it, rec.x.toDouble(), rec.y.toDouble(), rec.z.toDouble()) }
        } ?: player.location

        if (!crate.key.required) {
            return open(player, crateId, placed, force = false, skipAnimation = true, virtual = virtual, openAllShiftClick = false)
        }
        val want = minOf(maxBatch, physicalAvail + virtualAvail)
        if (want <= 0) {
            player send msg.crate.missingKey(crateDisplay, crate.key.item.name ?: crate.key.item.material)
            pushBack(player, placed != null)
            return false
        }
        val takePhysical = minOf(want, physicalAvail)
        val takeVirtual = want - takePhysical

        var actuallyPhysical = 0
        repeat(takePhysical) {
            if (plugin.keys.consumePhysicalKey(player, crateId)) actuallyPhysical++
        }
        val physTaken = actuallyPhysical

        plugin.scope.launch {
            val virtTaken = if (takeVirtual > 0) VirtualBalanceRepository.takeKeys(player.uniqueId, crateId, takeVirtual) else 0
            plugin.keys.refresh(player.uniqueId)
            val totalOpened = physTaken + virtTaken
            player.runTask(plugin) {
                if (totalOpened <= 0) {
                    player send msg.crate.missingKey(crateDisplay, crate.key.item.name ?: crate.key.item.material)
                    return@runTask
                }
                applyCooldown(player, crateId, crate, placed)
                val allWon = mutableListOf<NamedReward>()
                repeat(totalOpened) {
                    RewardRoller.roll(crate)?.let { allWon.add(it) }
                    allWon.addAll(RewardRoller.guaranteed(crate))
                }
                completeOpening(player, crateId, crate, placed, openLoc, allWon, totalOpened, virtual)
                player send msg.crate.batchOpened(totalOpened, crateDisplay)
            }
        }
        return true
    }

    private fun startOpening(
        player: Player,
        crateId: String,
        crate: CrateConfig,
        placed: PlacedCrateRecord?,
        openLoc: Location,
        skipAnimation: Boolean,
        virtual: Boolean,
    ) {
        activeOpenings.incrementAndGet()
        plugin.effects.playPreOpen(player, crateId, crate)

        // Remove non-static placed crate immediately unless open-chest handles it at end
        val animName = crate.opening.animation.lowercase()
        if (placed != null && crate.obtain != ObtainMethod.STATIC && (skipAnimation || animName != "open-chest")) {
            plugin.placed.removeAt(openLoc, clearBlock = true)
        }

        if (skipAnimation || animName == "instant") {
            val rolled = listOfNotNull(RewardRoller.roll(crate)) + RewardRoller.guaranteed(crate)
            completeOpening(player, crateId, crate, placed, openLoc, rolled, 1, virtual)
            return
        }

        plugin.animations.start(player, crateId, crate, placed, openLoc, virtual) { won ->
            if (placed != null && crate.obtain != ObtainMethod.STATIC && animName == "open-chest") {
                plugin.placed.removeAt(openLoc, clearBlock = true)
            }
            completeOpening(player, crateId, crate, placed, openLoc, won, 1, virtual)
        }
    }

    /** Delivers all won rewards, fires effects & holograms, records history, and dispatches [CrateOpenEvent]. */
    fun completeOpening(
        player: Player,
        crateId: String,
        crate: CrateConfig,
        placed: PlacedCrateRecord?,
        openLoc: Location,
        rewards: List<NamedReward>,
        openedCount: Int,
        virtual: Boolean,
    ) {
        try {
            val delivered = mutableListOf<NamedReward>()
            for (reward in rewards) {
                deliverSingleReward(player, crateId, crate, reward, delivered, HashSet())
            }
            if (delivered.isEmpty()) return

            lastPlayerByCrate[crateId] = player.name
            val primary = delivered.first()
            lastRewardIdByCrate[crateId] = primary.id
            lastRewardNameByCrate[crateId] = primary.config.displayItem.name ?: primary.id

            plugin.effects.playOpenEffects(player, openLoc, crateId, crate, delivered)
            if (placed != null && crate.obtain == ObtainMethod.STATIC && crate.hologram.rewardLine.isNotBlank()) {
                val text = plugin.effects.fillPlaceholders(player, crateId, crate, delivered, crate.hologram.rewardLine)
                plugin.placed.showRewardHologram(placed, crate, text)
            }

            if (plugin.config.data.history.enabled) {
                val rewardIds = delivered.map { it.id }
                val now = System.currentTimeMillis()
                val rec = HistoryRecord(0L, player.uniqueId, player.name, crateId, rewardIds, now, virtual)
                historyCache.computeIfAbsent(player.uniqueId) { mutableListOf() }.add(0, rec)
                plugin.scope.launch {
                    HistoryRepository.record(
                        player = player.uniqueId,
                        playerName = player.name,
                        crateId = crateId,
                        rewards = rewardIds,
                        openedAt = now,
                        virtual = virtual,
                        maxPerPlayer = plugin.config.data.history.maxPerPlayer,
                    )
                }
            }

            val apiRewards = delivered.map { r ->
                RewardInfo(
                    id = r.id,
                    displayName = r.config.displayItem.name ?: r.id,
                    weight = r.config.weight,
                    probabilityPercent = RewardRoller.probabilityPercent(crate, r.id),
                    rarity = r.config.rarity,
                    guaranteed = r.config.guaranteed,
                    fallbackOnly = r.config.fallbackOnly,
                    displayStack = plugin.items.buildRewardPreviewItem(crateId, r.id),
                )
            }
            plugin.server.pluginManager.callEvent(CrateOpenEvent(player, crateId, apiRewards, openedCount, virtual))
        } finally {
            activeOpenings.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
    }

    private fun deliverSingleReward(
        player: Player,
        crateId: String,
        crate: CrateConfig,
        original: NamedReward,
        delivered: MutableList<NamedReward>,
        visited: MutableSet<String>,
    ) {
        if (!visited.add(original.id)) return
        val [resolved, wasFallback] = RewardRoller.resolveFallback(crate, original) { perm -> player.hasPermission(perm) }
        if (wasFallback) {
            player send plugin.messageConfig.data.crate.fallbackAwarded(
                original.config.displayItem.name ?: original.id,
                resolved.config.displayItem.name ?: resolved.id,
            )
        }
        delivered.add(resolved)
        val cfg = resolved.config
        val rewardDisplay = cfg.displayItem.name ?: resolved.id
        val crateDisplay = plugin.displayNameOf(crateId, crate)

        // Check explicit static XaliRedeem voucher grant
        val redeemHook = plugin.redeemHook
        if (redeemHook != null && plugin.config.data.redeem.enabled && !cfg.redeem.voucher.isNullOrBlank()) {
            redeemHook.giveVoucher(player.uniqueId, cfg.redeem.voucher, cfg.redeem.voucherAmount)
        }

        // Build all ItemStacks to give for this reward
        val itemsToGive = mutableListOf<ItemStack>()
        if (cfg.giveItem.enabled) {
            plugin.items.buildRewardGrantItem(crateId, resolved.id)?.let { itemsToGive.add(it) }
        }
        for (extra in cfg.items) {
            itemsToGive.add(plugin.items.build(extra))
        }

        val expandedConsoleCmds = cfg.commands.map { expandRewardCommand(player, crateId, crate, resolved, it) }
        val expandedPlayerCmds = cfg.playerCommands.map { expandRewardCommand(player, crateId, crate, resolved, it) }

        // Route entire ad-hoc reward into XaliRedeem when redeem.mode == ALWAYS
        if (cfg.redeem.mode == RedeemDeliveryMode.ALWAYS && redeemHook != null && plugin.config.data.redeem.enabled) {
            val icon = plugin.items.buildRewardPreviewItem(crateId, resolved.id) ?: itemsToGive.firstOrNull()
            redeemHook.storeReward(
                player = player.uniqueId,
                crateId = crateId,
                title = rewardDisplay,
                description = ["&7Won from $crateDisplay"],
                icon = icon,
                items = itemsToGive,
                commands = expandedConsoleCmds,
                playerCommands = expandedPlayerCmds,
            )
        } else {
            // Give items directly; handle overflow via XaliRedeem or drop
            if (itemsToGive.isNotEmpty()) {
                val overflow = player.inventory.addItem(*itemsToGive.toTypedArray()).values.toList()
                if (overflow.isNotEmpty()) {
                    val policy = effectiveFullInventoryPolicy(crate)
                    val allowRedeem = cfg.redeem.mode != RedeemDeliveryMode.NEVER &&
                        policy == FullInventoryPolicy.REDEEM &&
                        redeemHook != null &&
                        plugin.config.data.redeem.enabled
                    if (allowRedeem) {
                        redeemHook.storeOverflow(
                            player = player.uniqueId,
                            crateId = crateId,
                            crateName = crateDisplay,
                            rewardName = rewardDisplay,
                            items = overflow,
                            icon = overflow.first(),
                        )
                        player send plugin.messageConfig.data.crate.overflowRedeem(rewardDisplay)
                    } else {
                        overflow.forEach { player.world.dropItemNaturally(player.location, it) }
                    }
                }
            }
            for (cmd in expandedConsoleCmds) plugin.runConsoleCommand(cmd.removePrefix("/"))
            for (cmd in expandedPlayerCmds) player.performCommand(cmd.removePrefix("/"))
        }

        // Linked rewards
        for (linkedId in cfg.linkedRewards) {
            val child = crate.rewards[linkedId] ?: continue
            deliverSingleReward(player, crateId, crate, NamedReward(linkedId, child), delivered, visited)
        }
    }

    private fun expandRewardCommand(player: Player, crateId: String, crate: CrateConfig, reward: NamedReward, raw: String): String {
        val withAmounts = RewardRoller.expandRandomAmounts(raw)
        return plugin.effects.fillPlaceholders(player, crateId, crate, listOf(reward), withAmounts)
    }

    private fun applyCooldown(player: Player, crateId: String, crate: CrateConfig, placed: PlacedCrateRecord?) {
        val durationMs = runCatching { parseDuration("cooldown", crate.cooldown.duration).inWholeMilliseconds }.getOrDefault(0L)
        if (durationMs <= 0L) return
        val locKey = if (crate.cooldown.perLocation && placed != null) placed.blockKey.asKey() else ""
        val expiresAt = System.currentTimeMillis() + durationMs
        cooldowns.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }[crateId to locKey] = expiresAt
        plugin.scope.launch { CooldownRepository.set(player.uniqueId, crateId, locKey, expiresAt) }
    }

    private fun pushBack(player: Player, isPlacedBlock: Boolean) {
        if (!isPlacedBlock || !plugin.config.data.crates.pushBack) return
        val dir = player.location.direction.multiply(-0.8).setY(0.35)
        player.velocity = dir
        plugin.effects.playSound(player, player.location, plugin.config.data.crates.pushBackSound)
    }

    private fun effectiveFullInventoryPolicy(crate: CrateConfig): FullInventoryPolicy =
        crate.opening.fullInventory ?: plugin.config.data.crates.fullInventory

    private fun freeSlots(player: Player): Int =
        player.inventory.storageContents.count { it == null || it.type.isAir }
}
