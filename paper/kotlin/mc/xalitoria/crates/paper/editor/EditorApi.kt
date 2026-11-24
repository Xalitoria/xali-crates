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
import java.net.URLDecoder

/**
 * Transport-agnostic API handler shared by the direct [EditorServer] and encrypted [EditorRelay].
 *
 * ```
 * GET    /v1/info
 * GET    /v1/registries
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
    data class Call(val method: String, val path: String, val query: String?, val body: ByteArray)

    data class Caller(
        val owner: String,
        val shared: Boolean = false,
        val from: String = owner,
        val readOnly: Boolean = false,
        val autoReload: Boolean = false,
        val initialCrate: String? = null,
    )

    data class Reply(
        val status: Int,
        val body: ByteArray,
        val type: String = JSON,
        val cache: String? = "no-store",
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
            call.method == "GET" && path == "/pack" -> {
                val file = packs.pack() ?: return json(404, ErrorReply("No server resource pack found"))
                val bytes = java.nio.file.Files.readAllBytes(file)
                Reply(200, bytes, type = "application/zip", cache = "private, max-age=300")
            }
            path.startsWith("/crates/") -> handleCrate(call, caller, path.removePrefix("/crates/"))
            path.startsWith("/animations/") -> handleAnimation(call, caller, path.removePrefix("/animations/"))
            else -> json(404, ErrorReply("Unknown endpoint: ${call.method} $path"))
        }
    }

    private fun info(caller: Caller): Reply = json(
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
            pack = packs.pack() != null,
            redeemAvailable = plugin.redeemHook != null,
            economyAvailable = plugin.economyHook != null,
        ),
    )

    private fun registries(): Reply {
        val materials = Material.entries.filter { it.isItem && !it.isLegacy }.map { it.key.key }.sorted()
        val sounds = runCatching {
            RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT).map { it.key.key }.sorted()
        }.getOrDefault([])
        val particles = Particle.entries.map { it.key.key }.sorted()
        val enchantments = runCatching {
            RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).map { it.key.key }.sorted()
        }.getOrDefault([])
        val entities = runCatching {
            RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE).map { it.key.key }.sorted()
        }.getOrDefault([])
        val potionEffects = runCatching {
            RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).map { it.key.key }.sorted()
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

    private fun handleCrate(call: Call, caller: Caller, rest: String): Reply {
        val isCheck = rest.endsWith("/check")
        val rawId = decodeSegment(if (isCheck) rest.removeSuffix("/check") else rest)
        val id = files.cleanId(rawId) ?: return json(400, ErrorReply("Invalid crate id '$rawId'"))

        return when {
            call.method == "GET" && !isCheck -> {
                val pair = runCatching { files.readCrate(id) }.getOrElse {
                    return json(500, ErrorReply("Failed to read crate '$id': ${it.message}"))
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
                if (caller.readOnly) return json(403, ErrorReply("This link is read-only"))
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
                if (caller.shared) {
                    val edit = pending.addCrate(caller.from, id, crate, problems.map { "${it.path}: ${it.message}" })
                    notifyPending(edit)
                    return json(202, HeldReply(dtos, held = true, id = edit.id))
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
                if (caller.readOnly || caller.shared) return json(403, ErrorReply("Deleting files requires a direct admin session"))
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
                    return json(500, ErrorReply("Failed to read animation '$id': ${it.message}"))
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
                if (caller.readOnly) return json(403, ErrorReply("This link is read-only"))
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
                if (caller.shared) {
                    val edit = pending.addAnimation(caller.from, id, anim, problems.map { "${it.path}: ${it.message}" })
                    notifyPending(edit)
                    return json(202, HeldReply(dtos, held = true, id = edit.id))
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
                if (caller.readOnly || caller.shared) return json(403, ErrorReply("Deleting files requires a direct admin session"))
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
        val msg = plugin.messageConfig.data.editor.pendingHeld(edit.id, label, edit.from)
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

    companion object {
        const val MAX_BODY = 2 * 1024 * 1024
        private const val JSON = "application/json; charset=utf-8"
    }
}
