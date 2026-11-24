import com.xalitoria.crates.editor.SchemaFile
import com.xalitoria.crates.editor.schema
import com.xalitoria.crates.editor.schemaJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class SchemaTest {
    private val cratesDir: Path = Path.of(System.getProperty("user.dir")).parent
    private val file: Path = cratesDir.resolve("editor/web/schema.json")

    @Test
    fun webSchemaJsonIsUpToDate() {
        assertTrue(Files.exists(file), "Missing $file — run ./gradlew :crates:editor:schema")
        val fresh = schema(cratesDir, "test")
        val current = Files.readString(file).replace(Regex("\"version\": \"[^\"]*\""), "\"version\": \"test\"")
        assertEquals(fresh, current, "crates/editor/web/schema.json is out of date — run ./gradlew :crates:editor:schema")
    }

    @Test
    fun schemaCoversCrateAndAnimationRootsWithDocs() {
        val parsed = schemaJson.decodeFromString(SchemaFile.serializer(), Files.readString(file))
        assertEquals("CrateConfig", parsed.crateRoot)
        assertEquals("AnimationConfig", parsed.animationRoot)
        assertTrue("CrateConfig" in parsed.types)
        assertTrue("RewardConfig" in parsed.types)
        assertTrue("AnimationConfig" in parsed.types)
        assertTrue("AnimationStage" in parsed.types)
        assertTrue("csgo" in parsed.builtinAnimations)
        val rewardFields = parsed.types.getValue("RewardConfig").fields.associateBy { it.key }
        assertTrue("weight" in rewardFields)
        assertTrue("display-item" in rewardFields)
        assertTrue("redeem" in rewardFields)
        assertTrue(!rewardFields.getValue("weight").doc.isNullOrBlank())
    }
}
