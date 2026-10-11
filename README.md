# 🌐 Voyage

![License](https://img.shields.io/github/license/itsmcb/voyage?color=7c3aed)
![Version](https://img.shields.io/github/v/release/itsmcb/voyage?color=7c3aed&label=version)
![Paper](https://img.shields.io/badge/Paper-26.2-7c3aed?logo=papermc)
[![Discord](https://img.shields.io/badge/Discord-Libre_Galaxy-7c3aed?logo=discord&logoColor=f5f5f5)](https://discord.gg/86qJJHtDgT)

Modern Minecraft world manager built for Paper 26.2 with cross-world player data linking, portal routing, zip exports, and legacy dimension imports.

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
3. Start the server. Configuration files will generate in `plugins/Voyage/`.

---

## ⚡ Quick Start

```bash
# Create a dimension with structures disabled and a custom spawn
/world create custom:lobby --environment NORMAL --seed 12345 --no-structures --spawn 0 65 0

# Teleport to the world (automatically loads world if offline; restores last log-off position)
/world tp custom:lobby

# Route Nether portals from custom:lobby to custom:nether with 8:1 vanilla coordinate scaling
/world portal-link create custom:lobby NETHER custom:nether VANILLA_SCALING VANILLA_NETHER 8.0

# Group worlds to share player inventories, stats, and ender chests
/world profile-link create custom:lobby survival_group DESTINATION_PRIORITY
```

---

## 📖 Commands

### `/world`

**Base Permission:** `voyage.world`  
Subcommand permissions default to `voyage.<subcommand>`.

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
- `--environment <NORMAL|NETHER|THE_END|CUSTOM>` (or `-e`) — Sets dimension environment.
- `--seed <seed>` (or `-s`) — Numeric long or string (hashed via string `hashCode()` to match vanilla).
- `--difficulty <PEACEFUL|EASY|NORMAL|HARD>` (or `-d`) — Sets dimension difficulty.
- `--no-structures` — Disables structure generation.
- `--spawn <x> <y> <z>` — Sets a fixed spawn point override.
- `--gamerule <name> <value>` (or `-g <name> <value>`) — Sets initial GameRules.

> [!WARNING]
> The server's primary level (`minecraft:overworld`) cannot be unloaded, deleted, or cloned while the server is running.

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
| `/world export <world>` | Compresses the world directory into `exports/<namespace>_<key>.zip` with player state. | `voyage.export` |
| `/world import <folder\|--all> [flags]` | Migrates legacy or vanilla worlds from `worlds-to-import/` into Paper 26.2 format. | `voyage.admin` |

##### `/world import` Flags
- `--namespace <ns>` — Destination namespace for imported dimensions *(default: `custom`)*.
- `--key <key>` (or `--name <key>`) — Explicit destination world key *(single folder import only)*.
- `--environment <NORMAL|NETHER|THE_END>` — Overrides dimension environment.
- `--generator <gen>` (or `-g <gen>`) — Custom generator plugin name or identifier.
- `--generator-settings <settings>` — Superflat JSON or custom generator settings string.
- `--do-not-auto-link` — Skips automatic portal linking between imported dimensions.
- `--link-data <profile> [strategy]` — Automatically links imported dimensions to a player data profile.
- `--delete-after-import` — Deletes the source folder from `worlds-to-import/` on success.

---

### `/voyage`

| Command | Description | Permission |
| :--- | :--- | :--- |
| `/voyage reload` | Reloads `config.yml`, `worlds.yml`, and data mappings. Online player states are synced before reloading. | `voyage.admin` |

---

## 🔗 Player Data Linking

Voyage manages player state per profile:

- **Isolated Profile (Default):** `isolated:<namespace>_<key>` — Player data belongs strictly to that single dimension.
- **Grouped Profile:** `group:<name>` — Configured in `config.yml` under `world-groups` or via `/world profile-link`. Worlds in the same group share:
  - Inventory and Armor
  - Ender Chest
  - Health and Maximum Health (`Attribute.MAX_HEALTH`)
  - Food Level and Saturation
  - Experience (Level, Progress, and Total XP)
  - Active Potion Effects (duration, amplifier, ambient, particles, icon, infinite)
  - GameMode
  - Respawn Anchor / Bed Location
  - Environmental Ticks (fire, freeze, fall distance, remaining air, maximum air)
  - Last Log-Off Location (tracked per world within the profile)

### Profile Merge Strategies

When linking worlds with existing player files:
- **`DESTINATION_PRIORITY` (Default):** Preserves data in the target group. Conflicting player files in the source profile are discarded.
- **`SOURCE_PRIORITY`:** Overwrites data in the target group with data from the linking world.

> [!TIP]
> Use `--dry-run` with `/world profile-link create` or `/world profile-link break` to preview conflict, transfer, and unaffected counts before modifying files on disk.

### Anti-Duplication Safeguards

During cross-profile transitions, players are placed into an immutable transition state. Inventory clicks, drags, item drops, hand swaps, block placement, interactions, entity pickups, and death item/XP drops are cancelled until the new profile is fully loaded and applied.

---

## 🌀 Portal Linking

Dimension links route portal events across worlds without hardcoding coordinate logic into vanilla portal code.

### Portal Types
- `NETHER` — Triggers on Nether portal blocks (`NETHER_PORTAL` teleport cause).
- `END` — Triggers on End portal frames (`END_PORTAL` teleport cause).
- `CUSTOM` — Custom portal integrations.

### Coordinate Strategies
- `TARGET_SPAWN_POINT` — Places the player at the destination world's spawn location.
- `VANILLA_SCALING` — Scales X and Z coordinates by a factor provided in `[extra]` (e.g., `8.0` for Overworld → Nether, `0.125` for Nether → Overworld; default: `1.0`).

### Portal Generators
- `NONE` — Teleports the player directly to calculated coordinates without generating blocks.
- `VANILLA_NETHER` — Delegates portal frame searching and generation to Paper's native Nether portal engine (applied only for `NETHER` portal events).
- `STRUCTURE` — Generates a specified structure at destination coordinates. Defaults to `minecraft:nether_portal`, or a custom structure key passed via `[extra]`.

> [!NOTE]
> Interception applies an 80-tick portal cooldown to prevent portal re-trigger loops. If the destination world is unloaded, Voyage cancels immediate traversal, begins an asynchronous load, and teleports the player once the world mounts.

---

## 📦 World Import & Migration

Place world directories inside `<server_root>/worlds-to-import/` and execute `/world import <folder|--all> [flags]`.

### Supported Formats
- **Paper 26.x Native** (identified via `data/paper/metadata.dat`)
- **Vanilla 26.x** (identified via `data/minecraft/chunk_tickets.dat`)
- **Paper 1.21.11** (identified via `paper-world.yml` or `uid.dat`)
- **Vanilla 1.21.x** (identified via `level.dat`)

### Import Features
- **Dimension Separation:** Multi-dimension folders containing `DIM-1`, `DIM1`, or modern `dimensions/` paths are split into separate linked dimensions (`<key>`, `<key>_nether`, `<key>_end`).
- **Portal Routing:** Automatically creates reciprocal scaled Nether links (`8:1` and `1:8`) and End links unless `--do-not-auto-link` is passed.
- **Metadata Preservation:** Imports seeds, daytime, difficulty, world borders (`world_border.dat` or `level.dat`), and GameRules (normalizing legacy names, such as `doDaylightCycle` → `advance_time` and `doMobSpawning` → `spawn_mobs`).
- **Contract Enforcement:** Re-stamps level files using the server's current `DataVersion` through `LevelDatNbt.writeVanillaSafe()` to prevent version mismatch exceptions.
- **Atomic Rollback:** If an import fails midway, created worlds and staged filesystem directories are automatically unmounted and deleted.

---

## ⚙️ Configuration

### `config.yml`

```yaml
# ====================
# Voyage Configuration
# ====================

# The designated fallback destination when players are removed from a loaded dimension.
default-world: "minecraft:overworld"

# Worlds under the same group share player inventories, health, etc.
# This section forces data linkage upon server start. Dynamically added linkages will not appear here.
world-groups:
  default_group:
    - "minecraft:overworld"
    - "minecraft:the_nether"
    - "minecraft:the_end"

# Profiles Configuration
profiles:
  # If true, players joining a new profile group for the first time will spawn with a fresh inventory.
  # If false, they will bring whatever inventory they had at the time of the switch.
  start-fresh-on-first-join: true

# World Presets (Templates)
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
  # Events to log: WORLD_CREATE, WORLD_DELETE, WORLD_CLONE, WORLD_IMPORT, WORLD_EXPORT, PROFILE_LINK, PORTAL_LINK
  events:
    - WORLD_CREATE
    - WORLD_DELETE
    - WORLD_CLONE
    - WORLD_IMPORT
    - WORLD_EXPORT
    - PROFILE_LINK
    - PORTAL_LINK
```

### `worlds.yml`

Persists tracked dimensions under `worlds.<namespace:key>`. Maintained automatically by Voyage.

```yaml
worlds:
  custom:lobby:
    environment: NORMAL
    seed: 12345
    generator: null
    worldType: NORMAL
    generatorSettings: null
    autoLoad: true
    difficulty: NORMAL
    generateStructures: true
    spawn:
      x: 0
      y: 65
      z: 0
    gamerules:
      doDaylightCycle: "false"
    metadata:
      note: "Hello!"
    links:
      NETHER:
        target: "custom:nether"
        strategy:
          type: "VANILLA_SCALING"
          factor: 8.0
        generator:
          type: "VANILLA_NETHER"
```

### Sparse Persistence

Optional configuration blocks (`spawn`, `gamerules`, `metadata`, and `links`) use sparse persistence:
- If a world has no custom spawn override, configured GameRules, metadata keys, or portal links, the respective YAML section is omitted from `worlds.yml`.
- On read, missing sections resolve to empty defaults.

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
    mw.setMetadata("note", "Neat builds");
});

// Query entry location (restores log-off position if present, falls back to spawn)
Location entry = voyage.getPlayerEntryLocation(bukkitWorld, player.getUniqueId(), true);

// Export world to singleplayer-ready zip
voyage.exportWorld(worldKey, targetZipPath, player.getUniqueId(), "Epic World");
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

## 🧩 Architecture & Internals

- **Dimension Storage:** Dimensions are resolved under `<level_directory>/dimensions/<namespace>/<key>`. The primary server level is resolved at `<level_directory>`.
- **Vanilla Level.dat Contract:** `LevelDatNbt.writeVanillaSafe()` enforces GZIP compression, the root `Data` wrapper, `DataVersion > 0`, and format version `19133` on all written `level.dat` files, preventing singleplayer client crashes.
- **Single-Threaded Profile Writer:** Player profile JSON writes are serialized through a dedicated daemon executor (`Voyage-Profile-Writer`). Writes stream to `.tmp` files and are committed using `StandardCopyOption.ATOMIC_MOVE` with fallback replacement.
- **In-Memory Write Caching:** Unsaved asynchronous writes are stored in a concurrent pending-write cache, guaranteeing immediate read-your-own-writes consistency across rapid world switches.
- **TTL Cache for World Scanning:** Unmanaged world discovery uses an on-demand 5-second TTL cache (`WorldDirectoryWatcher`) for tab completion rather than persistent OS filesystem watcher threads.
- **Graceful Evacuation:** World unloads evacuate players to the fallback world before requesting Bukkit unmounts; players unable to teleport are kicked before chunk unloading.
- **Synchronous Flush on Shutdown:** Server shutdowns synchronously flush online player states and persist modified `worlds.yml` definitions before the executor terminates.
- **Singleplayer Quality of Life Exports:** World zip exports merge generator settings back into `level.dat`, write the primary player to `Data.Player`, set `allowCommands: 1` ("Allow Cheats"), and output individual `playerdata/<uuid>.dat` files.
