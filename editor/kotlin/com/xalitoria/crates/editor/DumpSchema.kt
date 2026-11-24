package com.xalitoria.crates.editor

import java.nio.file.Files
import java.nio.file.Path

fun schema(cratesDir: Path, version: String): String {
    val docs = SourceDocs.scan(cratesDir.resolve("paper/kotlin/mc/xalitoria/crates/paper/data/model"))
    val built = SchemaBuilder(docs, version).build()
    return schemaJson.encodeToString(SchemaFile.serializer(), built) + "\n"
}

fun main(args: Array<String>) {
    val target = Path.of(args[0])
    Files.createDirectories(target.parent)
    Files.writeString(target, schema(Path.of(args[1]), args[2]))
    println("Wrote $target")
}
