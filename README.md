# Voyage
WIP modern Minecraft world management solution aimed at being simple to use but powerful when needed.

# Dependencies
Voyage requires the following to work:
- [VexelCore 1.0.1 Snapshot](https://github.com/ItsMCB/VexelCore)

Voyage does not require the following to work, but having them on the server will enable extra features:
- Placeholder API

# Void World
Voyage comes with a built-in void world generator. 
In `bukkit.yml`, add a `worlds` configuration section. 
Then add a subsection with the world name and generator.

Example:
```yaml
worlds:
  world:
    generator: Voyage:void
```

# Placeholders
`%voyage_version%` - Returns version of Voyage plugin

`%voyage_world_name%` - Returns player world name

`%voyage_world_seed%` - Returns player world seed

`%voyage_world_border_size%` - Returns world border size

`%voyage_world_all_amount%` - Returns amount of world folders

`%voyage_world_loaded_amount%` - Returns amount of loaded worlds

# Contributors
Contributions are highly appreciated!

When working with Voyage in your local environment, please compile VexelCore and change Voyage's `build.gradle` VexelCore dependency to be the locally compiled file path.
This solution is temporary while the API gets worked on.

# Alternatives
Voyage is awesome, but there are other great open-source plugins to try. Here are a few:
1. [Multiverse](https://github.com/Multiverse/Multiverse-Core)
2. [MyWorlds](https://github.com/bergerhealer/MyWorlds)