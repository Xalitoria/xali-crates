import mc.xalitoria.gradle.Load
import mc.xalitoria.gradle.RelativeLoad

plugins {
    kotlin("jvm")
    id("mc.xalitoria.conventions")
    id("mc.xalitoria.minecraft")
    alias(libs.plugins.shadow) apply false
}

subprojects {
    // Named for the package of what the module holds, see the Packages section of the root README.
    group = when (name) {
        "api" -> "net.xalitoria.crates"
        "paper" -> "mc.xalitoria.crates.paper"
        "editor" -> "com.xalitoria.crates"
        else -> error("No group for $path")
    }
    version = "0.2.0"
}

tasks.named("build") {
    dependsOn(subprojects.map { "${it.path}:build" })
}

paper {
    name = "XaliCrates"
    main = "mc.xalitoria.crates.paper.XaliCrates"
    prefix = "XaliCrates"
    author = "audizian"
    website = "https://xalitoria.com"
    description = "Customizable crates, animations, virtual keys, lucky chests, custom XaliMenu menus, and a web editor."
    apiVersion = "26.2"
    load = Load.POSTWORLD
    foliaSupported = true
    relocateSources = true
    serverDependencies {
        // Hard dependency: XaliMenu renders all player-facing crate, preview, key, and history menus.
        register("XaliMenu") { load = RelativeLoad.BEFORE; required = true }
        register("PlaceholderAPI") { load = RelativeLoad.BEFORE; required = false }
        // Optional full-inventory overflow and ad-hoc reward storage through XaliRedeem.
        register("XaliRedeem") { load = RelativeLoad.BEFORE; required = false }
        register("Vault") { load = RelativeLoad.BEFORE; required = false }
        register("VaultUnlocked") { load = RelativeLoad.BEFORE; required = false }
        register("ItemsAdder") { load = RelativeLoad.BEFORE; required = false }
        register("Nexo") { load = RelativeLoad.BEFORE; required = false }
        register("HeadDatabase") { load = RelativeLoad.BEFORE; required = false }
        // Crate holograms go to the first of these that is installed (config.yml `holograms.provider`).
        register("DecentHolograms") { load = RelativeLoad.BEFORE; required = false }
        register("CMI") { load = RelativeLoad.BEFORE; required = false }
        register("HolographicDisplays") { load = RelativeLoad.BEFORE; required = false }
        register("packetevents") { load = RelativeLoad.BEFORE; required = false }
        register("ProtocolLib") { load = RelativeLoad.BEFORE; required = false }
    }
}
