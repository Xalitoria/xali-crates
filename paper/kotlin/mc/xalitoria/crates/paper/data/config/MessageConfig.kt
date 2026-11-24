package mc.xalitoria.crates.paper.data.config

import com.charleskorn.kaml.YamlComment
import com.xalitoria.util.message.TimeWords
import com.xalitoria.util.message.formatArgs
import com.xalitoria.util.serial.message.State
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val HEAD = "{#a0a>}&lXALI{#fff<}&f&lCRATES &7>"
private const val PREFIX = "$HEAD "

/** Every player-facing message for XaliCrates, saved in `language/<language>.yml`. */
@Serializable
data class MessageConfig(
    val commands: Commands = Commands(),
    val crate: CrateMessages = CrateMessages(),
    val keys: KeyMessages = KeyMessages(),
    val lucky: LuckyMessages = LuckyMessages(),
    val admin: AdminMessages = AdminMessages(),
    val editor: EditorMessages = EditorMessages(),
    val migrate: MigrateMessages = MigrateMessages(),
    @YamlComment("Localized time units for cooldowns and durations.")
    val words: TimeWords = TimeWords(),
) {
    @Serializable
    data class Commands(
        @SerialName("no-permission")
        val noPermission: String = "You don't have permission to use this command.",
        @SerialName("player-only")
        val playerOnly: String = "You must be a player to use this command.",
        @SerialName("player-not-found")
        private val playerNotFound: String = "No player called {0} has joined.",
        @SerialName("unknown-crate")
        private val unknownCrate: String = "There is no crate called {0}.",
        private val help: String = """
            $HEAD &dHELP
            &d/{0} &r- open the virtual crates menu
            &d/{0} &rpreview <crate> &7- preview a crate's rewards
            &d/{0} &rkeys [send <player> <crate> <amount>] &7- view or send virtual keys
            &d/{0} &rclaim &7- withdraw virtual keys as physical keys
            &d/{0} &rdeposit <crate> [amount] &7- deposit physical keys as virtual keys
            &d/{0} &rhistory [limit] &7- view your recent crate openings
            &d/{0} &rluckychest &7- toggle lucky chest spawns
        """.trimIndent(),
        private val adminHelp: String = """
            &d/{0} &rgivekey|takekey <player> <crate> [amount] [--virtual] &7- manage keys
            &d/{0} &rgivecrate <player> <crate> [amount] [--virtual] &7- give crate items
            &d/{0} &ropen|forceopen <crate> <player|all> &7- open a crate for a player
            &d/{0} &rsetcrate|spawncrate|deletecrate|delall &7- manage placed crates
            &d/{0} &reditor [crate] &7- open the in-game crate editor
            &d/{0} &rweb [crate] [--auto-reload] &7- open the web editor
            &d/{0} &rshare [readonly|stop] [crate] &7- share a web editor link
            &d/{0} &redits [apply|drop <id>] &7- review shared web edits
            &d/{0} &rmigrate specializedcrates [path] [--dry-run] [--overwrite] [--skip-data]
            &d/{0} &rlist|info|errors|validate|toggleparticles|reload|debug
        """.trimIndent(),
        private val reload: String = "Configuration reloaded: {0} crates, {1} animations.",
        @SerialName("reload-failed")
        private val reloadFailed: String = "Configuration could not be reloaded: {0}",
        val debug: State = State("Debug logging is on.", "Debug logging is off."),
        val particles: State = State("Ambient crate particles are on.", "Ambient crate particles are off."),
        @SerialName("unexpected-error")
        val unexpectedError: String = "An unexpected error occurred. Please try again.",
    ) {
        fun playerNotFound(name: String): String = playerNotFound.formatArgs(name)
        fun unknownCrate(id: String): String = unknownCrate.formatArgs(id)
        fun help(label: String, admin: Boolean): String = help.formatArgs(label) + if (admin) "\n" + adminHelp.formatArgs(label) else ""
        fun reload(crates: Int, animations: Int): String = reload.formatArgs(crates, animations)
        fun reloadFailed(reason: String): String = reloadFailed.formatArgs(reason)
    }

    @Serializable
    data class CrateMessages(
        val disabled: String = "&cThis crate is currently disabled.",
        @SerialName("disabled-admin")
        val disabledAdmin: String = "&7Check &e/xcrates errors &7for details.",
        @SerialName("no-permission")
        val noPermission: String = "&cYou do not have permission to open this crate.",
        @SerialName("inventory-full")
        private val inventoryFull: String = "&cYou need at least {0} free inventory slots to open this crate.",
        @SerialName("overflow-redeem")
        private val overflowRedeem: String = "$PREFIX&eYour inventory was full; &f{0} &ewas sent to &b/redeem&e.",
        @SerialName("missing-key")
        private val missingKey: String = "&cYou need a {1}&c to open {0}&c.",
        @SerialName("missing-virtual-crate")
        private val missingVirtualCrate: String = "&cYou don't have a virtual {0}&c crate.",
        @SerialName("insufficient-funds")
        private val insufficientFunds: String = "&cYou need {0} to open {1}&c.",
        @SerialName("already-opening")
        val alreadyOpening: String = "&cYou are already opening a crate.",
        @SerialName("max-opening")
        val maxOpening: String = "&cToo many crates are opening right now. Try again shortly.",
        @SerialName("deny-creative")
        val denyCreative: String = "&cYou cannot do that in creative mode.",
        private val cooldown: String = "&cYou must wait {0} before opening {1}&c again.",
        @SerialName("confirm-open")
        private val confirmOpen: String = "$PREFIX&eClick again within &f{0}&e to confirm opening {1}&e.",
        @SerialName("confirm-open-all")
        private val confirmOpenAll: String = "$PREFIX&eShift-click again within &f{0}&e to open all keys for {1}&e.",
        @SerialName("fallback-awarded")
        private val fallbackAwarded: String = "$PREFIX&eYou already unlocked {0}&e, so you received {1}&e instead.",
        @SerialName("denied-place")
        val deniedPlace: String = "&cYou cannot place this crate here.",
        @SerialName("denied-place-static")
        val deniedPlaceStatic: String = "&cYou don't have permission to place static crates.",
        @SerialName("placed-success")
        private val placedSuccess: String = "$PREFIX&aPlaced {0}&a at &f{1}&a.",
        @SerialName("removed-success")
        private val removedSuccess: String = "$PREFIX&aRemoved placed {0}&a.",
        @SerialName("batch-opened")
        private val batchOpened: String = "$PREFIX&aOpened &f{0}x &r{1}&a.",
    ) {
        fun inventoryFull(slots: Int): String = inventoryFull.formatArgs(slots)
        fun overflowRedeem(reward: String): String = overflowRedeem.formatArgs(reward)
        fun missingKey(crate: String, key: String): String = missingKey.formatArgs(crate, key)
        fun missingVirtualCrate(crate: String): String = missingVirtualCrate.formatArgs(crate)
        fun insufficientFunds(cost: String, crate: String): String = insufficientFunds.formatArgs(cost, crate)
        fun cooldown(time: String, crate: String): String = cooldown.formatArgs(time, crate)
        fun confirmOpen(time: String, crate: String): String = confirmOpen.formatArgs(time, crate)
        fun confirmOpenAll(time: String, crate: String): String = confirmOpenAll.formatArgs(time, crate)
        fun fallbackAwarded(original: String, fallback: String): String = fallbackAwarded.formatArgs(original, fallback)
        fun placedSuccess(crate: String, coords: String): String = placedSuccess.formatArgs(crate, coords)
        fun removedSuccess(crate: String): String = removedSuccess.formatArgs(crate)
        fun batchOpened(count: Int, crate: String): String = batchOpened.formatArgs(count, crate)
    }

    @Serializable
    data class KeyMessages(
        private val header: String = "$PREFIX&dVirtual Keys &7({0})",
        private val entry: String = "&d{0} &7- &f{1} keys &7/ &f{2} crates",
        val empty: String = "&7No virtual keys or crates.",
        @SerialName("sending-disabled")
        val sendingDisabled: String = "&cSending virtual keys to other players is disabled.",
        @SerialName("not-enough")
        private val notEnough: String = "&cYou don't have {0} virtual keys for {1}&c.",
        @SerialName("sent")
        private val sent: String = "$PREFIX&aSent &f{0}x &r{1}&a virtual keys to &f{2}&a.",
        @SerialName("received-from")
        private val receivedFrom: String = "$PREFIX&aYou received &f{0}x &r{1}&a virtual keys from &f{2}&a.",
        @SerialName("claimed")
        private val claimed: String = "$PREFIX&aClaimed &f{0}x &r{1}&a physical keys.",
        @SerialName("deposited")
        private val deposited: String = "$PREFIX&aDeposited &f{0}x &r{1}&a keys into virtual storage.",
        @SerialName("deposit-disabled")
        val depositDisabled: String = "&cDepositing physical keys is disabled.",
        @SerialName("none-to-deposit")
        private val noneToDeposit: String = "&cYou have no physical {0}&c keys in your inventory.",
        @SerialName("none-to-claim")
        val noneToClaim: String = "&cYou have no virtual keys to claim.",
    ) {
        fun header(player: String): String = header.formatArgs(player)
        fun entry(crate: String, keys: Int, crates: Int): String = entry.formatArgs(crate, keys, crates)
        fun notEnough(amount: Int, crate: String): String = notEnough.formatArgs(amount, crate)
        fun sent(amount: Int, crate: String, target: String): String = sent.formatArgs(amount, crate, target)
        fun receivedFrom(amount: Int, crate: String, sender: String): String = receivedFrom.formatArgs(amount, crate, sender)
        fun claimed(amount: Int, crate: String): String = claimed.formatArgs(amount, crate)
        fun deposited(amount: Int, crate: String): String = deposited.formatArgs(amount, crate)
        fun noneToDeposit(crate: String): String = noneToDeposit.formatArgs(crate)
    }

    @Serializable
    data class LuckyMessages(
        val toggle: State = State("Lucky chest spawns are on.", "Lucky chest spawns are off."),
        private val spawned: String = "$PREFIX&aA lucky {0}&a appeared!",
    ) {
        fun spawned(crate: String): String = spawned.formatArgs(crate)
    }

    @Serializable
    data class AdminMessages(
        @SerialName("gave-key")
        private val gaveKey: String = "&aGave {0} {1}x {2}&a {3} keys.",
        @SerialName("took-key")
        private val tookKey: String = "&aTook {1}x {2}&a {3} keys from {0}.",
        @SerialName("received-key")
        private val receivedKey: String = "$PREFIX&aYou received &f{0}x &r{1}&a {2} keys.",
        @SerialName("gave-crate")
        private val gaveCrate: String = "&aGave {0} {1}x {2}&a {3} crates.",
        @SerialName("received-crate")
        private val receivedCrate: String = "$PREFIX&aYou received &f{0}x &r{1}&a {2} crates.",
        @SerialName("deleted-all")
        private val deletedAll: String = "&aRemoved {0} placed instances of {1}&a.",
        @SerialName("no-crate-targeted")
        val noCrateTargeted: String = "&cLook at a block within 6 blocks.",
        @SerialName("not-a-placed-crate")
        val notAPlacedCrate: String = "&cThere is no placed crate at that block.",
        @SerialName("list-header")
        val listHeader: String = "$PREFIX&dLoaded Crates",
        @SerialName("list-entry")
        private val listEntry: String = "&d{0} &7- {1} &8({2} rewards, {3} placed)",
        @SerialName("errors-header")
        val errorsHeader: String = "$PREFIX&dConfiguration Problems",
        @SerialName("errors-none")
        val errorsNone: String = "&aNo configuration problems found.",
        @SerialName("history-header")
        private val historyHeader: String = "$PREFIX&dRecent Openings &7({0})",
        @SerialName("history-entry")
        private val historyEntry: String = "&7[{0}] &d{1} &7-> &f{2}",
        @SerialName("history-empty")
        val historyEmpty: String = "&7No crate opening history found.",
    ) {
        fun gaveKey(player: String, amount: Int, crate: String, kind: String): String = gaveKey.formatArgs(player, amount, crate, kind)
        fun tookKey(player: String, amount: Int, crate: String, kind: String): String = tookKey.formatArgs(player, amount, crate, kind)
        fun receivedKey(amount: Int, crate: String, kind: String): String = receivedKey.formatArgs(amount, crate, kind)
        fun gaveCrate(player: String, amount: Int, crate: String, kind: String): String = gaveCrate.formatArgs(player, amount, crate, kind)
        fun receivedCrate(amount: Int, crate: String, kind: String): String = receivedCrate.formatArgs(amount, crate, kind)
        fun deletedAll(count: Int, crate: String): String = deletedAll.formatArgs(count, crate)
        fun listEntry(id: String, state: String, rewards: Int, placed: Int): String = listEntry.formatArgs(id, state, rewards, placed)
        fun historyHeader(player: String): String = historyHeader.formatArgs(player)
        fun historyEntry(time: String, crate: String, rewards: String): String = historyEntry.formatArgs(time, crate, rewards)
    }

    @Serializable
    data class EditorMessages(
        @SerialName("web-link")
        private val webLink: String = "$PREFIX&aWeb editor ready: &b{0}",
        @SerialName("web-stopped")
        private val webStopped: String = "&aStopped {0} active web editor sessions.",
        @SerialName("web-failed")
        private val webFailed: String = "&cCould not start web editor session: {0}",
        @SerialName("web-saved")
        private val webSaved: String = "$PREFIX&aSaved {0} &f{1}&a from web editor ({2}).",
        @SerialName("pending-held")
        private val pendingHeld: String = "$PREFIX&eShared edit &f#{0}&e for &f{1}&e from &f{2}&e. Use &b/xcrates edits apply {0}&e.",
        @SerialName("pending-applied")
        private val pendingApplied: String = "&aApplied shared edit #{0} ({1}).",
        @SerialName("pending-dropped")
        private val pendingDropped: String = "&eDropped shared edit #{0}.",
        @SerialName("pending-none")
        val pendingNone: String = "&7There are no pending shared edits.",
        @SerialName("prompt-chat")
        private val promptChat: String = "$PREFIX&eType {0} in chat, or type &ccancel&e.",
        @SerialName("prompt-cancelled")
        val promptCancelled: String = "&7Edit cancelled.",
        @SerialName("ingame-saved")
        private val ingameSaved: String = "$PREFIX&aSaved crate &f{0}&a.",
    ) {
        fun webLink(url: String): String = webLink.formatArgs(url)
        fun webStopped(count: Int): String = webStopped.formatArgs(count)
        fun webFailed(reason: String): String = webFailed.formatArgs(reason)
        fun webSaved(kind: String, name: String, note: String): String = webSaved.formatArgs(kind, name, note)
        fun pendingHeld(id: Int, target: String, from: String): String = pendingHeld.formatArgs(id, target, from)
        fun pendingApplied(id: Int, target: String): String = pendingApplied.formatArgs(id, target)
        fun pendingDropped(id: Int): String = pendingDropped.formatArgs(id)
        fun promptChat(what: String): String = promptChat.formatArgs(what)
        fun ingameSaved(crate: String): String = ingameSaved.formatArgs(crate)
    }

    @Serializable
    data class MigrateMessages(
        @SerialName("started")
        private val started: String = "$PREFIX&eMigrating from SpecializedCrates at &f{0}&e...",
        @SerialName("finished")
        private val finished: String = "$PREFIX&aMigration complete ({0}): &f{1}&a crates, &f{2}&a animations, &f{3}&a menus, &f{4}&a placed crates, &f{5}&a player records.",
        @SerialName("warning-count")
        private val warningCount: String = "&e{0} warnings were logged (see console for full details).",
        @SerialName("source-missing")
        private val sourceMissing: String = "&cMigration source folder not found: {0}",
    ) {
        fun started(path: String): String = started.formatArgs(path)
        fun finished(mode: String, crates: Int, anims: Int, menus: Int, placed: Int, players: Int): String =
            finished.formatArgs(mode, crates, anims, menus, placed, players)
        fun warningCount(count: Int): String = warningCount.formatArgs(count)
        fun sourceMissing(path: String): String = sourceMissing.formatArgs(path)
    }
}
