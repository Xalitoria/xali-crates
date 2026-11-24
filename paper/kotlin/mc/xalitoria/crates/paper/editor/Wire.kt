package mc.xalitoria.crates.paper.editor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Typed JSON wire protocol shared between the plugin's editor HTTP/relay bridge and `crates/editor/web/`.
 */
internal object Wire {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    inline fun <reified T> write(value: T): String = json.encodeToString(value)

    inline fun <reified T> read(text: String): T? = try {
        json.decodeFromString<T>(text)
    } catch (_: Exception) {
        null
    }
}

@Serializable internal data class ErrorReply(val error: String)

@Serializable internal data class ProblemDto(
    val path: String,
    val message: String,
    val severity: String,
)

@Serializable internal data class ProblemsReply(val problems: List<ProblemDto>)

@Serializable internal data class SavedReply(val problems: List<ProblemDto>, val reloaded: Boolean)

@Serializable internal data class HeldReply(val problems: List<ProblemDto>, val held: Boolean, val id: Int)

@Serializable internal data class InfoReply(
    val plugin: String,
    val version: String,
    val serverVersion: String? = null,
    val owner: String,
    val shared: Boolean,
    val readOnly: Boolean,
    val autoReload: Boolean = false,
    val initialCrate: String? = null,
    val crates: List<String>,
    val animations: List<String>,
    val builtinAnimations: List<String>,
    val pack: Boolean = false,
    val redeemAvailable: Boolean = false,
    val economyAvailable: Boolean = false,
)

@Serializable internal data class GlyphDto(val name: String, val char: String, val plugin: String, val font: String? = null)
@Serializable internal data class GlyphsReply(val glyphs: List<GlyphDto>)
@Serializable internal data class ItemsRequest(val materials: List<String> = [])
@Serializable internal data class ItemInfoDto(val material: String, val itemModel: String?, val customModelData: List<Double>?, val maxDamage: Int)
@Serializable internal data class ItemsReply(val items: Map<String, ItemInfoDto>)

@Serializable internal data class CrateDocumentReply(val id: String, val crate: JsonElement, val yaml: String)

@Serializable internal data class AnimationDocumentReply(val id: String, val animation: JsonElement, val yaml: String)

@Serializable internal data class CrateWriteRequest(val crate: JsonObject? = null, val yaml: String? = null)

@Serializable internal data class AnimationWriteRequest(val animation: JsonObject? = null, val yaml: String? = null)

@Serializable internal data class RegistriesReply(val registries: Map<String, List<String>>)

// ---- Relay frames (compatible with menu/relay)

@Serializable internal data class RelayCreateRequest(val ttl: Int, val id: String? = null)

@Serializable internal data class RelaySession(val id: String, val secret: String, val ws: Boolean = false)

@Serializable internal data class RelayCall(val id: String? = null, val data: String? = null, val fetch: Boolean = false)

@Serializable internal data class RelayWsAnswer(val id: String, val data: String)

@Serializable internal data class RelayRequest(
    val rid: String? = null,
    val t: Long? = null,
    val method: String? = null,
    val path: String? = null,
    val body: String? = null,
    val from: String? = null,
)

@Serializable internal data class RelayReply(
    val rid: String,
    val status: Int,
    val type: String,
    val body: String,
)
