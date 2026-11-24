package mc.xalitoria.crates.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.xalitoria.util.bukkit.runTask
import com.xalitoria.util.message.fail
import com.xalitoria.util.message.info
import com.xalitoria.util.message.pass
import com.xalitoria.util.message.send
import com.xalitoria.util.message.toComponent
import com.xalitoria.util.message.warn
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kotlinx.coroutines.launch
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.constants.Permissions
import mc.xalitoria.crates.paper.data.db.repository.HistoryRepository
import mc.xalitoria.crates.paper.data.db.repository.VirtualBalanceRepository
import mc.xalitoria.crates.paper.data.model.ConfigProblem
import mc.xalitoria.crates.paper.editor.PendingEdits
import mc.xalitoria.crates.paper.migrate.SpecializedCratesMigrator
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Brigadier commands for XaliCrates:
 * - `/xalicrates` (aliases: `crates`, `xcrates`, `crate`, `xcrate`)
 * - `/xkeys` (aliases: `keys`, `cratekeys`, `xcratekeys`, `cratekey`, `xcratekey`)
 * - `/xrewards` (aliases: `rewards`)
 */
class CratesCommand(private val plugin: XaliCrates) {
    val label = "xalicrates"
    val aliases = ["crates", "xcrates", "crate", "xcrate"]
    val description = "Open crates, preview rewards, manage keys and edit crates"

    val keysLabel = "xkeys"
    val keysAliases = ["keys", "cratekeys", "xcratekeys", "cratekey", "xcratekey"]
    val keysDescription = "View and send virtual crate keys"

    val rewardsLabel = "xrewards"
    val rewardsAliases = ["rewards"]
    val rewardsDescription = "Preview crate rewards"

