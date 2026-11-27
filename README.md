# XaliCrates

**XaliCrates** is a Folia-safe Paper crate plugin with physical, virtual, placed, entity, and mining lucky-chest crates, seven built-in opening animations plus custom stage-based YAML animations, customizable player-facing menus via [XaliMenu](../menu/README.md), optional full-inventory overflow and ad-hoc reward storage via [XaliRedeem](../redeem/README.md), a non-editable in-game admin editor, a live [web editor](editor/README.md), and a full migrator from SpecializedCrates.

## Contents

- [Architecture](#architecture)
- [Dependencies](#dependencies)
- [Commands](#commands)
- [Permissions](#permissions)
- [Configuration](#configuration)
- [XaliMenu integration](#xalimenu-integration)
- [XaliRedeem integration](#xaliredeem-integration)
- [Holograms](#holograms)
- [Hiding players near a crate](#hiding-players-near-a-crate)
- [Migration from SpecializedCrates](#migration-from-specializedcrates)
- [Placeholders](#placeholders)
- [Tests](#tests)
- [See also](#see-also)

## Architecture

| Part | Group / Package | Role |
|---|---|---|
| `crates/api` | `net.xalitoria.crates` | Java/Bukkit-only public API (`XaliCratesApi`, `CrateInfo`, `RewardInfo`, `PlacedCrateInfo`, and Bukkit events). Enforced by `AddonSurface`. |
| `crates/paper` | `mc.xalitoria.crates.paper` | Paper/Folia plugin: database repositories, weighted roller, animations, holograms (through a hologram plugin, packets, or native `TextDisplay`), non-editable admin GUI, web editor server/relay, and SpecializedCrates migrator. |
| `crates/editor` | `com.xalitoria.crates.editor` | Schema generator (`:crates:editor:schema` -> `web/schema.json`) and zero-build static web editor sharing `editor-core`. |

## Dependencies

- **Required**: `XaliMenu` — renders all player-facing menus (virtual crates hub, multi-crates, reward previews, virtual keys, and opening history) from customizable `.conf` files. Admin in-game crate editor screens (`gui/editor/`) are hardcoded non-editable Bukkit inventories owned by XaliCrates.
- **Optional**: `XaliRedeem` (stores overflow items and `redeem.mode: ALWAYS` dynamic rewards as pending rewards without static voucher configs), `Vault` / `VaultUnlocked`, `PlaceholderAPI`, `Nexo`, `ItemsAdder`, `HeadDatabase`. For [holograms](#holograms): `DecentHolograms`, `CMI`, `HolographicDisplays`, `packetevents`, `ProtocolLib`.

## Commands

Main command: `/xalicrates` (also `/crates`, `/xcrates`, `/crate`, `/xcrate`).
Standalone commands: `/xkeys` (also `/keys`, `/cratekeys`, `/xcratekeys`, `/cratekey`, `/xcratekey`), `/xrewards` (also `/rewards`).

| Command | Permission | What it does |
|---|---|---|
| `/xalicrates` | `xalicrates.use` | Opens the virtual crates XaliMenu hub. |
| `/xalicrates preview <crate>` / `/xrewards <crate>` | `xalicrates.preview` | Opens the crate's XaliMenu reward preview. |
| `/xalicrates keys [list\|send <player> <crate> <amount>]` / `/xkeys` | `xalicrates.keys` | Opens virtual keys menu, lists balances, or sends virtual keys (`xalicrates.keys.send`). |
| `/xalicrates claim [crate] [amount]` | `xalicrates.claim` | Withdraws virtual keys into physical key items. |
| `/xalicrates deposit <crate> [amount]` | `xalicrates.keys` | Deposits physical keys into virtual storage. |
| `/xalicrates history [limit]` | `xalicrates.history` | Views recent crate openings. |
| `/xalicrates luckychest` | `xalicrates.luckychest.toggle` | Toggles mining-spawned lucky chests for the player. |
| `/xalicrates givekey\|takekey\|givecrate <player\|all> <crate> [amount] [--virtual]` | `xalicrates.admin.give` / `.take` | Gives or removes physical or virtual keys/crates. |
| `/xalicrates open\|forceopen <crate> [player\|all]` | `xalicrates.admin` | Opens (or force-opens without keys/cooldown) a crate. |
| `/xalicrates setcrate <crate>` / `spawncrate <crate> <world> <x> <y> <z>` / `deletecrate` / `delall <crate>` | `xalicrates.admin.edit` | Manages placed crates in worlds. |
| `/xalicrates editor [crate]` | `xalicrates.admin.edit` | Opens the hardcoded, non-editable in-game admin crate editor. |
| `/xalicrates web [crate] [--auto-reload]` / `web stop` | `xalicrates.admin.edit` | Opens an encrypted live web editor session. |
| `/xalicrates share [readonly\|stop] [crate]` / `edits [apply\|drop <id>]` | `xalicrates.admin.edit` | Shares a web editor link whose saves are held for admin approval. |
| `/xalicrates migrate specializedcrates [path] [--dry-run] [--overwrite] [--skip-data]` | `xalicrates.admin.migrate` | Migrates SpecializedCrates configs, animations, custom menus, placed crates, and player data. |
| `/xalicrates list\|info\|errors\|validate\|toggleparticles\|reload\|debug` | `xalicrates.admin` | Inspection, validation, and reload utilities. |

## Permissions

| Node | Default | Meaning |
|---|---|---|
| `xalicrates.use` | `true` | Base `/xalicrates` command. |
| `xalicrates.keys` | `true` | View and deposit virtual keys. |
| `xalicrates.keys.send` | `true` | Send virtual keys to other players. |
| `xalicrates.claim` | `true` | Withdraw virtual keys as physical items. |
| `xalicrates.preview` | `true` | Preview crate rewards. |
| `xalicrates.history` | `true` | View personal opening history. |
| `xalicrates.luckychest` | `true` | Eligible to spawn lucky chests while mining. |
| `xalicrates.luckychest.toggle` | `true` | Toggle personal lucky chest spawns. |
| `xalicrates.place.bypass` | `op` | Bypass block-placement restrictions for static crates. |
| `xalicrates.admin` | `op` | General admin commands (`open`, `list`, `info`, `errors`, `reload`, `debug`). |
| `xalicrates.admin.give` / `.take` | `op` | Give or take keys and crates. |
| `xalicrates.admin.edit` | `op` | In-game editor, web editor, and placed crate commands. |
| `xalicrates.admin.migrate` | `op` | Run migration commands. |

## Configuration

- `plugins/XaliCrates/config.yml` — global settings, written with its defaults on the first start (`database`, `keys`, `crates`, `lucky-chest`, `virtual`, `menus`, `redeem`, `holograms`, `particles`, `history`, `animations`, `editor`).
- `plugins/XaliCrates/language/<lang>.yml` — all player messages.
- `plugins/XaliCrates/crates/<id>.yml` — one typed YAML file per crate or multi-crate.
- `plugins/XaliCrates/animations/<id>.yml` — custom stage-based inventory animations (9-column grids per stage).

## XaliMenu integration

On first start (when `menus.write-defaults: true`), XaliCrates writes four starter menus into `plugins/XaliMenu/menus/`: `crates.conf`, `crates-preview.conf`, `crates-keys.conf`, and `crates-history.conf`.

Registered XaliMenu extensions:
- **Catalogs**: `xalicrates:crates`, `xalicrates:rewards`, `xalicrates:keys`, `xalicrates:history`.
- **Actions**: `xalicrates:open`, `xalicrates:preview`, `xalicrates:claim-key`, `xalicrates:deposit-key`, `xalicrates:toggle-lucky`.
- **Conditions**: `xalicrates:has-key`, `xalicrates:can-open`, `xalicrates:on-cooldown`.
- **ItemSource**: `xalicrates:crate/<id>`, `xalicrates:key/<id>`, `xalicrates:reward/<crate>/<reward>`.

## XaliRedeem integration

When `XaliRedeem` is installed and `redeem.enabled: true`:
- Overflow items (when `full-inventory: REDEEM`) are stored via `XaliRedeemApi.giveReward` as dynamic pending rewards that do not need static voucher configs in `XaliRedeem`.
- Individual rewards can set `redeem.mode: ALWAYS` to always deliver into `/redeem`, or `redeem.voucher: <id>` to grant a configured XaliRedeem voucher.

## Holograms

A crate placed in a world can show a hologram (`hologram.lines`, an `animation` that cycles color prefixes, and a `reward-line` shown for `reward-duration` after a win). Where it is drawn is `holograms.provider` in `config.yml`:

| Value | Draws the hologram with | Notes |
|---|---|---|
| `AUTO` | The first installed of the rows below, in this order, and `NATIVE` when none is | The default. |
| `DECENTHOLOGRAMS` | DecentHolograms | One armor-stand line per hologram line. Not saved to DecentHolograms' files. |
| `CMI` | CMI | CMI saves the holograms it holds, so each one is removed when XaliCrates stops, and leftovers (`xalicrates_*`) are removed at start. |
| `HOLOGRAPHICDISPLAYS` | HolographicDisplays (API 3) | For servers that are on a version the newer hosts do not support. |
| `PACKETEVENTS` | PacketEvents | Preferred of the two packet libraries. A fake `TextDisplay` that exists only in packets, sent to the players near it. |
| `PROTOCOLLIB` | ProtocolLib | The same packets, through ProtocolLib. |
| `NATIVE` | Paper's `TextDisplay` entity | Needs no plugin. Exists only while its chunk is loaded, and is made again when the chunk loads. |

A named provider that is not installed (or fails to start) falls back to `AUTO` with a warning. `holograms.view-range` (blocks) and `holograms.refresh-ticks` are for `PACKETEVENTS`, `PROTOCOLLIB` and `NATIVE`; a hologram plugin uses its own range. `holograms.offset` and `hologram.offset` lift a hologram in every provider; a hologram plugin stacks its lines a little differently from a text display, so raise or lower it there if it sits too high or low.

**Colors** are only the ones XaliCrates reads itself: `&` codes, `&#rrggbb` and MiniMessage tags, each line on its own (a color never runs into the next line). A hologram plugin is given the line in the legacy `§` form with `§x§r§r§g§g§b§b` hex, which they all pass through untouched. Parsing every color format a server might have is XaliChat's job (`protocolor`), not this plugin's.

A packet hologram is shown to a player within `view-range` and hidden four blocks past it (so the edge does not blink). It is sent again after a world change and a respawn, and a restart or a crash leaves nothing in a world to clean up.

## Hiding players near a crate

A crate file can hide the players who crowd a placed crate, so nobody stands in front of another player's view of it:

```yaml
display:
  hide-radius: 4.0
```

Players who stand within `hide-radius` blocks (straight line from the middle of the crate block, same world) of the same placed crate cannot see each other until one of them steps out. The default is `0`, and a radius of `0` or less turns it off. It is checked every 5 ticks on each player's own thread, so it works on Folia. Only players this plugin hid are shown again: the radius being lowered or set to `0`, the crate being removed, disabled or reloaded away, a player leaving, and the plugin stopping all show them. A vanish plugin's own hiding is not touched.

## Migration from SpecializedCrates

```console
/xalicrates migrate specializedcrates --dry-run
/xalicrates migrate specializedcrates --overwrite
```

Converts `Crates/*.crate`, `Crates/*.multicrate`, legacy `Rewards.yml`, `animations/*.yml`, `ActiveCrates.db`, `PlayerData.db`, `PlayerData/*.db`, and MySQL `scPlayerStats` (`SQL.yml`), and generates XaliMenu `.conf` menus for custom preview inventories and multi-crates.

## Placeholders

Requires PlaceholderAPI (`%xcrates_<key>%`):
`keys_<crate>`, `physical_keys_<crate>`, `total_keys_<crate>`, `total_virtual_keys`, `crates_<crate>`, `cooldown_<crate>`, `crate_name_<crate>`, `reward_name_<crate>_<reward>`, `reward_chance_<crate>_<reward>`, `last_player_<crate>`, `last_reward_<crate>`, `lucky_enabled`.

## Tests

| Test | What it checks |
|---|---|
| `crates/api/test/AddonSurface.kt` | Public API has no Kotlin types or interface `$DefaultImpls`. |
| `crates/paper/test/RewardRollerTest.kt` | Probabilities, weighted roll, repeat limits, fallback cycle guard, `%amountX-Y%`. |
| `crates/paper/test/ValidationAndRegistryTest.kt` | Starter crate/animation round-trip and validation rules. |
| `crates/paper/test/ProximityRulesTest.kt` | Hide radius: `0`, negative and non-finite radii are off, the distance is straight-line and inclusive, other worlds are not near, and two players hide only when they share a crate. |
| `crates/paper/test/ConfigFilesTest.kt` | `config.yml` and `language/en-us.yml` are written on the first start and an edited language file is read back. |
| `crates/paper/test/CratesDatabaseTest.kt` | Guarded virtual key/crate updates, concurrent consumption, transfers, cooldowns, history pruning. |
| `crates/paper/test/SpecializedCratesMigratorTest.kt` | Full migration of crates, multi-crates, animations, menus, and flatfile data. |
| `crates/paper/test/HologramTest.kt` | Provider choice (`AUTO` order, PacketEvents over ProtocolLib, fallback), tick period, host-safe names, the plugin's own colors per line, text-display metadata, and the packet hologram's viewers (range, edge margin, offline and forgotten players, text updates, removal). |
| `crates/paper/test/EditorSecurityTest.kt` | Web editor IP checks, save authorization, privileged-command scan, byte ranges, skin addresses, and session limits. |
| `crates/paper/test/RelayCryptoTest.kt` | PBKDF2 derivation and AES-256-GCM relay frame sealing/opening. |
| `crates/editor/test/SchemaTest.kt` | Ensures `crates/editor/web/schema.json` matches the Kotlin model. |
| `crates/editor/web/test/` | Node.js tests for YAML round-trip, validation, store undo/redo, relay crypto, and schema. |

## See also

- [XaliCrates web editor](editor/README.md)
- [Shared editor-core](../editor-core/README.md)
- [XaliMenu](../menu/README.md)
- [XaliRedeem](../redeem/README.md)
