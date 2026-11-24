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
    version = "0.1.0"
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
        // XaliMenu is required for custom crate preview, virtual crate, key and history menus.
        register("XaliMenu") { load = RelativeLoad.BEFORE }
        register("PlaceholderAPI") { load = RelativeLoad.BEFORE; required = false }
        // Optional full-inventory overflow and ad-hoc reward storage through XaliRedeem.
        register("XaliRedeem") { load = RelativeLoad.BEFORE; required = false }
        register("Vault") { load = RelativeLoad.BEFORE; required = false }
        register("VaultUnlocked") { load = RelativeLoad.BEFORE; required = false }
        register("ItemsAdder") { load = RelativeLoad.BEFORE; required = false }
        register("Nexo") { load = RelativeLoad.BEFORE; required = false }
        register("HeadDatabase") { load = RelativeLoad.BEFORE; required = false }
    }
}
