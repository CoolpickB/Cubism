# Cubism

Cubism is a Fabric mod for Minecraft 26.3. It makes the overworld one cube planet that floats in empty space.

Each of the six faces is a complete Minecraft world. You can walk, build, mine and fall on each face as on a vanilla overworld. When you walk over an edge, you go onto the next face, and the view does not jump. Gravity follows the face under you. A jump off an edge arcs onto the next face.

## Requirements

- Minecraft 26.3
- Fabric Loader 0.20.1 or later
- Fabric API
- Java 25

To play, select the **Cubism** world type when you create a world. The mod must be on the server and on each client.

## Mod support

| Mod | Support | Notes |
|---|---|---|
| C2ME | 🔴 | Blocked as a precaution. It runs world generation on many threads, which can quietly break the cube's generation and damage saves. |
| Continuity | 🟢 | Connected textures connect across the edges of the cube. |
| Distant Horizons | 🔴 | Not supported at this time. Support is planned after the first release. |
| Do a Barrel Roll | 🟢 | |
| Entity Culling | 🟡 | Entity Culling does not cull or stop the entities of other faces. As a result, these entities do not get its performance gain. |
| Iris | 🟢 | Shader packs generally load fine, but Complementary Unbound r5.9.3 is currently the only one that follows the cube's shape properly. |
| Lithium | 🟢 | Turns off a few options for collisions. |
| More Culling | 🟡 | |
| Sodium | 🟢 | |
| Voxy | 🟢 | |
| Voxygen | 🟢 | |
| Xaero's Minimap and Xaero's World Map | 🟡 | The maps show the six faces side by side, not as a cube. We did not test waypoint beacons and distances for a waypoint on another face. |

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
