import mc.xalitoria.gradle.sibling
import mc.xalitoria.gradle.repo.mavenNexo

plugins {
    kotlin("plugin.serialization")
    alias(libs.plugins.shadow)
}

repositories {
    mavenNexo()
}

dependencies {
    implementation(sibling("api"))
    // Libraries the server downloads when the plugin loads (see loader.json); nothing declared here is shaded.
    api(libs.xalicraft.util)
    api(libs.bundles.database)
    api(libs.bundles.exposed)

    // Folia's API is Paper's plus the regionised scheduler, so it covers both platforms.
    compileOnly(libs.folia.api)
    compileOnly(libs.xali.menu.api)
    compileOnly(libs.xali.redeem.api)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.vaultunlocked)
    compileOnly(libs.itemsadder)
    compileOnly(libs.nexo)
    compileOnly(libs.headdatabase)
    // Hologram hosts and packet libraries, picked at run time (hologram.provider); a class of one is touched only after its plugin is found.
    compileOnly(libs.decentholograms)
    compileOnly(libs.cmi.api)
    compileOnly(libs.holographicdisplays.api)
    compileOnly(libs.packetevents)
    compileOnly(libs.protocollib)

    testImplementation(libs.xalicraft.util)
    testImplementation(libs.folia.api)
    testImplementation(libs.xali.menu.api)
    testImplementation(libs.xali.redeem.api)
    testImplementation(libs.bundles.database)
    testImplementation(libs.bundles.exposed)
    testImplementation(libs.packetevents)
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    jar { enabled = false }
    shadowJar {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        minimize {
            exclude(project(sibling("api").path))
        }
    }
    build {
        dependsOn(shadowJar)
    }
}
