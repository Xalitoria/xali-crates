package mc.xalitoria.crates.paper

import com.xalitoria.util.bukkit.coroutineScope
import com.xalitoria.util.bukkit.register
import com.xalitoria.util.bukkit.runTask
import com.xalitoria.util.config.YamlFile
import com.xalitoria.util.message.fail
import com.xalitoria.util.message.send
import com.xalitoria.util.message.toComponent
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mc.xalitoria.crates.paper.animation.AnimationManager
import mc.xalitoria.crates.paper.api.CratesApiImpl
import mc.xalitoria.crates.paper.command.CratesCommand
import mc.xalitoria.crates.paper.data.config.MainConfig
import mc.xalitoria.crates.paper.data.config.MessageConfig
import mc.xalitoria.crates.paper.data.constants.Permissions
import mc.xalitoria.crates.paper.data.db.DatabaseManager
import mc.xalitoria.crates.paper.data.model.AnimationConfig
import mc.xalitoria.crates.paper.data.model.ConfigProblem
import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.editor.EditorApi
import mc.xalitoria.crates.paper.editor.EditorFiles
import mc.xalitoria.crates.paper.editor.EditorRelay
import mc.xalitoria.crates.paper.editor.EditorServer
import mc.xalitoria.crates.paper.editor.EditorSessions
import mc.xalitoria.crates.paper.editor.PendingEdits
import mc.xalitoria.crates.paper.external.CratesExpansion
import mc.xalitoria.crates.paper.external.CustomItemHook
import mc.xalitoria.crates.paper.external.EconomyHook
import mc.xalitoria.crates.paper.external.MenuHook
import mc.xalitoria.crates.paper.external.RedeemHook
import mc.xalitoria.crates.paper.gui.editor.CrateEditorManager
import mc.xalitoria.crates.paper.listener.CrateListeners
import mc.xalitoria.crates.paper.service.CrateOpenService
import mc.xalitoria.crates.paper.service.CrateRegistry
import mc.xalitoria.crates.paper.service.EffectPlayer
import mc.xalitoria.crates.paper.service.ItemFactory
import mc.xalitoria.crates.paper.service.KeyService
import mc.xalitoria.crates.paper.service.LuckyChestService
import mc.xalitoria.crates.paper.service.PlacedCrateService
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.xalitoria.crates.XaliCratesApi
import org.bukkit.command.CommandSender
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.net.URLEncoder
import java.text.MessageFormat

/**
 * XaliCrates Paper/Folia plugin entry point.
 *
 * Depends on `XaliMenu` for customizable player-facing menus, soft-depends on `XaliRedeem` for full-inventory overflow
 * and dynamic non-config rewards, and provides both a non-editable in-game admin crate editor and a live web editor.
 */
class XaliCrates : JavaPlugin() {
    lateinit var config: YamlFile<MainConfig>
        private set

    lateinit var messageConfig: YamlFile<MessageConfig>
        private set

    lateinit var scope: CoroutineScope
        private set

    @Volatile
    var crates: Map<String, CrateConfig> = emptyMap()
        internal set

    @Volatile
    var animationConfigs: Map<String, AnimationConfig> = emptyMap()
        internal set

    @Volatile
    var problems: Map<String, List<ConfigProblem>> = emptyMap()
        internal set

    lateinit var items: ItemFactory
        private set

    lateinit var effects: EffectPlayer
        private set

    lateinit var keys: KeyService
        private set

    lateinit var placed: PlacedCrateService
        private set

    lateinit var luckyChests: LuckyChestService
        private set

    lateinit var openService: CrateOpenService
        private set

    lateinit var animations: AnimationManager
        private set

    lateinit var editorGui: CrateEditorManager
        private set

    lateinit var menuHook: MenuHook
        private set

    var redeemHook: RedeemHook? = null
        private set

    var economyHook: EconomyHook? = null
        private set

    var hasPlaceholders: Boolean = false
        private set

    internal lateinit var editorSessions: EditorSessions
        private set

    internal lateinit var pendingEdits: PendingEdits
        private set

    internal lateinit var editorFiles: EditorFiles
        private set

    internal lateinit var editorServer: EditorServer
        private set

    internal lateinit var editorRelay: EditorRelay
        private set

    private var expansion: CratesExpansion? = null

