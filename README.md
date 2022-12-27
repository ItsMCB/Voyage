# Voyage
The Minecraft world management solution that's simple to use but powerful when needed.

# Dependencies
Voyage takes advantage of other plugins. Please consider adding the following to your server.

Required:
- [VexelCore 1.0.1 Snapshot](https://github.com/ItsMCB/VexelCore)

Not required, but enables extra feature:
- Placeholder API (Placeholder Support)

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
  world:
    generator: Voyage:void
```

## Superflat
NOTE: The block generation height of the superflat world can be customized by adding `:<height as int>` to the end of the generator configuration section.

```yaml
worlds:
  world:
    generator: Voyage:superflat:-60
```

## Moon
```yaml
worlds:
  world:
    generator: Voyage:moon
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

# Contributors
Contributions are highly appreciated!

When working with Voyage in your local environment, please compile VexelCore and change Voyage's `build.gradle` VexelCore dependency to be the locally compiled file path.
This solution is temporary while the API gets worked on.

# Alternatives
Voyage is awesome, but there are other great open-source plugins to try. Here are a few:
1. [Multiverse](https://github.com/Multiverse/Multiverse-Core)
2. [MyWorlds](https://github.com/bergerhealer/MyWorlds)