# Cubism

Cubism is a Minecraft mod that turns the world into a finite cube.

You live on a six-sided planet floating in space. Each face plays like a normal Minecraft world, right up to the edge. Step over it and gravity turns with you, onto a side of the game you haven't seen before.

## Requirements

- Minecraft 26.3
- Fabric Loader 0.20.1 or later
- Fabric API
- Java 25

To play, select the **Cubism** world type when you create a world. The mod must be on the server and on each client.

## Mod support

| Mod | Support | Notes |
|---|---|---|
| C2ME | 🟢 | |
| Continuity | 🟢 | Connected textures connect across the edges of the cube. |
| Distant Horizons | 🔴 | Support planned for the future, use Voxy for now. |
| Do a Barrel Roll | 🟢 | |
| Entity Culling | 🟡 | Entity Culling does not cull or stop the entities of other faces. As a result, these entities do not get its performance gain. |
| Iris | 🟢 | Shader packs generally load fine, but Complementary Unbound r5.9.3 is currently the only one that follows the cube's shape properly. |
| Lithium | 🟢 | Turns off a few options for collisions. |
| More Culling | 🟡 | |
| Sodium | 🟢 | |
| Voxy | 🟢 | |
| Voxygen | 🟢 | |
| Xaero's Minimap and Xaero's World Map | 🟡 | The maps show the six faces side by side, not as a cube. Waypoint markers and teleports work across faces. |

If you find a problem with another mod, open an issue. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Client options

The client options are in `config/cubism-client.properties`. The game writes this file at the first start.

| Key | Default | Effect |
|---|---|---|
| `edgeCameraTilt` | `true` | The camera tilts with the ground over a rounded edge. You can also set this in the Accessibility options. |
| `showStorageLine` | `false` | F3 shows the storage position and the chunks that are missing near you. For debugging. |
| `traceLoads` | `false` | Each world load writes a timeline to `logs/cubism-load-<time>.log`. For debugging. |
| `traceLook` | `false` | The look and the face of the player go to `logs/cubism-look.log`. For debugging. |

To write the load timeline on a dedicated server, start the server with `-Dcubism.loadTrace=true`.

## Build

1. Install Java 25.
2. Clone this repository.
3. Run `./gradlew build`.

The mod jar is in `build/libs`.

To copy each build into the mods folder of a test instance, add this line to `~/.gradle/gradle.properties`:

```properties
cubism.deployDir=/path/to/instance/mods
```

## License

MIT. See [LICENSE](LICENSE).
