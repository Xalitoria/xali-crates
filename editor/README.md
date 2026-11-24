# XaliCrates editor

**The XaliCrates editor** is a zero-build static web editor hosted at <https://crate.xali.to> for `crates/<id>.yml` and `animations/<id>.yml`. Its form fields, bounds, defaults, and inline help are generated directly from the Kotlin data classes via `:crates:editor:schema` (`web/schema.json`), and it shares the rendering, Nexo placeholder, asset, font, version/pack-format, and encrypted relay engine from [`editor-core`](../../editor-core/README.md).

## Features

- **Crates tab**: General/opening/cooldown/economy/multi-crate settings, crate & key items, live hologram preview, interactive reward probability distribution bar, per-reward inspector (including XaliRedeem modes and fallbacks), ambient/open effects, rarity tiers, lucky chest rules, and live Minecraft/Nexo item icons.
- **Animations tab**: Interactive 9×N Minecraft chest slot grid with click/drag symbol painting, symbol & custom filler palette, stage timeline, Shift+click winning slot toggles, and live play/pause animation preview.
- **Raw YAML tab**: Live synchronized YAML editor with validation, Copy, Import `.yml`, and Export `.yml`.
- **Multi-version & Nexo support**: Automatically uses the connected server's Minecraft version (`serverVersion`) or manual version selector (MC `1.8.9` through `26.3`) to resolve modern `1.21.4+` `items/*.json` definitions (including `1.21.6+` `oversized_in_gui` clipping) vs legacy `models/item/*.json` overrides, resource pack `overlays`, and Nexo/Oraxen/ItemsAdder glyphs & `<shift:N>` offsets.
- **Encrypted relay & direct HTTP**: Connect via `/xcrates web` (short 12-char `#<code>` AES-256-GCM relay link), `/xcrates share` (held in `/xcrates edits` for admin approval), direct HTTP (`editor.enabled: true`), or standalone local mode.

## Commands

```console
user $ ./gradlew :crates:editor:schema
user $ ./gradlew :crates:editor:test
user $ node --test crates/editor/web/test/
```
