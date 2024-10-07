<div align="center">
	<h1> Voyage </h1>
</div>

<div align="center">
<a target="_blank" href="https://github.com/itsmcb/Voyage/blob/main/LICENSE">
       <img alt="Software License" src="https://img.shields.io/github/license/itsmcb/voyage?color=7c3aed&style=flat-square">
    </a>
    <a target="_blank" href="https://github.com/itsmcb/Voyage/releases">
        <img alt="Version" src="https://img.shields.io/github/v/release/itsmcb/voyage?color=7c3aed&label=version&style=flat-square">
    </a>
     <a target="_blank" href="https://github.com/ItsMCB/Voyage/commits/">
       <img alt="Last Commit" src="https://img.shields.io/github/last-commit/itsmcb/voyage?color=7c3aed&style=flat-square">
    </a>
    <br>
    <a target="_blank" href="https://discord.gg/86qJJHtDgT">
        <img alt="Discord" src="https://img.shields.io/badge/Discord-Voyage-7c3aed?logo=discord&style=flat-square">
    </a>
</div>

<div align="center">
<h2>Modern Minecraft world management for <a target="_blank" href="https://papermc.io/">Paper</a> and <a target="_blank" href="https://purpurmc.org/">Purpur</a></h2>
<p style="text-align: center;">Voyage is a work-in-progress world management solution designed to be simple to use but powerful but needed.</p>

<div align="center">
	<h2> Download </h2>
	A pre-built jar will be provided once Voyage is ready for a 1.0 release. Until then, feel free to clone the repo and build the jar yourself.

Must also install (dependency):
- [VexelCore 1.0.1 Snapshot](https://github.com/ItsMCB/VexelCore)

Not required, but enables extra feature:
- Placeholder API (Placeholder Support)
</div>


# Commands
### World

## Creation Flags
`--environment`:
- NORMAL
- NETHER
- THE_END

`--type`:
- DEFAULT
- FLAT
- LARGEBIOMES
- AMPLIFIED

`/world info <world name>` - View information about world

`/world options <world name> <option> <value>` - Configure world options

Options and values:
- `time <freeze/unfreeze>` - Changes daylight cycle
- `fire <on/off>` - Changes fire spread

### Entity
`/entity select` - Select an entity

`/entity tphere` - Teleport entity to your location.

`/entity mount` - Mount onto entity

`/entity remove` - Delete entity

### Chunk
`/chunk info` - View information about the current chunk you're standing in

# Generators
Voyage comes with multiple basic **built-in** generators.

In `bukkit.yml`, add a `worlds` configuration section.   
Then add a subsection with the world name and generator.

## Void
```yaml  
worlds:  
 world: generator: Voyage:void  
```  

## Superflat
NOTE: The block generation height of the superflat world can be customized by adding `:<height as int>` to the end of the generator configuration section.

```yaml  
worlds:  
 world: generator: Voyage:superflat:-60  
```  

## Moon
```yaml  
worlds:  
 world: generator: Voyage:moon  
```  


# Placeholders
`%voyage_version%` - Returns version of Voyage plugin

`%voyage_world_name%` - Returns player world name

`%voyage_world_seed%` - Returns player world seed

`%voyage_world_border_size%` - Returns world border size

`%voyage_world_all_amount%` - Returns amount of world folders

`%voyage_world_loaded_amount%` - Returns amount of loaded worlds

`%voyage_chunk_is_slime_chunk%` - Returns player chunk slime chunk status

`%voyage_chunk_x` - Returns player chunk X coordinate

`%voyage_chunk_z` - Returns player chunk Z coordinate

# Datapacks
In vanilla Minecraft, datapacks are put into the desired world's datapacks folder.  
On Bukkit-based Minecraft servers, they are put into the main world's folder and affect the whole server.

# Contributors
Contributions are highly appreciated!

When working with Voyage in your local environment, please compile VexelCore and change Voyage's `build.gradle` VexelCore dependency to be the locally compiled file path.  
This solution is temporary while the API gets worked on.

# Alternatives
Voyage is awesome, but there are other great open-source plugins to try. Here are a few:
1. [Multiverse](https://github.com/Multiverse/Multiverse-Core)
2. [MyWorlds](https://github.com/bergerhealer/MyWorlds)

