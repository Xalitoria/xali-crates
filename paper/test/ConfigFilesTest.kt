package mc.xalitoria.crates.paper

import mc.xalitoria.crates.paper.data.config.ConfigFiles
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConfigFilesTest {

    @Test
    fun firstStartWritesConfigAndLanguageFile(@TempDir dir: Path) {
        val config = ConfigFiles.main(dir)
        ConfigFiles.messages(dir, config.data.language)

        assertTrue(Files.exists(dir.resolve("config.yml")), "config.yml was not written")
        assertTrue(Files.exists(dir.resolve("language/en-us.yml")), "language/en-us.yml was not written")
    }

    @Test
    fun editedLanguageFileIsReadBack(@TempDir dir: Path) {
        ConfigFiles.messages(dir, "en-us")
        val file = dir.resolve("language/en-us.yml")
        val original = Files.readString(file)
        assertTrue("XALI" in original, "default prefix missing from the written file")

        Files.writeString(file, original.replace("CRATES", "VAULTS"))
        val reloaded = ConfigFiles.messages(dir, "en-us")

        assertTrue("VAULTS" in Files.readString(file))
        assertTrue("VAULTS" in reloaded.data.commands.help("xalicrates", false), "edited prefix was not read back")
    }
}
