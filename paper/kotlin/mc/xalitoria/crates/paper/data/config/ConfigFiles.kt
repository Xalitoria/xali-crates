package mc.xalitoria.crates.paper.data.config

import com.xalitoria.util.config.YamlFile
import java.nio.file.Path

/**
 * Loads `config.yml` and `language/<language>.yml` from [dataPath]. `YamlFile.load()` is what writes the defaults on the
 * first start, so both files are loaded here and never just constructed.
 */
object ConfigFiles {
    fun main(dataPath: Path): YamlFile<MainConfig> =
        YamlFile(dataPath.resolve("config.yml"), MainConfig.serializer(), default = { MainConfig() })
            .also { it.load() }
            .also { it.data.validate() }

    fun messages(dataPath: Path, language: String): YamlFile<MessageConfig> =
        YamlFile(dataPath.resolve("language/$language.yml"), MessageConfig.serializer(), default = { MessageConfig() })
            .also { it.load() }
}
