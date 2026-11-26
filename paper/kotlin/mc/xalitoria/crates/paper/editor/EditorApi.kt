package mc.xalitoria.crates.paper.editor

import com.xalitoria.util.bukkit.runTask
import com.xalitoria.util.message.info
import com.xalitoria.util.message.send
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.constants.Permissions
import mc.xalitoria.crates.paper.data.model.ConfigProblem
import mc.xalitoria.crates.paper.data.model.Validation
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Player
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * Transport-agnostic API handler shared by the direct [EditorServer] and encrypted [EditorRelay].
 *
 * ```
 * GET    /v1/info
 * GET    /v1/registries
 * GET    /v1/glyphs
 * POST   /v1/items
 * GET    /v1/pack            (supports Range: bytes=start-end for streaming large packs)
 * GET    /v1/skin?url=...    (allowlisted textures.minecraft.net proxy)
 * GET    /v1/crates/<id>
 * PUT    /v1/crates/<id>
 * POST   /v1/crates/<id>/check
 * DELETE /v1/crates/<id>
 * GET    /v1/animations/<id>
 * PUT    /v1/animations/<id>
 * POST   /v1/animations/<id>/check
 * DELETE /v1/animations/<id>
 * ```
 */
internal class EditorApi(
    private val plugin: XaliCrates,
    val files: EditorFiles,
    val pending: PendingEdits,
) {
    private val packs = EditorPacks(plugin)

    data class Call(
        val method: String,
        val path: String,
        val query: String?,
        val body: ByteArray,
        val range: String? = null,
    )

    data class Caller(
        val owner: String,
        val shared: Boolean = false,
        val from: String = owner,
        val readOnly: Boolean = false,
        val autoReload: Boolean = false,
        val initialCrate: String? = null,
        val ownerIp: String? = null,
        val ownerUuid: UUID? = null,
        val clientIp: String? = null,
    )

    data class Reply(
        val status: Int,
        val body: ByteArray = ByteArray(0),
        val type: String = JSON,
        val cache: String? = "no-store",
        val file: Path? = null,
        val headers: Map<String, String> = emptyMap(),
    )

    fun handle(call: Call, caller: Caller): Reply {
        val path = call.path.trimEnd('/').ifEmpty { "/" }
        return when {
            call.method == "GET" && path == "/info" -> info(caller)
            call.method == "GET" && path == "/registries" -> registries()
            call.method == "GET" && path == "/glyphs" -> json(200, GlyphsReply(packs.glyphs()))
            call.method == "POST" && path == "/items" -> {
                val req = Wire.read<ItemsRequest>(call.body.decodeToString()) ?: return json(400, ErrorReply("Malformed JSON request"))
                json(200, ItemsReply(packs.items(req.materials.take(500))))
            }
            call.method == "GET" && path == "/pack" -> handlePack(call)
            call.method == "GET" && path == "/skin" -> handleSkin(call)
            path.startsWith("/crates/") -> handleCrate(call, caller, path.removePrefix("/crates/"))
            path.startsWith("/animations/") -> handleAnimation(call, caller, path.removePrefix("/animations/"))
            else -> json(404, ErrorReply("Unknown endpoint"))
        }
    }

    private fun onlinePlayerFor(clientIp: String?): Player? {
        if (clientIp == null) return null
        val matches = plugin.server.onlinePlayers.filter { player ->
            val ip = runCatching { player.address?.address?.hostAddress }.getOrNull()
            EditorGuard.ipsMatch(ip, clientIp)
        }
        return matches.firstOrNull { runCatching { it.hasPermission(Permissions.ADMIN_EDIT) }.getOrDefault(false) } ?: matches.firstOrNull()
    }

    private fun onlineAdminIps(): List<String> = plugin.server.onlinePlayers.mapNotNull { player ->
        runCatching {
            if (player.hasPermission(Permissions.ADMIN_EDIT)) EditorGuard.normalizeIp(player.address?.address?.hostAddress) else null
        }.getOrNull()
    }

    private fun evaluateAccess(caller: Caller): Pair<EditorGuard.SaveDecision, Player?> {
        val matched = onlinePlayerFor(caller.clientIp)
        val ownerStillAllowed = caller.ownerUuid?.let { uuid ->
            val player = plugin.server.getPlayer(uuid)
            player == null || runCatching { player.hasPermission(Permissions.ADMIN_EDIT) }.getOrDefault(false)
        } ?: true
        val decision = EditorGuard.checkSave(
            policy = plugin.config.data.editor.verifyIp,
            shared = caller.shared,
            readOnly = caller.readOnly,
            ownerIp = caller.ownerIp,
            ownerStillAllowed = ownerStillAllowed,
            clientIp = caller.clientIp,
            onlineAdminIps = onlineAdminIps(),
        )
        return decision to matched
    }

    private fun accessStatus(caller: Caller, decision: EditorGuard.SaveDecision): Pair<Boolean, String?> = when (decision) {
        EditorGuard.SaveDecision.Allow -> true to null
        EditorGuard.SaveDecision.ReadOnly -> false to null
        EditorGuard.SaveDecision.PermissionRevoked -> false to "${caller.owner} no longer has ${Permissions.ADMIN_EDIT} permission to save crates."
        is EditorGuard.SaveDecision.Hold -> if (caller.shared) {
            true to null
        } else {
            false to "IP check (${decision.reason}): your browser IP does not match an online admin in-game. Edits can still be submitted, but saves will be held in /xcrates edits until approved in-game."
        }
    }

    private fun info(caller: Caller): Reply {
        val [decision, matched] = evaluateAccess(caller)
        val [ipVerified, ipNotice] = accessStatus(caller, decision)
        val packFile = packs.pack()
        return json(
            200,
            InfoReply(
                plugin = plugin.name,
                version = plugin.pluginMeta.version,
                serverVersion = plugin.server.minecraftVersion,
                owner = caller.owner,
                shared = caller.shared,
                readOnly = caller.readOnly,
                autoReload = caller.autoReload,
                initialCrate = caller.initialCrate,
                crates = files.listCrates(),
                animations = files.listAnimations(),
                builtinAnimations = Validation.BUILTIN_ANIMATIONS.sorted(),
                pack = packFile != null || packs.packUrl() != null,
                packSize = packFile?.let { runCatching { Files.size(it) }.getOrNull() },
                packHash = packs.packHash(),
                packUrl = packs.packUrl(),
                redeemAvailable = plugin.redeemHook != null,
                economyAvailable = plugin.economyHook != null,
                ipVerified = ipVerified,
                ipNotice = ipNotice,
                clientName = matched?.name,
            ),
        )
    }

    private fun registries(): Reply {
        val access = RegistryAccess.registryAccess()
        val materials = Material.entries.filter { it.isItem && !it.isLegacy }.map { it.key.key }.sorted()
        val sounds = runCatching {
            access.getRegistry(RegistryKey.SOUND_EVENT).map { it.key().value() }.sorted()
        }.getOrDefault([])
        val particles = Particle.entries.map { it.key.key }.sorted()
        val enchantments = runCatching {
            access.getRegistry(RegistryKey.ENCHANTMENT).map { it.key().value() }.sorted()
        }.getOrDefault([])
        val entities = runCatching {
            access.getRegistry(RegistryKey.ENTITY_TYPE).map { it.key().value() }.sorted()
        }.getOrDefault([])
        val potionEffects = runCatching {
            access.getRegistry(RegistryKey.MOB_EFFECT).map { it.key().value() }.sorted()
        }.getOrDefault([])

        return json(
            200,
            RegistriesReply(
                mapOf(
                    "materials" to materials,
                    "sounds" to sounds,
                    "particles" to particles,
                    "enchantments" to enchantments,
                    "entities" to entities,
                    "potionEffects" to potionEffects,
                ),
            ),
            cache = "private, max-age=300",
        )
    }

    private fun handlePack(call: Call): Reply {
        val file = packs.pack() ?: return json(404, ErrorReply("No server resource pack found"))
        val size = Files.size(file)
        val part = call.range?.let { byteRange(it, size) }
        if (call.range != null && part == null) {
            return Reply(
                status = 416,
                body = Wire.write(ErrorReply("Requested byte range is outside the pack")).toByteArray(Charsets.UTF_8),
                type = JSON,
                headers = mapOf("Content-Range" to "bytes */$size"),
            )
        }
        if (part == null) {
            return Reply(status = 200, type = "application/zip", cache = "private, max-age=300", file = file)
        }
        val bytes = Files.newByteChannel(file).use { channel ->
            channel.position(part.first)
            ByteBuffer.allocate((part.last - part.first + 1).toInt()).also { buf ->
                while (buf.hasRemaining() && channel.read(buf) >= 0) { /* fill buffer */ }
            }.array()
        }
        return Reply(
            status = 206,
            body = bytes,
            type = "application/zip",
            cache = "private, max-age=300",
            headers = mapOf("Content-Range" to "bytes ${part.first}-${part.last}/$size"),
        )
    }

    private fun handleSkin(call: Call): Reply {
        val params = parseQuery(call.query) ?: return json(400, ErrorReply("Malformed query parameter"))
        val raw = params["url"] ?: return json(400, ErrorReply("Missing url parameter"))
        val uri = skinAddress(raw) ?: return json(400, ErrorReply("Only skins on textures.minecraft.net can be fetched"))
        val bytes = runCatching {
            Http.getChecked(uri, Duration.ofSeconds(8), MAX_SKIN) { skinAddress(it.toString()) != null }
        }.getOrNull() ?: return json(404, ErrorReply("The skin could not be fetched"))
        return Reply(status = 200, body = bytes, type = "image/png", cache = "max-age=86400")
    }

    private fun handleCrate(call: Call, caller: Caller, rest: String): Reply {
        val isCheck = rest.endsWith("/check")
        val rawId = decodeSegment(if (isCheck) rest.removeSuffix("/check") else rest)
        val id = files.cleanId(rawId) ?: return json(400, ErrorReply("Invalid crate id '$rawId'"))

        return when {
            call.method == "GET" && !isCheck -> {
                val pair = runCatching { files.readCrate(id) }.getOrElse {
                    plugin.logger.warning("Failed to read crate '$id' for editor: ${it.message}")
                    return json(500, ErrorReply("Failed to read crate '$id'"))
                } ?: return json(404, ErrorReply("Crate '$id' does not exist"))
                json(200, CrateDocumentReply(id, pair.first, pair.second))
            }

            call.method == "POST" && isCheck -> {
                val req = Wire.read<CrateWriteRequest>(call.body.decodeToString())
                    ?: return json(400, ErrorReply("Malformed JSON request"))
                val crate = runCatching { files.decodeCrate(req.crate, req.yaml) }.getOrElse {
                    return json(422, ErrorReply(it.message ?: "Could not parse crate"))
                }
                val problems = files.validateCrate(id, crate).map { it.toDto() }
                json(200, ProblemsReply(problems))
            }

            call.method == "PUT" && !isCheck -> {
                val [decision, matched] = evaluateAccess(caller)
                if (decision == EditorGuard.SaveDecision.ReadOnly) return json(403, ErrorReply("This link is read-only"))
                if (decision == EditorGuard.SaveDecision.PermissionRevoked) {
                    return json(403, ErrorReply("${caller.owner} no longer has permission to save crates"))
                }
                val req = Wire.read<CrateWriteRequest>(call.body.decodeToString())
                    ?: return json(400, ErrorReply("Malformed JSON request"))
                val crate = runCatching { files.decodeCrate(req.crate, req.yaml) }.getOrElse {
                    return json(422, ErrorReply(it.message ?: "Could not parse crate"))
                }
                val problems = files.validateCrate(id, crate)
                val dtos = problems.map { it.toDto() }
                if (problems.any { it.severity == ConfigProblem.Severity.ERROR }) {
                    return json(422, ErrorReply(problems.first { it.severity == ConfigProblem.Severity.ERROR }.let { "${it.path}: ${it.message}" }))
                }
                if (decision is EditorGuard.SaveDecision.Hold) {
                    val senderLabel = matched?.name ?: caller.from
                    val privileged = EditorGuard.privilegedCommands(crate)
                    val edit = pending.addCrate(
                        from = senderLabel,
                        name = id,
                        crate = crate,
                        problems = problems.map { "${it.path}: ${it.message}" },
                        clientIp = caller.clientIp,
                        reason = decision.reason,
                        privileged = privileged,
                    )
                    notifyPending(edit)
                    return json(202, HeldReply(dtos, held = true, id = edit.id, reason = decision.reason))
                }
                files.saveCrate(id, crate)
                var reloaded = false
                if (caller.autoReload) {
                    plugin.runTask {
                        plugin.reload()
                        notifySaved("crate", id, "reloaded")
                    }
                    reloaded = true
                } else {
                    plugin.runTask { notifySaved("crate", id, "run /xcrates reload to apply") }
                }
                json(200, SavedReply(dtos, reloaded = reloaded))
            }

            call.method == "DELETE" && !isCheck -> {
                val [decision, _] = evaluateAccess(caller)
                if (decision != EditorGuard.SaveDecision.Allow) {
                    return json(403, ErrorReply("Deleting files requires a verified admin session"))
                }
                val deleted = files.deleteCrate(id)
                if (!deleted) return json(404, ErrorReply("Crate '$id' does not exist"))
                if (caller.autoReload) plugin.runTask { plugin.reload() }
                json(200, SavedReply([], reloaded = caller.autoReload))
            }

            else -> json(405, ErrorReply("Method not allowed"))
        }
    }

    private fun handleAnimation(call: Call, caller: Caller, rest: String): Reply {
        val isCheck = rest.endsWith("/check")
        val rawId = decodeSegment(if (isCheck) rest.removeSuffix("/check") else rest)
        val id = files.cleanId(rawId) ?: return json(400, ErrorReply("Invalid animation id '$rawId'"))

        return when {
            call.method == "GET" && !isCheck -> {
                val pair = runCatching { files.readAnimation(id) }.getOrElse {
                    plugin.logger.warning("Failed to read animation '$id' for editor: ${it.message}")
                    return json(500, ErrorReply("Failed to read animation '$id'"))
                } ?: return json(404, ErrorReply("Animation '$id' does not exist"))
                json(200, AnimationDocumentReply(id, pair.first, pair.second))
            }

            call.method == "POST" && isCheck -> {
                val req = Wire.read<AnimationWriteRequest>(call.body.decodeToString())
                    ?: return json(400, ErrorReply("Malformed JSON request"))
                val anim = runCatching { files.decodeAnimation(req.animation, req.yaml) }.getOrElse {
                    return json(422, ErrorReply(it.message ?: "Could not parse animation"))
                }
                val problems = files.validateAnimation(id, anim).map { it.toDto() }
                json(200, ProblemsReply(problems))
            }

            call.method == "PUT" && !isCheck -> {
                val [decision, matched] = evaluateAccess(caller)
                if (decision == EditorGuard.SaveDecision.ReadOnly) return json(403, ErrorReply("This link is read-only"))
                if (decision == EditorGuard.SaveDecision.PermissionRevoked) {
                    return json(403, ErrorReply("${caller.owner} no longer has permission to save animations"))
                }
                val req = Wire.read<AnimationWriteRequest>(call.body.decodeToString())
                    ?: return json(400, ErrorReply("Malformed JSON request"))
                val anim = runCatching { files.decodeAnimation(req.animation, req.yaml) }.getOrElse {
                    return json(422, ErrorReply(it.message ?: "Could not parse animation"))
                }
                val problems = files.validateAnimation(id, anim)
                val dtos = problems.map { it.toDto() }
                if (problems.any { it.severity == ConfigProblem.Severity.ERROR }) {
                    return json(422, ErrorReply(problems.first { it.severity == ConfigProblem.Severity.ERROR }.let { "${it.path}: ${it.message}" }))
                }
                if (decision is EditorGuard.SaveDecision.Hold) {
                    val senderLabel = matched?.name ?: caller.from
                    val edit = pending.addAnimation(
                        from = senderLabel,
                        name = id,
                        animation = anim,
                        problems = problems.map { "${it.path}: ${it.message}" },
                        clientIp = caller.clientIp,
                        reason = decision.reason,
                    )
                    notifyPending(edit)
                    return json(202, HeldReply(dtos, held = true, id = edit.id, reason = decision.reason))
                }
                files.saveAnimation(id, anim)
                var reloaded = false
                if (caller.autoReload) {
                    plugin.runTask {
                        plugin.reload()
                        notifySaved("animation", id, "reloaded")
                    }
                    reloaded = true
                } else {
                    plugin.runTask { notifySaved("animation", id, "run /xcrates reload to apply") }
                }
                json(200, SavedReply(dtos, reloaded = reloaded))
            }

            call.method == "DELETE" && !isCheck -> {
                val [decision, _] = evaluateAccess(caller)
                if (decision != EditorGuard.SaveDecision.Allow) {
                    return json(403, ErrorReply("Deleting files requires a verified admin session"))
                }
                val deleted = files.deleteAnimation(id)
                if (!deleted) return json(404, ErrorReply("Animation '$id' does not exist"))
                if (caller.autoReload) plugin.runTask { plugin.reload() }
                json(200, SavedReply([], reloaded = caller.autoReload))
            }

            else -> json(405, ErrorReply("Method not allowed"))
        }
    }

    private fun notifyPending(edit: PendingEdits.Edit) {
        val label = "${edit.kind.name.lowercase()}/${edit.name}"
        val privSuffix = if (edit.privileged.isNotEmpty()) " &c[${edit.privileged.size} console cmd(s) — run /xcrates edits inspect ${edit.id}]" else ""
        val msg = plugin.messageConfig.data.editor.pendingHeld(edit.id, label, edit.from) + privSuffix
        plugin.runTask {
            Bukkit.getOnlinePlayers().filter { it.hasPermission(Permissions.ADMIN_EDIT) }.forEach { it.send(msg) }
            Bukkit.getConsoleSender().info(msg)
        }
    }

    private fun notifySaved(kind: String, id: String, note: String) {
        val msg = plugin.messageConfig.data.editor.webSaved(kind, id, note)
        Bukkit.getOnlinePlayers().filter { it.hasPermission(Permissions.ADMIN_EDIT) }.forEach { it.send(msg) }
    }

    private fun ConfigProblem.toDto(): ProblemDto = ProblemDto(path, message, severity.name)

    private inline fun <reified T> json(status: Int, payload: T, cache: String? = "no-store"): Reply =
        Reply(status, Wire.write(payload).toByteArray(Charsets.UTF_8), JSON, cache)

    private fun decodeSegment(segment: String): String =
        runCatching { URLDecoder.decode(segment, Charsets.UTF_8) }.getOrDefault(segment)

    private fun parseQuery(query: String?): Map<String, String>? {
        if (query.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (entry in query.split('&')) {
            val eq = entry.indexOf('=').takeIf { it > 0 } ?: continue
            val value = runCatching { URLDecoder.decode(entry.substring(eq + 1), Charsets.UTF_8) }.getOrNull() ?: return null
            out[entry.substring(0, eq)] = value
        }
        return out
    }

    companion object {
        const val MAX_BODY = 2 * 1024 * 1024
        const val MAX_PART = 4 * 1024 * 1024
        private const val MAX_SKIN = 256 * 1024
        private const val JSON = "application/json; charset=utf-8"
        private val SKIN_PATH = Regex("/texture/[0-9a-f]{16,128}")

        /** Parses `Range: bytes=<first>-<last>` capped to [MAX_PART] and [size]. */
        fun byteRange(header: String, size: Long): LongRange? {
            val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(header.trim()) ?: return null
            val first = match.groupValues[1].toLongOrNull() ?: return null
            val last = match.groupValues[2].toLongOrNull() ?: (size - 1)
            if (first >= size || last < first) return null
            return first..minOf(last, size - 1, first + MAX_PART - 1)
        }

        /** Allowlists only `https://textures.minecraft.net/texture/<hex>` (prevents SSRF). */
        fun skinAddress(text: String): URI? {
            val uri = runCatching { URI(text.replaceFirst("http://", "https://")) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host != "textures.minecraft.net" || uri.port != -1 || uri.userInfo != null) return null
            if (uri.rawQuery != null || uri.rawFragment != null || !SKIN_PATH.matches(uri.rawPath)) return null
            return uri
        }
    }
}
