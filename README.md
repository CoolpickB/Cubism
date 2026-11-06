# Cubism

Cubism is a Fabric mod for Minecraft 26.3. It makes the overworld one cube planet that floats in empty space.

Each of the six faces is a complete Minecraft world. You can walk, build, mine and fall on each face as on a vanilla overworld. When you walk over an edge, you go onto the next face, and the view does not jump. Gravity follows the face under you. A jump off an edge arcs onto the next face.

## Requirements

- Minecraft 26.3
- Fabric Loader 0.20.1 or later
- Fabric API
- Java 25

To play, select the **Cubism** world type when you create a world. The mod must be on the server and on each client.

## Other mods

These mods work with Cubism:

| Mod | Notes |
|---|---|
| Sodium | |
| Iris | All shader packs get the folded faces of the cube. Complementary Unbound r5.9.3 also gets the cube sun, sky and clouds. |
| Voxy | Works with and without shader packs. With a shader pack, only Complementary Unbound gets the correct light on the distant terrain of other faces. |
| Lithium | Cubism turns off four Lithium options for collisions. Lithium writes them to the log at startup. |
| Do a Barrel Roll | |

We plan support for these mods before version 1.0:

- Entity Culling
- More Culling
- Continuity
- Voxygen
- Xaero's Minimap and Xaero's World Map

These mods do not work with Cubism at this time:

- C2ME
- Distant Horizons

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