    override fun onEnable() {
        instance = this
        scope = coroutineScope()

        val pm = server.pluginManager
        if (!pm.isPluginEnabled("XaliMenu")) {
            logger.severe("XaliMenu is required for XaliCrates player menus; disabling.")
            pm.disablePlugin(this)
            return
        }

        try {
            config = loadMainConfig()
            messageConfig = loadMessages(config.data)
        } catch (e: Exception) {
            logger.severe("Failed to load XaliCrates configuration: ${e.message}")
            pm.disablePlugin(this)
            return
        }
        debugEnabled = config.data.debug

        try {
            DatabaseManager.connect(this, config.data.database)
        } catch (e: Exception) {
            logger.severe("Failed to connect to the XaliCrates database: ${e.message}")
            pm.disablePlugin(this)
            return
        }

        registerPermissions()

        items = ItemFactory(this)
        effects = EffectPlayer(this)
        keys = KeyService(this)
        placed = PlacedCrateService(this).also { it.particlesEnabled = config.data.particles.enabled }
        luckyChests = LuckyChestService(this)
        openService = CrateOpenService(this)
        animations = AnimationManager(this)
        editorGui = CrateEditorManager(this)

        CrateRegistry.writeDefaultsIfEmpty(dataPath)
        loadCatalog()

        hasPlaceholders = pm.isPluginEnabled("PlaceholderAPI")
        if (pm.isPluginEnabled("XaliRedeem")) {
            redeemHook = runCatching { RedeemHook(this) }.getOrNull()
        }
        if (pm.isPluginEnabled("Vault") || pm.isPluginEnabled("VaultUnlocked")) {
            economyHook = runCatching { EconomyHook.create() }.getOrNull()
        }

        menuHook = MenuHook(this)
        if (config.data.menus.writeDefaults) {
            val written = menuHook.writeDefaultMenus()
            if (written.isNotEmpty()) {
                logger.info("Wrote default XaliMenu configs: ${written.joinToString(", ")}")
            }
        }

        if (hasPlaceholders) {
            expansion = CratesExpansion(this).also { it.register() }
        }

        editorSessions = EditorSessions({ config.data.editor.sessionMinutes })
        pendingEdits = PendingEdits()
        editorServer = EditorServer(this, editorSessions, pendingEdits)
        editorFiles = editorServer.files
        editorRelay = EditorRelay(this, editorServer.api)
        if (config.data.editor.enabled) {
            editorServer.start()?.let { err -> logger.warning("Could not start web editor HTTP endpoint: $err") }
        }

        server.servicesManager.register(XaliCratesApi::class.java, CratesApiImpl(this), this, ServicePriority.Normal)
        pm.registerEvents(CrateListeners(this), this)

        scope.launch {
            runCatching { placed.loadAllFromDb() }
                .onFailure { logger.warning("Failed to load placed crates: ${it.message}") }
            for (player in server.onlinePlayers) {
                runCatching {
                    keys.load(player.uniqueId)
                    openService.loadPlayer(player.uniqueId)
                }
            }
        }

        val cmd = CratesCommand(this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(this, cmd.mainBuilder().build(), cmd.description, cmd.aliases)
            event.registrar().register(this, cmd.keysBuilder().build(), cmd.keysDescription, cmd.keysAliases)
            event.registrar().register(this, cmd.rewardsBuilder().build(), cmd.rewardsDescription, cmd.rewardsAliases)
        }

        logger.info(
            "Enabled XaliCrates: ${crates.size} crate(s), ${animationConfigs.size} custom animation(s), " +
                "redeem=${redeemHook != null}, economy=${economyHook != null}, placeholders=$hasPlaceholders",
        )
    }

    override fun onDisable() {
        server.servicesManager.unregisterAll(this)
        expansion?.unregister()
        if (::editorRelay.isInitialized) editorRelay.stop()
        if (::editorServer.isInitialized) editorServer.stop()
        if (::animations.isInitialized) animations.shutdown()
        if (::placed.isInitialized) placed.shutdown()
        if (::scope.isInitialized) scope.cancel("XaliCrates disabling")
        DatabaseManager.close()
        if (instance === this) instance = null
    }

    fun reload(): Pair<Int, Int> {
        val nextConfig = loadMainConfig()
        val nextMessages = loadMessages(nextConfig.data)
        config = nextConfig
        messageConfig = nextMessages
        debugEnabled = config.data.debug
        placed.particlesEnabled = config.data.particles.enabled
        loadCatalog()
        placed.rebuildVisuals()
        if (config.data.editor.enabled && !editorServer.running) {
            editorServer.start()?.let { err -> logger.warning("Could not start web editor HTTP endpoint: $err") }
        } else if (!config.data.editor.enabled && editorServer.running) {
            editorServer.stop()
        }
        return crates.size to animationConfigs.size
    }

    fun toggleDebug(): Boolean {
        debugEnabled = !debugEnabled
        return debugEnabled
    }

    fun displayNameOf(id: String, crate: CrateConfig): String =
        crate.displayName?.takeIf { it.isNotBlank() } ?: crate.crateItem.name?.takeIf { it.isNotBlank() } ?: id

    fun startWebEditor(sender: CommandSender, initialCrate: String?, autoReload: Boolean) {
        startWebSession(sender, initialCrate, shared = false, readOnly = false, autoReload = autoReload)
    }

    fun startSharedWebEditor(sender: CommandSender, initialCrate: String?, readOnly: Boolean) {
        startWebSession(sender, initialCrate, shared = true, readOnly = readOnly, autoReload = false)
    }

