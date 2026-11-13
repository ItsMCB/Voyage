# 🌐 Voyage

![License](https://img.shields.io/github/license/itsmcb/voyage?color=7c3aed)
![Version](https://img.shields.io/github/v/release/itsmcb/voyage?color=7c3aed&label=version)
![Paper](https://img.shields.io/badge/Paper-26.2-7c3aed?logo=papermc)
[![Discord](https://img.shields.io/badge/Discord-Libre_Galaxy-7c3aed?logo=discord&logoColor=f5f5f5)](https://discord.gg/86qJJHtDgT)

Modern Minecraft world manager built for Paper 26.2 with SQLite-backed world & profile persistence, idle world eviction, portal routing, singleplayer-compatible zip exports, and legacy dimension imports.

---

## 📑 Table of Contents

- [Requirements & Installation](#-requirements--installation)
- [Quick Start](#-quick-start)
- [Commands](#-commands)
  - [`/world`](#world)
  - [`/voyage`](#voyage)
- [Player Data Linking](#-player-data-linking)
- [Portal Linking](#-portal-linking)
- [World Import & Migration](#-world-import--migration)
- [Configuration](#-configuration)
- [Developer API & Events](#-developer-api--events)
- [Architecture & Internals](#-architecture--internals)

---

## 🚀 Requirements & Installation

### Requirements
- **Paper 26.2**
- **Java 25+**
- **[VexelCore](https://github.com/itsmcb/vexelcore)** (plugin dependency)

### Installation
1. Install **VexelCore** into your server's `plugins/` directory.
2. Download the latest `Voyage.jar` and place it into `plugins/`.
3. Start the server. Voyage automatically initializes `plugins/Voyage/` with:
   - `config.yml` — Plugin configuration
   - `voyage.db` — Embedded SQLite database (WAL mode)
   - `import/` — Drop folder for world directories and `.zip` archives to import
   - `exports/` — Output directory for `/world export` archives
   - `staging/` — Temporary extraction directory used during `.zip` imports (auto-cleaned)
   - `backups/voyage/` — Rolling database snapshots (created on backup)

---

## ⚡ Quick Start

```bash
# Create a void dimension with structures disabled and a custom spawn
/world create custom:lobby --preset void --spawn 0 65 0

# Create a normal dimension with a specific seed and gamerule
/world create custom:survival --environment NORMAL --seed 12345 --no-structures --gamerule keep_inventory true

# Teleport to the world (automatically loads world if offline; restores last log-off position)
/world tp custom:survival

# Route Nether portals from custom:survival to custom:nether with 8:1 vanilla coordinate scaling
/world portal-link create custom:survival NETHER custom:nether VANILLA_SCALING VANILLA_NETHER 8.0

# Group worlds to share player inventories, stats, and ender chests
/world profile-link create custom:survival survival_group --strategy DESTINATION_PRIORITY
```

---

## 📖 Commands

### `/world`

**Base Permission:** `voyage.world`  
Subcommand permissions default to `voyage.<subcommand>` (and `voyage.admin` grants all `voyage.*` permissions).

#### Lifecycle & Management

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/world create <namespace:key> [flags]` | Generates and mounts a new dimension, then teleports creator to spawn. | `voyage.create` |
| `/world load <world>` | Mounts an offline tracked dimension. | `voyage.load` |
| `/world unload <world>` | Safely saves and unmounts a loaded dimension after evacuating players. | `voyage.unload` |
| `/world clone <source> <target> [--overwrite]` | Duplicates a dimension structure and its player data. *(Alias: `/world duplicate`)* | `voyage.clone` |
| `/world delete <world> [--confirm]` | Evacuates players, unloads, and permanently deletes a managed world from disk. | `voyage.delete` |
| `/world manage <track\|untrack> <world>` | Starts tracking an existing directory or stops tracking a managed world. | `voyage.manage` |
| `/world list` | Displays managed, unmanaged, loaded, and offline worlds with player counts. | `voyage.list` |
| `/world info [world] [--gamerule [rule]]` | Inspects dimension specs, disk footprint, data profile, and GameRules. | `voyage.info` |

##### `/world create` Flags
- `--preset <name>` (or `-p`) — Applies a preset template from `config.yml` (built-in: `void`, `flat`, `amplified`).
- `--environment <NORMAL|NETHER|THE_END|CUSTOM>` (or `-e`) — Sets dimension environment.
- `--seed <seed>` (or `-s`) — Numeric long or string (hashed via string `hashCode()` to match vanilla).
- `--generator <gen>` (or `-gen`) — Custom chunk generator plugin identifier (e.g., `OldGenerator:b173`).
- `--generator-settings <settings>` — Generator settings JSON or flat string.
- `--difficulty <PEACEFUL|EASY|NORMAL|HARD>` (or `-d`) — Sets dimension difficulty.
- `--no-structures` — Disables structure generation.
- `--spawn <x> <y> <z>` — Sets a fixed spawn point override.
- `--gamerule <name> <value>` (or `-g <name> <value>`, repeatable) — Sets initial GameRules.

> [!NOTE]
> Void worlds (`--preset void` or `--generator void`) automatically place a bedrock safety block under the world spawn if the block below spawn is air, preventing players from falling into the void on arrival.

> [!WARNING]
> The server's primary level (`minecraft:overworld`) cannot be unloaded, deleted, exported, or cloned while the server is running.

#### Teleportation & Player Management

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/world teleport <world> [player] [strategy]` | Teleports player into a dimension. Loads world first if offline. *(Alias: `/world tp`)* | `voyage.teleport` |
| `/world evacuate <world> [targetWorld]` | Forcefully teleports all players out of a dimension to a fallback world. | `voyage.evacuate` |
| `/world border <world> <size\|reset>` | Sets border diameter in blocks (min `1.0`), or `reset` for max (`59,999,968`). | `voyage.border` |

##### `/world teleport` Strategies
- **Default (`LOG_OFF_LOCATION` / unspecified):** Teleports the target to their last log-off position in that world's profile group. Falls back to world spawn if no previous position exists.
- **`SPAWN_POINT` (or `SPAWN`):** Forces teleportation to the destination world's spawn location.

#### Dimension Interconnection

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/world portal-link create <world> <type> <target> <strategy> <generator> [extra]` | Creates dimension-wide portal routing. | `voyage.portallink` |
| `/world portal-link break <world> <type>` | Removes dimension portal routing for the specified portal type. | `voyage.portallink` |
| `/world profile-link create <world> [profile] [strategy] [--dry-run]` | Links a world to a profile group, an existing world's group, or isolates it (`none`). | `voyage.profilelink` |
| `/world profile-link break <world> <true\|false> [--dry-run]` | Isolates a world from its group. Set `true` to copy existing data, `false` to start fresh. | `voyage.profilelink` |
| `/world export <world> [--with-data\|--no-data]` | Compresses the world into `plugins/Voyage/exports/<namespace>_<key>.zip` with optional player data. | `voyage.export` |
| `/world import <folder\|list\|--all> [flags]` | Lists or migrates folders and `.zip` archives from `plugins/Voyage/import/` into Paper 26.2 format. | `voyage.admin` |

##### `/world import` Flags
- `--namespace <ns>` — Destination namespace for imported dimensions *(default: `custom`)*.
- `--key <key>` (or `--name <key>`) — Explicit destination world key *(single folder/archive import only)*.
- `--environment <NORMAL|NETHER|THE_END>` — Overrides dimension environment.
- `--generator <gen>` (or `-g <gen>`) — Custom generator plugin name or identifier.
- `--generator-settings <settings>` — Superflat JSON or custom generator settings string.
- `--do-not-auto-link` — Skips automatic portal linking between imported dimensions.
- `--link-data <profile> [strategy]` — Automatically links imported dimensions to a player data profile.
- `--delete-after-import` — Deletes the source folder or `.zip` from `plugins/Voyage/import/` on success *(otherwise renamed with `.imported`)*.

---

### `/voyage`

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/voyage reload` | Reloads `config.yml`, SQLite world definitions, and player data mappings. | `voyage.admin` |
| `/voyage backup [label]` | Creates an atomic hot snapshot of `voyage.db` in `plugins/Voyage/backups/voyage/`. | `voyage.admin` |
| `/voyage db-reset [--confirm]` | Wipes `voyage.db`, recreates all SQLite schemas, and reloads world state. | `voyage.admin` |

---

## 🔗 Player Data Linking

Voyage manages player state per profile inside `plugins/Voyage/voyage.db`:

- **Isolated Profile (Default):** `isolated:<namespace>_<key>` — Player data belongs strictly to that single dimension.
- **Grouped Profile:** `group:<name>` — Configured in `config.yml` under `world-groups` or via `/world profile-link`. Worlds in the same group share:
  - Inventory and Armor
  - Ender Chest
  - Health and Maximum Health (`Attribute.MAX_HEALTH`)
  - Food Level and Saturation
  - Experience (Level, Progress, and Total XP)
  - Active Potion Effects (duration, amplifier, ambient, particles, icon, infinite)
  - GameMode
  - Respawn Anchor / Bed Location *(preserved in memory even if the respawn world is temporarily evicted)*
  - Environmental Ticks (fire, freeze, fall distance, remaining air, maximum air)
  - Last Log-Off Location (tracked per world within the profile)

### Profile Merge Strategies

When linking worlds with existing player profiles:
- **`DESTINATION_PRIORITY` (Default):** Preserves data in the target group. Conflicting player records in the source profile are discarded.
- **`SOURCE_PRIORITY`:** Overwrites data in the target group with records from the linking world.

> [!TIP]
> Use `--dry-run` with `/world profile-link create` or `/world profile-link break` to preview conflict, transfer, and unaffected player counts before modifying database state.

### Anti-Duplication & Login Safeguards

- During cross-profile transitions and logins, players enter an immutable transition state tracked with monotonic generation IDs to prevent rapid world-change races (`A → B → C`) from overwriting intermediate profiles.
- Inventory clicks, drags, item drops, hand swaps, block placement, interactions, entity pickups, and death item/XP drops are canceled until the new profile is fully loaded and applied.
- On initial server login when no Voyage database record exists yet for a player (`state == null`), Voyage preserves their existing inventory and location while snapshotting their state for future transitions.
- Runtime `/world profile-link create` and `break` hot-swap online players in that world and keep their active profile tracking synchronized.

---

## 🌀 Portal Linking

Dimension links route portal events across worlds without hardcoding coordinate logic into vanilla portal code.

### Portal Types
- `NETHER` — Intercepts both Paper's `EntityPortalReadyEvent` and `PlayerPortalEvent` (`NETHER_PORTAL` cause).
- `END` — Triggers on End portal frames (`END_PORTAL` teleport cause).
- `CUSTOM` — Custom portal integrations.

### Coordinate Strategies
- `TARGET_SPAWN_POINT` — Places the player at the destination world's spawn location.
- `VANILLA_SCALING` — Scales X and Z coordinates by a factor provided in `[extra]` (e.g., `8.0` for Overworld → Nether, `0.125` for Nether → Overworld; default: `1.0`).

### Portal Generators
- `NONE` — Teleports the player directly to calculated coordinates without generating blocks.
- `VANILLA_NETHER` — Delegates portal frame searching/generation to Paper's native Nether portal engine when the destination is already loaded; when the destination must be loaded asynchronously first, `VanillaNetherGenerator` searches a 16-block radius for an existing portal or builds a safe 4×5 obsidian Nether portal frame (applied only for `NETHER` portal events).
- `STRUCTURE` — Generates a specified structure at destination coordinates. Defaults to `minecraft:nether_portal`, or a custom structure key passed via `[extra]`.

> [!NOTE]
> Interception applies an 80-tick portal cooldown to prevent portal re-trigger loops. If the destination world is unloaded, Voyage cancels immediate traversal, begins an asynchronous load, and teleports the player once the world mounts.

---

## 📦 World Import & Migration

Place world directories or `.zip` archives inside `plugins/Voyage/import/` and execute `/world import <folder|list|--all> [flags]`.

### Supported Formats
- **Paper 26.x Native** (identified via `data/paper/metadata.dat`)
- **Vanilla 26.x** (identified via `data/minecraft/chunk_tickets.dat`)
- **Paper 1.21.11** (identified via `paper-world.yml` or `uid.dat`)
- **Vanilla 1.21.x** (identified via `level.dat`)
- **Voyage Exports (`voyage.json`)** — Automatically restores difficulty, spawn coordinates & angles, GameRules, metadata, portal links, and embedded player profile states.

### Import Features
- **Archive Staging & Safety:** `.zip` files extract off the main thread into `plugins/Voyage/staging/<name>-<timestamp>/` with canonical ZipSlip protection and zip-bomb caps (`65,536` entries / `10 GiB` uncompressed).
- **Dimension Separation:** Multi-dimension folders containing `DIM-1`, `DIM1`, or modern `dimensions/` paths are split into separate linked dimensions (`<key>`, `<key>_nether`, `<key>_end`).
- **Portal Routing:** Automatically creates reciprocal scaled Nether links (`8:1` and `1:8`) and End links unless `--do-not-auto-link` is passed.
- **Metadata Preservation:** Imports seeds, daytime, difficulty, world borders (`world_border.dat` or `level.dat`), and GameRules (normalizing legacy names, such as `doDaylightCycle` → `advance_time` and `doMobSpawning` → `spawn_mobs`).
- **Contract Enforcement:** Re-stamps level files using the server's current `DataVersion` through `LevelDatNbt.writeVanillaSafe()` to prevent version mismatch errors.
- **Atomic Rollback:** If an import fails midway, created worlds and staged filesystem directories are automatically unmounted and deleted.

---

## ⚙️ Configuration

### `config.yml`

```yaml
# ====================
# Voyage Configuration
# ====================

# The designated fallback destination when players are evacuated from an unmounted dimension.
default-world: "minecraft:overworld"

# Database Configuration (Embedded SQLite in WAL mode)
database:
  backup:
    # Whether automated rolling hot backups are taken
    enabled: true
    # Frequency in minutes to create an atomic snapshot
    interval-minutes: 60
    # Number of recent snapshots to retain before pruning older files
    max-retained-dumps: 3
    # Destination folder inside plugins/Voyage/
    folder: "backups/voyage"

# World Export Archive Settings
export:
  # Maximum number of recent .zip exports to retain in plugins/Voyage/exports/ before pruning
  max-retained: 5
  # Maximum age in days before an export is automatically pruned (0 to disable)
  retention-days: 7

# Memory Optimization: Idle World Eviction
# Automatically unmounts worlds when empty to keep RAM footprint low at 1,000+ planets
eviction:
  enabled: true
  # How many minutes a dimension must be empty before being unmounted
  idle-timeout-minutes: 5

# Worlds under the same group share player inventories, health, ender chest, etc.
# Note: Worlds not listed here default to their own isolated data profile.
world-groups:
  default_group:
    - "minecraft:overworld"
    - "minecraft:the_nether"
    - "minecraft:the_end"

# Profiles Configuration
profiles:
  # If true, players joining a new profile group for the first time will spawn with a fresh inventory.
  start-fresh-on-first-join: true

# World Presets (Templates)
# Used via /world create <key> --preset <name>
presets:
  void:
    environment: NORMAL
    worldType: FLAT
    generatorSettings: '{"layers":[{"block":"minecraft:air","height":1}],"biome":"minecraft:the_void","features":false,"lakes":false}'
    generateStructures: false
  flat:
    environment: NORMAL
    worldType: FLAT
    generatorSettings: '{"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","features":false,"lakes":false}'
    generateStructures: false
  amplified:
    environment: NORMAL
    worldType: AMPLIFIED

# Webhook Integration
webhook:
  url: ""
  events:
    - WORLD_CREATE
    - WORLD_DELETE
    - WORLD_CLONE
    - WORLD_IMPORT
    - WORLD_EXPORT
    - PROFILE_LINK
    - PORTAL_LINK
```

### SQLite Storage (`voyage.db`)

Voyage persists world definitions and player profiles in `plugins/Voyage/voyage.db` via HikariCP in SQLite WAL mode:
- `voyage_worlds` — Core dimension settings (environment, seed, world type, generator, settings, auto-load, difficulty, structures, spawn coordinates & angles)
- `voyage_world_gamerules` — Per-world GameRule overrides
- `voyage_world_metadata` — Arbitrary key-value plugin metadata tags
- `voyage_portal_links` — Dimension-level portal links, coordinate strategies, and generators
- `voyage_player_profiles` — JSON-serialized player state indexed by `(profile_id, player_uuid)`

---

## 📡 Developer API & Events

### Obtaining the API

`VoyageAPI` is registered as a Bukkit service:

```java
RegisteredServiceProvider<VoyageAPI> provider = Bukkit.getServicesManager().getRegistration(VoyageAPI.class);
if (provider != null) {
    VoyageAPI voyage = provider.getProvider();
}
```

### Key API Methods

```java
// Create worlds via builder
CompletableFuture<ManagedWorld> future = voyage.builder(NamespacedKey.fromString("custom:arena"))
    .environment(World.Environment.NORMAL)
    .seed(987654L)
    .generateStructures(false)
    .autoLoad(true)
    .create();

// Manage world state
Optional<ManagedWorld> world = voyage.getManagedWorld(NamespacedKey.fromString("custom:arena"));
world.ifPresent(mw -> {
    mw.setDifficulty(Difficulty.HARD);
    mw.setSpawn(0, 65, 0, 90.0f, 0.0f);
    mw.setMetadata("note", "Neat builds");
});

// Query entry location (restores log-off position if present, falls back to safe spawn)
Location entry = voyage.getPlayerEntryLocation(bukkitWorld, player.getUniqueId(), true);

// Export world to singleplayer-ready zip (with optional root folder & player data toggles)
voyage.exportWorld(worldKey, targetZipPath, player.getUniqueId(), "Epic World", true, true);
```

### Event API

| Event | Cancellable | Description |
| :--- | :---: | :--- |
| `VoyageWorldPreLoadEvent` | ✅ | Called before a managed dimension loads. Cancel to prevent loading. |
| `VoyageWorldLoadEvent` | ❌ | Called after a managed dimension has successfully loaded. |
| `VoyageWorldUnloadEvent` | ❌ | Called when a managed dimension unloads. |
| `VoyageWorldDeleteEvent` | ✅ | Called before permanent disk deletion. Cancel to abort. |
| `VoyageWorldDeletedEvent` | ❌ | Called after a managed world has been deleted from disk. Purges records in dependent plugins. |
| `VoyageWorldCloneEvent` | ❌ | Called after world and data cloning completes. |
| `VoyageWorldPreLinkEvent` | ✅ | Called before profile data migration begins. Cancel to abort. |

---

## 🧩 Architecture & Internals

- **Dimension Storage:** Dimensions are resolved under `<level_directory>/dimensions/<namespace>/<key>`. The primary server level (`minecraft:overworld`) is resolved at `<level_directory>`.
- **Ordered Startup Auto-Loading:** Voyage runs at `STARTUP` so custom chunk generators (e.g., `Voyage:void`) are available for server levels, while `autoLoad` managed worlds defer mounting to the first server tick so `minecraft:overworld` and `POSTWORLD` generator plugins initialize cleanly first.
- **SQLite WAL & Serialized Writers:** World configuration and player profile persistence run through dedicated single-threaded executors (`Voyage-WorldConfig-Writer` and `Voyage-Profile-Writer`) backed by HikariCP and SQLite in WAL mode, with rolling atomic backups via `VACUUM INTO`.
- **In-Memory Write Caching:** Pending asynchronous world and player writes remain readable immediately through concurrent in-memory caches (`pendingSaves` / `pendingWrites`) and `cachedLogOffLocations`, preserving read-your-own-writes consistency across rapid world switches.
- **Zero-I/O Tab Completion Cache:** `WorldDirectoryWatcher` populates an in-memory set of unmanaged disk worlds asynchronously (`scanDiskAsync()`), keeping Brigadier command suggestions completely free of disk I/O.
- **Idle World Eviction:** `WorldEvictionController` monitors empty managed worlds every 30 seconds and automatically unloads dimensions idle past `eviction.idle-timeout-minutes` (skipping the main level, temporary instances, and worlds undergoing export/clone/delete).
- **Orphan & Staging Cleanup:** At startup, `WorldDiskScanner` asynchronously purges orphaned `.voyage-temp` / `.voyage-deleted` world folders under `dimensions/` as well as leftover extraction folders in `plugins/Voyage/staging/`.
- **Graceful Evacuation & Shutdown Flush:** World unloads evacuate players to the fallback world (kicking only if teleport fails) before unmounting chunks. On shutdown, Voyage synchronously saves online players (skipping active profile transitions), flushes pending SQLite queues, and executes `PRAGMA wal_checkpoint(TRUNCATE)` before closing the connection pool.
- **Vanilla & Paper 26.x Exports:** Zip exports package dimension payloads under `dimensions/minecraft/overworld/`, sanitize `level.dat` (`Data.Version`, `DataPacks`, `allowCommands: 1`, world border, spawn), synthesize `data/minecraft/world_gen_settings.dat` and `world_border.dat`, write player state to both `players/data/<uuid>.dat` and `playerdata/<uuid>.dat` (plus `Data.Player`), and embed `voyage.json` for lossless re-import.
