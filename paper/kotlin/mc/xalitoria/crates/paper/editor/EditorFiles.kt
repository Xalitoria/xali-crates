package mc.xalitoria.crates.paper.editor

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.data.model.AnimationConfig
import mc.xalitoria.crates.paper.data.model.ConfigProblem
import mc.xalitoria.crates.paper.data.model.CrateConfig
import mc.xalitoria.crates.paper.data.model.Validation
import mc.xalitoria.crates.paper.service.CrateRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Reads, parses, validates, and writes crate and animation files under `plugins/XaliCrates/` for the web editor.
 */
internal class EditorFiles(private val plugin: XaliCrates) {
    private val cratesDir: Path get() = plugin.dataPath.resolve("crates")
    private val animsDir: Path get() = plugin.dataPath.resolve("animations")

    fun listCrates(): List<String> = listIds(cratesDir)

    fun listAnimations(): List<String> = listIds(animsDir)

    fun readCrate(id: String): Pair<JsonElement, String>? {
        val clean = cleanId(id) ?: return null
        val file = resolveYaml(cratesDir, clean) ?: return null
        val text = file.readText()
        val parsed = CrateRegistry.yaml.decodeFromString(CrateConfig.serializer(), text)
        val json = Wire.json.encodeToJsonElement(CrateConfig.serializer(), parsed)
        return json to text
    }

    fun readAnimation(id: String): Pair<JsonElement, String>? {
        val clean = cleanId(id) ?: return null
        val file = resolveYaml(animsDir, clean) ?: return null
        val text = file.readText()
        val parsed = CrateRegistry.yaml.decodeFromString(AnimationConfig.serializer(), text)
        val json = Wire.json.encodeToJsonElement(AnimationConfig.serializer(), parsed)
        return json to text
    }

    fun decodeCrate(json: JsonObject?, yaml: String?): CrateConfig = when {
        yaml != null -> CrateRegistry.yaml.decodeFromString(CrateConfig.serializer(), yaml)
        json != null -> Wire.json.decodeFromJsonElement(CrateConfig.serializer(), json)
        else -> error("Request must include either 'crate' JSON or 'yaml' text")
    }

    fun decodeAnimation(json: JsonObject?, yaml: String?): AnimationConfig = when {
        yaml != null -> CrateRegistry.yaml.decodeFromString(AnimationConfig.serializer(), yaml)
        json != null -> Wire.json.decodeFromJsonElement(AnimationConfig.serializer(), json)
        else -> error("Request must include either 'animation' JSON or 'yaml' text")
    }

    fun validateCrate(id: String, crate: CrateConfig): List<ConfigProblem> {
        val knownAnims = (listAnimations() + plugin.animationConfigs.keys).toSet()
        val knownCrates = (listCrates() + plugin.crates.keys + id).toSet()
        return Validation.validateCrate(id, crate, knownAnims, knownCrates)
    }

    fun validateAnimation(id: String, anim: AnimationConfig): List<ConfigProblem> =
        Validation.validateAnimation(id, anim)

    fun saveCrate(id: String, crate: CrateConfig) {
        CrateRegistry.saveCrate(cratesDir, id, crate)
    }

    fun saveAnimation(id: String, anim: AnimationConfig) {
        CrateRegistry.saveAnimation(animsDir, id, anim)
    }

    fun deleteCrate(id: String): Boolean = cleanId(id)?.let { CrateRegistry.deleteCrateFile(cratesDir, it) } ?: false

    fun deleteAnimation(id: String): Boolean = cleanId(id)?.let { CrateRegistry.deleteAnimationFile(animsDir, it) } ?: false

    fun cleanId(raw: String): String? {
        val id = raw.trim().lowercase()
        return id.takeIf { Validation.ID_PATTERN.matches(it) }
    }

    private fun listIds(dir: Path): List<String> {
        if (!dir.exists()) return []
        return Files.list(dir).use { stream ->
            stream.filter { it.isRegularFile() && it.extension.lowercase() in setOf("yml", "yaml") }
                .map { it.nameWithoutExtension.lowercase() }
                .sorted()
                .distinct()
                .toList()
        }
    }

    private fun resolveYaml(dir: Path, id: String): Path? {
        val yml = dir.resolve("$id.yml")
        if (yml.exists()) return yml
        val yaml = dir.resolve("$id.yaml")
        if (yaml.exists()) return yaml
        return null
    }
}