    fun stopWebSessions(owner: String?, shared: Boolean): Int {
        val direct = editorSessions.clearMatching(owner = owner, shared = shared)
        val relayed = editorRelay.stop(owner = owner, shared = shared)
        return direct + relayed
    }

    private fun startWebSession(
        sender: CommandSender,
        initialCrate: String?,
        shared: Boolean,
        readOnly: Boolean,
        autoReload: Boolean,
    ) {
        val settings = config.data.editor
        val cleanCrate = initialCrate?.lowercase()?.takeIf { it in crates }
        val owner = sender.name

        if (settings.enabled && editorServer.running) {
            val token = editorSessions.create(
                owner = owner,
                shared = shared,
                readOnly = readOnly,
                autoReload = autoReload,
                initialCrate = cleanCrate,
            )
            val apiUrl = settings.publicUrl.trim().trimEnd('/').ifEmpty { "http://${settings.host}:${settings.port}" }
            val site = settings.site.trimEnd('/')
            val link = buildString {
                append("$site/#api=").append(URLEncoder.encode(apiUrl, Charsets.UTF_8))
                append("&token=").append(token)
                if (cleanCrate != null) append("&crate=").append(URLEncoder.encode(cleanCrate, Charsets.UTF_8))
            }
            sendClickableLink(sender, link)
            return
        }

        scope.launch {
            val result = editorRelay.start(
                owner = owner,
                crate = cleanCrate,
                readOnly = readOnly,
                shared = shared,
                autoReload = autoReload,
            )
            runTask {
                when (result) {
                    is EditorRelay.Started.Ok -> sendClickableLink(sender, result.link)
                    is EditorRelay.Started.Failed -> sender send messageConfig.data.editor.webFailed(result.reason)
                }
            }
        }
    }

    private fun sendClickableLink(sender: CommandSender, url: String) {
        val text = messageConfig.data.editor.webLink(url)
        val component = text.toComponent()
            .clickEvent(ClickEvent.openUrl(url))
            .hoverEvent(HoverEvent.showText("&aClick to open the XaliCrates web editor".toComponent()))
        sender.sendMessage(component)
    }

    private fun loadCatalog() {
        val loaded = CrateRegistry.loadAll(dataPath)
        crates = loaded.crates
        animationConfigs = loaded.animations
        problems = loaded.problems
        if (problems.isNotEmpty()) {
            val errs = problems.values.flatten().count { it.severity == ConfigProblem.Severity.ERROR }
            val warns = problems.values.flatten().count { it.severity == ConfigProblem.Severity.WARNING }
            logger.warning("Loaded crates with $errs error(s) and $warns warning(s). Run /xalicrates errors for details.")
        }
    }

    private fun loadMainConfig(): YamlFile<MainConfig> {
        val file = YamlFile(dataPath.resolve("config.yml"), MainConfig.serializer(), default = { MainConfig() })
        file.data.validate()
        return file
    }

    private fun loadMessages(cfg: MainConfig): YamlFile<MessageConfig> =
        YamlFile(dataPath.resolve("language/${cfg.language}.yml"), MessageConfig.serializer(), default = { MessageConfig() })

    private fun registerPermissions() {
        val pm = server.pluginManager
        val defaults = listOf(
            Permissions.USE to PermissionDefault.TRUE,
            Permissions.KEYS to PermissionDefault.TRUE,
            Permissions.KEYS_SEND to PermissionDefault.TRUE,
            Permissions.CLAIM to PermissionDefault.TRUE,
            Permissions.PREVIEW to PermissionDefault.TRUE,
            Permissions.HISTORY to PermissionDefault.TRUE,
            Permissions.LUCKY_CHEST to PermissionDefault.TRUE,
            Permissions.LUCKY_CHEST_TOGGLE to PermissionDefault.TRUE,
            Permissions.PLACE_BYPASS to PermissionDefault.OP,
            Permissions.ADMIN to PermissionDefault.OP,
            Permissions.ADMIN_GIVE to PermissionDefault.OP,
            Permissions.ADMIN_TAKE to PermissionDefault.OP,
            Permissions.ADMIN_EDIT to PermissionDefault.OP,
            Permissions.ADMIN_MIGRATE to PermissionDefault.OP,
        )
        for ([node, def] in defaults) {
            if (pm.getPermission(node) == null) pm.addPermission(Permission(node, def))
        }
    }

    companion object {
        @Volatile
        private var instance: XaliCrates? = null

        @Volatile
        private var debugEnabled: Boolean = false

        fun debug(message: String, vararg args: Any?) {
            if (!debugEnabled) return
            val formatted = if (args.isEmpty()) message else runCatching { MessageFormat.format(message, *args) }.getOrDefault(message)
            instance?.logger?.info("[DEBUG] $formatted")
        }
    }
}