    private val msg get() = plugin.messageConfig.data
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    fun mainBuilder(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(label)
        .requires { it.sender.hasPermission(Permissions.USE) }
        .executes { exec(it) { sender -> openHub(sender) } }
        .then(Commands.literal("help").executes { exec(it) { sender -> sender send msg.commands.help(label, sender.hasPermission(Permissions.ADMIN)) } })
        .then(
            Commands.literal("preview")
                .requires { it.sender.hasPermission(Permissions.PREVIEW) }
                .then(crateArg().executes { exec(it) { sender -> withPlayer(sender) { p -> openPreview(p, str(it, "crate")) } } }),
        )
        .then(keysNode("keys"))
        .then(
            Commands.literal("claim")
                .requires { it.sender.hasPermission(Permissions.CLAIM) }
                .executes { exec(it) { sender -> withPlayer(sender) { p -> claimAll(p) } } }
                .then(
                    crateArg()
                        .executes { exec(it) { sender -> withPlayer(sender) { p -> plugin.keys.claimKey(p, str(it, "crate"), 1) } } }
                        .then(amountArg().executes { exec(it) { sender -> withPlayer(sender) { p -> plugin.keys.claimKey(p, str(it, "crate"), num(it, "amount")) } } }),
                ),
        )
        .then(
            Commands.literal("deposit")
                .requires { it.sender.hasPermission(Permissions.KEYS) }
                .then(
                    crateArg()
                        .executes { exec(it) { sender -> withPlayer(sender) { p -> plugin.keys.depositKey(p, str(it, "crate"), 64) } } }
                        .then(amountArg().executes { exec(it) { sender -> withPlayer(sender) { p -> plugin.keys.depositKey(p, str(it, "crate"), num(it, "amount")) } } }),
                ),
        )
        .then(
            Commands.literal("history")
                .requires { it.sender.hasPermission(Permissions.HISTORY) }
                .executes { exec(it) { sender -> withPlayer(sender) { p -> openHistory(p, null) } } }
                .then(amountArg("limit").executes { exec(it) { sender -> withPlayer(sender) { p -> showHistoryText(p, p.uniqueId, p.name, num(it, "limit")) } } }),
        )
        .then(
            Commands.literal("luckychest")
                .requires { it.sender.hasPermission(Permissions.LUCKY_CHEST_TOGGLE) }
                .executes { exec(it) { sender -> withPlayer(sender) { p -> plugin.luckyChests.togglePlayer(p) } } },
        )
        // ---- Admin subcommands
        .then(
            Commands.literal("open")
                .requires { it.sender.hasPermission(Permissions.ADMIN) }
                .then(
                    crateArg()
                        .executes { exec(it) { sender -> withPlayer(sender) { p -> plugin.openService.open(p, str(it, "crate"), null, force = false, virtual = true) } } }
                        .then(playerOrAllArg().executes { exec(it) { sender -> openForTarget(sender, str(it, "crate"), str(it, "player"), force = false) } }),
                ),
        )
        .then(
            Commands.literal("forceopen")
                .requires { it.sender.hasPermission(Permissions.ADMIN) }
                .then(
                    crateArg()
                        .executes { exec(it) { sender -> withPlayer(sender) { p -> plugin.openService.open(p, str(it, "crate"), null, force = true, virtual = true) } } }
                        .then(playerOrAllArg().executes { exec(it) { sender -> openForTarget(sender, str(it, "crate"), str(it, "player"), force = true) } }),
                ),
        )
        .then(giveOrTakeNode("givekey", isGive = true, isCrate = false))
        .then(giveOrTakeNode("takekey", isGive = false, isCrate = false))
        .then(giveOrTakeNode("givecrate", isGive = true, isCrate = true))
        .then(
            Commands.literal("setcrate")
                .requires { it.sender.hasPermission(Permissions.ADMIN_EDIT) }
                .then(crateArg().executes { exec(it) { sender -> withPlayer(sender) { p -> setCrateHere(p, str(it, "crate")) } } }),
        )
        .then(
            Commands.literal("spawncrate")
                .requires { it.sender.hasPermission(Permissions.ADMIN_EDIT) }
                .then(
                    crateArg().then(
                        Commands.argument("world", StringArgumentType.word())
                            .then(
                                Commands.argument("x", IntegerArgumentType.integer())
                                    .then(
                                        Commands.argument("y", IntegerArgumentType.integer())
                                            .then(
                                                Commands.argument("z", IntegerArgumentType.integer())
                                                    .executes {
                                                        exec(it) { sender ->
                                                            spawnCrateAt(
                                                                sender,
                                                                str(it, "crate"),
                                                                str(it, "world"),
                                                                num(it, "x"),
                                                                num(it, "y"),
                                                                num(it, "z"),
                                                            )
                                                        }
                                                    },
                                            ),
                                    ),
                            ),
                    ),
                ),
        )
        .then(
            Commands.literal("deletecrate")
                .requires { it.sender.hasPermission(Permissions.ADMIN_EDIT) }
                .executes { exec(it) { sender -> withPlayer(sender) { p -> deleteTargetCrate(p) } } },
        )
        .then(
            Commands.literal("delall")
                .requires { it.sender.hasPermission(Permissions.ADMIN_EDIT) }
                .then(crateArg().executes { exec(it) { sender -> deleteAllPlaced(sender, str(it, "crate")) } }),
        )
        .then(
            Commands.literal("list")
                .requires { it.sender.hasPermission(Permissions.ADMIN) }
                .executes { exec(it) { sender -> listCrates(sender) } },
        )
        .then(
            Commands.literal("info")
                .requires { it.sender.hasPermission(Permissions.ADMIN) }
                .then(crateArg().executes { exec(it) { sender -> crateInfo(sender, str(it, "crate")) } }),
        )
        .then(
            Commands.literal("errors")
                .requires { it.sender.hasPermission(Permissions.ADMIN) }
                .executes { exec(it) { sender -> showProblems(sender) } },
        )
        .then(
            Commands.literal("validate")
                .requires { it.sender.hasPermission(Permissions.ADMIN) }
                .executes { exec(it) { sender -> showProblems(sender) } },
        )
        .then(
            Commands.literal("toggleparticles")
                .requires { it.sender.hasPermission(Permissions.ADMIN) }
                .executes {
                    exec(it) { sender ->
                        plugin.placed.particlesEnabled = !plugin.placed.particlesEnabled
                        sender send msg.commands.particles.get(plugin.placed.particlesEnabled)
                    }
                },
        )
        .then(
            Commands.literal("editor")
                .requires { it.sender.hasPermission(Permissions.ADMIN_EDIT) }
                .executes { exec(it) { sender -> withPlayer(sender) { p -> plugin.editorGui.openCrateList(p) } } }
                .then(crateArg().executes { exec(it) { sender -> withPlayer(sender) { p -> openInGameEditor(p, str(it, "crate")) } } }),
        )
        .then(webNode())
        .then(shareNode())
        .then(editsNode())
        .then(migrateNode())
        .then(
            Commands.literal("reload")
                .requires { it.sender.hasPermission(Permissions.ADMIN) }
                .executes { exec(it) { sender -> doReload(sender) } },
        )
        .then(
            Commands.literal("debug")
                .requires { it.sender.hasPermission(Permissions.ADMIN) }
                .executes { exec(it) { sender -> sender send msg.commands.debug.get(plugin.toggleDebug()) } },
        )

    fun keysBuilder(): LiteralArgumentBuilder<CommandSourceStack> = keysNode(keysLabel)

    fun rewardsBuilder(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(rewardsLabel)
        .requires { it.sender.hasPermission(Permissions.PREVIEW) }
        .executes { exec(it) { sender -> openHub(sender) } }
        .then(crateArg().executes { exec(it) { sender -> withPlayer(sender) { p -> openPreview(p, str(it, "crate")) } } })

    // ---- Subtree builders

    private fun keysNode(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .requires { it.sender.hasPermission(Permissions.KEYS) }
        .executes { exec(it) { sender -> withPlayer(sender) { p -> openKeys(p) } } }
        .then(
            Commands.literal("list")
                .executes { exec(it) { sender -> withPlayer(sender) { p -> showKeysText(p, p.uniqueId, p.name) } } },
        )
        .then(
            Commands.literal("send")
                .requires { it.sender.hasPermission(Permissions.KEYS_SEND) }
                .then(
                    playerArg().then(
                        crateArg().then(
                            amountArg().executes {
                                exec(it) { sender ->
                                    withPlayer(sender) { p -> sendKeys(p, str(it, "player"), str(it, "crate"), num(it, "amount")) }
                                }
                            },
                        ),
                    ),
                ),
        )
        .then(
            playerArg()
                .requires { it.sender.hasPermission(Permissions.ADMIN) }
                .executes { exec(it) { sender -> showOtherKeys(sender, str(it, "player")) } },
        )

    private fun giveOrTakeNode(name: String, isGive: Boolean, isCrate: Boolean): LiteralArgumentBuilder<CommandSourceStack> {
        val perm = if (isGive) Permissions.ADMIN_GIVE else Permissions.ADMIN_TAKE
        return Commands.literal(name)
            .requires { it.sender.hasPermission(perm) }
            .then(
                playerOrAllArg().then(
                    crateArg()
                        .executes { exec(it) { sender -> modifyHolding(sender, str(it, "player"), str(it, "crate"), 1, isGive, isCrate, virtual = false) } }
                        .then(
                            amountArg()
                                .executes { exec(it) { sender -> modifyHolding(sender, str(it, "player"), str(it, "crate"), num(it, "amount"), isGive, isCrate, virtual = false) } }
                                .then(
                                    Commands.literal("--virtual")
                                        .executes { exec(it) { sender -> modifyHolding(sender, str(it, "player"), str(it, "crate"), num(it, "amount"), isGive, isCrate, virtual = true) } },
                                )
                                .then(
                                    Commands.literal("-v")
                                        .executes { exec(it) { sender -> modifyHolding(sender, str(it, "player"), str(it, "crate"), num(it, "amount"), isGive, isCrate, virtual = true) } },
                                ),
                        )
                        .then(
                            Commands.literal("--virtual")
                                .executes { exec(it) { sender -> modifyHolding(sender, str(it, "player"), str(it, "crate"), 1, isGive, isCrate, virtual = true) } },
                        ),
                ),
            )
    }

    private fun webNode(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("web")
        .requires { it.sender.hasPermission(Permissions.ADMIN_EDIT) }
        .executes { exec(it) { sender -> plugin.startWebEditor(sender, initialCrate = null, autoReload = false) } }
        .then(Commands.literal("stop").executes { exec(it) { sender -> stopWebSessions(sender, shared = false) } })
        .then(
            Commands.literal("--auto-reload")
                .executes { exec(it) { sender -> plugin.startWebEditor(sender, initialCrate = null, autoReload = true) } },
        )
        .then(
            crateArg()
                .executes { exec(it) { sender -> plugin.startWebEditor(sender, initialCrate = str(it, "crate"), autoReload = false) } }
                .then(
                    Commands.literal("--auto-reload")
                        .executes { exec(it) { sender -> plugin.startWebEditor(sender, initialCrate = str(it, "crate"), autoReload = true) } },
                ),
        )

    private fun shareNode(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("share")
        .requires { it.sender.hasPermission(Permissions.ADMIN_EDIT) }
        .executes { exec(it) { sender -> plugin.startSharedWebEditor(sender, initialCrate = null, readOnly = false) } }
        .then(Commands.literal("stop").executes { exec(it) { sender -> stopWebSessions(sender, shared = true) } })
        .then(
            Commands.literal("readonly")
                .executes { exec(it) { sender -> plugin.startSharedWebEditor(sender, initialCrate = null, readOnly = true) } }
                .then(crateArg().executes { exec(it) { sender -> plugin.startSharedWebEditor(sender, initialCrate = str(it, "crate"), readOnly = true) } }),
        )
        .then(crateArg().executes { exec(it) { sender -> plugin.startSharedWebEditor(sender, initialCrate = str(it, "crate"), readOnly = false) } })

    private fun editsNode(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("edits")
        .requires { it.sender.hasPermission(Permissions.ADMIN_EDIT) }
        .executes { exec(it) { sender -> listPendingEdits(sender) } }
        .then(
            Commands.literal("apply")
                .then(amountArg("id").executes { exec(it) { sender -> applyPendingEdit(sender, num(it, "id")) } }),
        )
        .then(
            Commands.literal("drop")
                .then(amountArg("id").executes { exec(it) { sender -> dropPendingEdit(sender, num(it, "id")) } }),
        )

    private fun migrateNode(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("migrate")
        .requires { it.sender.hasPermission(Permissions.ADMIN_MIGRATE) }
        .then(
            Commands.literal("specializedcrates")
                .executes { exec(it) { sender -> runMigrate(sender, "") } }
                .then(
                    Commands.argument("args", StringArgumentType.greedyString())
                        .suggests { _, b ->
                            listOf("--dry-run", "--overwrite", "--skip-data")
                                .filter { it.startsWith(b.remaining, ignoreCase = true) }
                                .forEach(b::suggest)
                            b.buildFuture()
                        }
                        .executes { exec(it) { sender -> runMigrate(sender, str(it, "args")) } },
                ),
        )

    // ---- Handlers

    private fun openHub(sender: CommandSender) {
        withPlayer(sender) { player ->
            if (!plugin.menuHook.openCratesHub(player)) {
                player send msg.commands.help(label, player.hasPermission(Permissions.ADMIN))
            }
        }
    }

    private fun openPreview(player: Player, crateId: String) {
        if (crateId.lowercase() !in plugin.crates) {
            player fail msg.commands.unknownCrate(crateId)
            return
        }
        if (!plugin.menuHook.openPreview(player, crateId)) {
            player fail "Preview menu is not loaded in XaliMenu. Run /xmenu reload."
        }
    }

    private fun openKeys(player: Player) {
        if (!plugin.menuHook.openKeys(player)) {
            showKeysText(player, player.uniqueId, player.name)
        }
    }

    private fun openHistory(player: Player, limit: Int?) {
        if (limit == null && plugin.menuHook.openHistory(player)) return
        showHistoryText(player, player.uniqueId, player.name, limit ?: 15)
    }

    private fun showKeysText(sender: CommandSender, target: UUID, name: String) {
        plugin.scope.launch {
            val balances = VirtualBalanceRepository.of(target).filterValues { it.keys > 0 || it.crates > 0 }
            plugin.runTask {
                sender send msg.keys.header(name)
                if (balances.isEmpty()) {
                    sender send msg.keys.empty
                } else {
                    for ([id, bal] in balances.toSortedMap()) {
                        val display = plugin.crates[id]?.let { plugin.displayNameOf(id, it) } ?: id
                        sender send msg.keys.entry(display, bal.keys, bal.crates)
                    }
                }
            }
        }
    }

    private fun showOtherKeys(sender: CommandSender, playerName: String) {
        val target = resolveOffline(playerName) ?: run {
            sender fail msg.commands.playerNotFound(playerName)
            return
        }
        showKeysText(sender, target.first, target.second)
    }

    private fun sendKeys(sender: Player, targetName: String, crateId: String, amount: Int) {
        if (!plugin.config.data.keys.allowKeySending) {
            sender send msg.keys.sendingDisabled
            return
        }
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: run {
            sender fail msg.commands.unknownCrate(crateId)
            return
        }
        val target = resolveOffline(targetName) ?: run {
            sender fail msg.commands.playerNotFound(targetName)
            return
        }
        if (target.first == sender.uniqueId) {
            sender fail "You cannot send keys to yourself."
            return
        }
        val crateDisplay = plugin.displayNameOf(id, crate)
        plugin.scope.launch {
            val ok = VirtualBalanceRepository.transferKeys(sender.uniqueId, target.first, id, amount)
            plugin.keys.refresh(sender.uniqueId)
            plugin.keys.refresh(target.first)
            sender.runTask(plugin) {
                if (!ok) {
                    sender send msg.keys.notEnough(amount, crateDisplay)
                } else {
                    sender send msg.keys.sent(amount, crateDisplay, target.second)
                    Bukkit.getPlayer(target.first)?.let { online ->
                        online.runTask(plugin) { online send msg.keys.receivedFrom(amount, crateDisplay, sender.name) }
                    }
                }
            }
        }
    }

    private fun claimAll(player: Player) {
        val owned = plugin.keys.cachedAll(player.uniqueId).filterValues { it.keys > 0 }
        if (owned.isEmpty()) {
            player send msg.keys.noneToClaim
            return
        }
        for ([id, bal] in owned) {
            plugin.keys.claimKey(player, id, bal.keys)
        }
    }

    private fun showHistoryText(sender: CommandSender, target: UUID, name: String, limit: Int) {
        plugin.scope.launch {
            val rows = HistoryRepository.recent(target, limit.coerceIn(1, 50))
            plugin.runTask {
                sender send msg.admin.historyHeader(name)
                if (rows.isEmpty()) {
                    sender send msg.admin.historyEmpty
                } else {
                    for (row in rows) {
                        val time = timeFormat.format(Instant.ofEpochMilli(row.openedAt))
                        val crateDisplay = plugin.crates[row.crateId]?.let { plugin.displayNameOf(row.crateId, it) } ?: row.crateId
                        sender send msg.admin.historyEntry(time, crateDisplay, row.rewards.joinToString(", "))
                    }
                }
            }
        }
    }

    private fun openForTarget(sender: CommandSender, crateId: String, target: String, force: Boolean) {
        val id = crateId.lowercase()
        if (id !in plugin.crates) {
            sender fail msg.commands.unknownCrate(crateId)
            return
        }
        val targets = if (target.equals("all", ignoreCase = true)) {
            Bukkit.getOnlinePlayers().toList()
        } else {
            val p = Bukkit.getPlayerExact(target) ?: run {
                sender fail msg.commands.playerNotFound(target)
                return
            }
            listOf(p)
        }
        for (p in targets) {
            p.runTask(plugin) { plugin.openService.open(p, id, null, force = force, virtual = true) }
        }
        sender pass "Opening $id for ${targets.size} player(s)."
    }

    private fun modifyHolding(
        sender: CommandSender,
        targetArg: String,
        crateId: String,
        amount: Int,
        isGive: Boolean,
        isCrate: Boolean,
        virtual: Boolean,
    ) {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: run {
            sender fail msg.commands.unknownCrate(crateId)
            return
        }
        val crateDisplay = plugin.displayNameOf(id, crate)
        val kindWord = if (virtual) "virtual" else "physical"

        if (targetArg.equals("all", ignoreCase = true)) {
            val online = Bukkit.getOnlinePlayers().toList()
            for (p in online) {
                applyOneHolding(p.uniqueId, p.name, p, id, crateDisplay, amount, isGive, isCrate, virtual, kindWord, sender, silentSender = true)
            }
            sender pass "${if (isGive) "Gave" else "Took"} ${amount}x $crateDisplay $kindWord ${if (isCrate) "crates" else "keys"} (${online.size} online players)."
            return
        }

        val resolved = resolveOffline(targetArg) ?: run {
            sender fail msg.commands.playerNotFound(targetArg)
            return
        }
        val online = Bukkit.getPlayer(resolved.first)
        if (!virtual && online == null) {
            sender fail "${resolved.second} is offline. Pass --virtual to modify virtual balances offline."
            return
        }
        applyOneHolding(resolved.first, resolved.second, online, id, crateDisplay, amount, isGive, isCrate, virtual, kindWord, sender, silentSender = false)
    }

    private fun applyOneHolding(
        uuid: UUID,
        name: String,
        online: Player?,
        crateId: String,
        crateDisplay: String,
        amount: Int,
        isGive: Boolean,
        isCrate: Boolean,
        virtual: Boolean,
        kindWord: String,
        sender: CommandSender,
        silentSender: Boolean,
    ) {
        if (virtual) {
            plugin.scope.launch {
                if (isGive) {
                    if (isCrate) plugin.keys.giveVirtualCrates(uuid, crateId, amount)
                    else plugin.keys.giveVirtualKeys(uuid, crateId, amount)
                } else {
                    if (isCrate) plugin.keys.takeVirtualCrates(uuid, crateId, amount)
                    else plugin.keys.takeVirtualKeys(uuid, crateId, amount)
                }
                plugin.runTask {
                    if (!silentSender) {
                        sender send when {
                            isGive && isCrate -> msg.admin.gaveCrate(name, amount, crateDisplay, kindWord)
                            isGive -> msg.admin.gaveKey(name, amount, crateDisplay, kindWord)
                            else -> msg.admin.tookKey(name, amount, crateDisplay, kindWord)
                        }
                    }
                    if (isGive && online != null && online.isOnline) {
                        online.runTask(plugin) {
                            online send if (isCrate) msg.admin.receivedCrate(amount, crateDisplay, kindWord)
                            else msg.admin.receivedKey(amount, crateDisplay, kindWord)
                        }
                    }
                }
            }
        } else if (online != null) {
            online.runTask(plugin) {
                if (isGive) {
                    if (isCrate) plugin.keys.givePhysicalCrates(online, crateId, amount)
                    else plugin.keys.givePhysicalKeys(online, crateId, amount)
                    if (!silentSender) {
                        sender send if (isCrate) msg.admin.gaveCrate(name, amount, crateDisplay, kindWord)
                        else msg.admin.gaveKey(name, amount, crateDisplay, kindWord)
                    }
                    online send if (isCrate) msg.admin.receivedCrate(amount, crateDisplay, kindWord)
                    else msg.admin.receivedKey(amount, crateDisplay, kindWord)
                } else {
                    var taken = 0
                    repeat(amount) { if (plugin.keys.consumePhysicalKey(online, crateId)) taken++ }
                    if (!silentSender) sender send msg.admin.tookKey(name, taken, crateDisplay, kindWord)
                }
            }
        }
    }

    private fun setCrateHere(player: Player, crateId: String) {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: run {
            player fail msg.commands.unknownCrate(crateId)
            return
        }
        val block = player.getTargetBlockExact(6) ?: run {
            player send msg.admin.noCrateTargeted
            return
        }
        plugin.placed.place(block.location, id, setBlock = false)
        player send msg.crate.placedSuccess(plugin.displayNameOf(id, crate), "${block.x}, ${block.y}, ${block.z}")
    }

    private fun spawnCrateAt(sender: CommandSender, crateId: String, worldName: String, x: Int, y: Int, z: Int) {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: run {
            sender fail msg.commands.unknownCrate(crateId)
            return
        }
        val world = Bukkit.getWorld(worldName) ?: run {
            sender fail "Unknown world '$worldName'."
            return
        }
        val loc = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        plugin.placed.place(loc, id, setBlock = true)
        sender send msg.crate.placedSuccess(plugin.displayNameOf(id, crate), "$worldName $x, $y, $z")
    }

    private fun deleteTargetCrate(player: Player) {
        val block = player.getTargetBlockExact(6) ?: run {
            player send msg.admin.noCrateTargeted
            return
        }
        val removed = plugin.placed.removeAt(block.location, clearBlock = false) ?: run {
            player send msg.admin.notAPlacedCrate
            return
        }
        val display = plugin.crates[removed.crateId]?.let { plugin.displayNameOf(removed.crateId, it) } ?: removed.crateId
        player send msg.crate.removedSuccess(display)
    }

    private fun deleteAllPlaced(sender: CommandSender, crateId: String) {
        val id = crateId.lowercase()
        val count = plugin.placed.removeAllOf(id, clearBlock = false)
        val display = plugin.crates[id]?.let { plugin.displayNameOf(id, it) } ?: id
        sender send msg.admin.deletedAll(count, display)
    }

    private fun listCrates(sender: CommandSender) {
        sender send msg.admin.listHeader
        for ([id, crate] in plugin.crates.toSortedMap()) {
            val state = if (crate.enabled) "&aenabled" else "&cdisabled"
            sender send msg.admin.listEntry(id, state, crate.rewards.size, plugin.placed.countOf(id))
        }
    }

    private fun crateInfo(sender: CommandSender, crateId: String) {
        val id = crateId.lowercase()
        val crate = plugin.crates[id] ?: run {
            sender fail msg.commands.unknownCrate(crateId)
            return
        }
        sender send "{#a0a>}&lXALI{#fff<}&f&lCRATES &7> &d$id"
        sender send "&7Display: &r${plugin.displayNameOf(id, crate)} &8(${if (crate.enabled) "&aenabled" else "&cdisabled"}&8)"
        sender send "&7Obtain: &f${crate.obtain.name} &8| &7Animation: &f${crate.opening.animation} &8| &7Multi-crate: &f${crate.isMultiCrate}"
        sender send "&7Key required: &f${crate.key.required} &8| &7Cost: &f${crate.cost.amount} &8| &7Cooldown: &f${crate.cooldown.duration}"
        sender send "&7Rewards: &f${crate.rewards.size} &8| &7Tiers: &f${crate.tiers.size} &8| &7Placed: &f${plugin.placed.countOf(id)}"
    }

    private fun showProblems(sender: CommandSender) {
        val problems = plugin.problems
        if (problems.isEmpty()) {
            sender send msg.admin.errorsNone
            return
        }
        sender send msg.admin.errorsHeader
        for ([source, list] in problems) {
            for (p in list) {
                val color = if (p.severity == ConfigProblem.Severity.ERROR) "&cERROR" else "&eWARN"
                sender send "$color &f$source &7(${p.path}): &r${p.message}"
            }
        }
    }

    private fun openInGameEditor(player: Player, crateId: String) {
        val id = crateId.lowercase()
        if (id !in plugin.crates) {
            player fail msg.commands.unknownCrate(crateId)
            return
        }
        plugin.editorGui.openCrateDetail(player, id)
    }

    private fun stopWebSessions(sender: CommandSender, shared: Boolean) {
        val owner = if (sender is Player && !sender.hasPermission(Permissions.ADMIN)) sender.name else null
        val stopped = plugin.stopWebSessions(owner, shared)
        sender send msg.editor.webStopped(stopped)
    }

    private fun listPendingEdits(sender: CommandSender) {
        val edits = plugin.pendingEdits.list()
        if (edits.isEmpty()) {
            sender send msg.editor.pendingNone
            return
        }
        sender send "{#a0a>}&lXALI{#fff<}&f&lCRATES &7> &dPending Shared Edits"
        for (edit in edits) {
            val target = "${edit.kind.name.lowercase()}/${edit.name}"
            val warnNote = if (edit.problems.isEmpty()) "&a0 issues" else "&e${edit.problems.size} warning(s)"
            val comp = "&d#${edit.id} &f$target &7from &f${edit.from} &8($warnNote&8) ".toComponent()
                .append(
                    "&a[Apply]".toComponent()
                        .clickEvent(ClickEvent.runCommand("/$label edits apply ${edit.id}"))
                        .hoverEvent(HoverEvent.showText("&aClick to apply #$edit.id".toComponent())),
                )
                .append(" ".toComponent())
                .append(
                    "&c[Drop]".toComponent()
                        .clickEvent(ClickEvent.runCommand("/$label edits drop ${edit.id}"))
                        .hoverEvent(HoverEvent.showText("&cClick to discard #${edit.id}".toComponent())),
                )
            sender.sendMessage(comp)
        }
    }

    private fun applyPendingEdit(sender: CommandSender, id: Int) {
        val edit = plugin.pendingEdits.take(id) ?: run {
            sender fail "No pending edit #$id."
            return
        }
        val target = "${edit.kind.name.lowercase()}/${edit.name}"
        when (edit.kind) {
            PendingEdits.Kind.CRATE -> edit.crate?.let { plugin.editorFiles.saveCrate(edit.name, it) }
            PendingEdits.Kind.ANIMATION -> edit.animation?.let { plugin.editorFiles.saveAnimation(edit.name, it) }
        }
        plugin.reload()
        sender send msg.editor.pendingApplied(edit.id, target)
    }

    private fun dropPendingEdit(sender: CommandSender, id: Int) {
        val edit = plugin.pendingEdits.take(id) ?: run {
            sender fail "No pending edit #$id."
            return
        }
        sender send msg.editor.pendingDropped(edit.id)
    }

    private fun runMigrate(sender: CommandSender, rawArgs: String) {
        val tokens = rawArgs.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        var dryRun = false
        var overwrite = false
        var skipData = false
        var pathArg: String? = null
        for (tok in tokens) {
            when (tok.lowercase()) {
                "--dry-run" -> dryRun = true
                "--overwrite" -> overwrite = true
                "--skip-data" -> skipData = true
                else -> if (pathArg == null) pathArg = tok
            }
        }
        val pluginsDir = plugin.dataPath.parent
        val source = if (pathArg != null) Path.of(pathArg) else pluginsDir.resolve("SpecializedCrates")
        if (!source.toFile().isDirectory) {
            sender send msg.migrate.sourceMissing(source.toAbsolutePath().toString())
            return
        }
        val menuDir = plugin.server.pluginManager.getPlugin("XaliMenu")?.dataPath?.resolve("menus")
            ?: pluginsDir.resolve("XaliMenu/menus")

        sender send msg.migrate.started(source.toAbsolutePath().toString())
        plugin.scope.launch {
            try {
                val report = SpecializedCratesMigrator.migrate(
                    sourceDir = source,
                    targetDataDir = plugin.dataPath,
                    xaliMenuDir = menuDir,
                    dryRun = dryRun,
                    overwrite = overwrite,
                    skipData = skipData,
                )
                plugin.runTask {
                    if (!dryRun) {
                        plugin.reload()
                        plugin.scope.launch { runCatching { plugin.placed.loadAllFromDb() } }
                    }
                    val mode = if (dryRun) "dry-run" else "applied"
                    sender send msg.migrate.finished(
                        mode,
                        report.cratesMigrated,
                        report.animationsMigrated,
                        report.menusGenerated,
                        report.placedCratesMigrated,
                        report.playersMigrated,
                    )
                    if (report.warnings.isNotEmpty()) {
                        sender send msg.migrate.warningCount(report.warnings.size)
                        report.warnings.take(10).forEach { w ->
                            plugin.logger.warning("[Migrate] $w")
                            sender warn w
                        }
                        if (report.warnings.size > 10) {
                            report.warnings.drop(10).forEach { plugin.logger.warning("[Migrate] $it") }
                        }
                    }
                    if (!dryRun && report.menusGenerated > 0) {
                        sender info "Run /xmenu reload to load the ${report.menusGenerated} generated XaliMenu preview/multi-crate menus."
                    }
                }
            } catch (e: Exception) {
                plugin.logger.warning("SpecializedCrates migration failed: ${e.message}")
                plugin.runTask { sender fail "Migration failed: ${e.message}" }
            }
        }
    }

    private fun doReload(sender: CommandSender) {
        try {
            val [cratesCount, animsCount] = plugin.reload()
            sender pass msg.commands.reload(cratesCount, animsCount)
            if (plugin.problems.isNotEmpty()) {
                sender warn "Found problems in ${plugin.problems.size} file(s). Run /$label errors for details."
            }
        } catch (e: Exception) {
            sender fail msg.commands.reloadFailed(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    // ---- Argument helpers

    private fun crateArg(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("crate", StringArgumentType.word()).suggests { _, b ->
            plugin.crates.keys.filter { it.startsWith(b.remaining, ignoreCase = true) }.forEach(b::suggest)
            b.buildFuture()
        }

    private fun playerArg(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("player", StringArgumentType.word()).suggests { _, b ->
            Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(b.remaining, ignoreCase = true) }.forEach(b::suggest)
            b.buildFuture()
        }

    private fun playerOrAllArg(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("player", StringArgumentType.word()).suggests { _, b ->
            (listOf("all") + Bukkit.getOnlinePlayers().map { it.name })
                .filter { it.startsWith(b.remaining, ignoreCase = true) }
                .forEach(b::suggest)
            b.buildFuture()
        }

    private fun amountArg(name: String = "amount"): RequiredArgumentBuilder<CommandSourceStack, Int> =
        Commands.argument(name, IntegerArgumentType.integer(1))

    private fun str(ctx: CommandContext<CommandSourceStack>, name: String): String = ctx.getArgument(name, String::class.java)
    private fun num(ctx: CommandContext<CommandSourceStack>, name: String): Int = ctx.getArgument(name, Int::class.java)

    private inline fun exec(ctx: CommandContext<CommandSourceStack>, block: (CommandSender) -> Unit): Int {
        block(ctx.source.sender)
        return Command.SINGLE_SUCCESS
    }

    private inline fun withPlayer(sender: CommandSender, block: (Player) -> Unit) {
        if (sender !is Player) {
            sender fail msg.commands.playerOnly
            return
        }
        block(sender)
    }

    private fun resolveOffline(name: String): Pair<UUID, String>? {
        Bukkit.getPlayerExact(name)?.let { return it.uniqueId to it.name }
        val offline = Bukkit.getOfflinePlayerIfCached(name) ?: return null
        return offline.uniqueId to (offline.name ?: name)
    }
}
