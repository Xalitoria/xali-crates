import mc.xalitoria.gradle.sibling

plugins {
    kotlin("plugin.serialization")
}

// The web editor for XaliCrates, served as static files. Its form and checks are driven by schema.json, which this
// module writes from the plugin's Kotlin serializer descriptors and KDoc comments so the editor cannot drift from
// the configuration model.
dependencies {
    implementation(sibling("paper"))
    implementation(libs.folia.api)
    implementation(libs.xalicraft.util)
}

val pluginJar = "${sibling("paper").path}:shadowJar"

tasks.test {
    useJUnitPlatform()
}

tasks.configureEach {
    if (name in setOf("compileKotlin", "compileTestKotlin", "test")) dependsOn(pluginJar)
}

val cratesDirectory = projectDir.parentFile

// Writes web/schema.json. Run after changing CrateConfig, AnimationConfig, or their KDoc comments.
val schema = tasks.register<JavaExec>("schema") {
    group = "editor"
    description = "Writes web/schema.json from the crate and animation model classes and KDoc comments."
    classpath = sourceSets.main.get().runtimeClasspath
    dependsOn(pluginJar)
    mainClass = "com.xalitoria.crates.editor.DumpSchemaKt"
    args(file("web/schema.json"), cratesDirectory, project.version)
    inputs.files(fileTree(cratesDirectory.resolve("paper/kotlin")))
    outputs.file("web/schema.json")
}
