# Cubism: goals

## What the mod is

The overworld is one cube planet in empty space. Each of the six faces is a full Minecraft world. When you walk over an edge, you go onto the next face. The Nether is a smaller cube of the same type. The End is vanilla.

## Core model

Every system in the mod obeys these rules:

1. **Geometry.** The cube center is `(0, centerY, 0)`. The surface (sea level) is at Chebyshev distance `R` from the center. The diameter is 24 to 128 chunks. The edges of the surface are rounded by `edge_rounding`.
2. **Storage.** Each face is stored upright in its own region along +X, as the pyramid wedge `max(|x|, |z|) <= y - centerY`. Thus each face is a vanilla world in which the local up is world up. `SeamLayout` changes storage positions to cube positions and back.
3. **Seams.** The diagonal between two wedges is a seam. Rendering, light, block updates and entity tracking cross a seam with a 90 degree turn. A block next to a seam reads the turned blocks across it. A block that cannot turn reads as air from the other face.
4. **Worldgen stays in the wedge.** The mod refuses writes outside the wedge of a face. A feature or a structure that the diagonal cuts is whole or not placed.
5. **Gravity.** On a face, gravity points straight down in the frame of the face. When a fall leaves its start face, gravity follows `CubeGravity.airGravity`. This field points to the center along nested cubes with rounded edges. Thus a jump off an edge arcs onto the next face. The turn over an edge has the same radius at every height, at least 12 blocks (`CubeGravity.turnRadius`).
6. **Crossing.** When the center of an entity is 1 block past the diagonal, the entity moves into the region of the next face. Its position, velocity and look turn with the same rigid motion as the seam rendering, so the view does not jump.
7. **Camera.** Over an edge, the camera turns with the gravity field. A step or a jump does not jolt the view. When **Edge Camera Tilt** is off (Accessibility options), the camera on the ground turns only over the last 2 blocks before the seam.
8. **Server authority.** The server crosses all entities other than players. A player crosses on its own client, and the server follows with the next movement. The server crosses a player only 4 blocks past the diagonal.
9. **New entities.** A new entity starts upright in the wedge where it spawns. An item that a player drops gets the gravity of that player.

## Version 1

### Overworld

- **Movement.** All vanilla movement works on all six faces. Items, XP orbs, arrows and falling blocks fall toward the face under them.
- **Blocks.** Blocks act as in vanilla on each face. Water, lava, sand and gravel flow and fall in the local frame. Blocks that cannot turn 90 degrees (repeaters, dust, slabs, stairs and more) read as air from the other face.
- **Terrain.** Terrain continues across edges with no cliffs or gaps. Heights, biomes, caves, ores and decoration follow the vanilla rules.
- **Light.** Sky light comes from the local up of each face. The bottom face is not dark at noon.
- **Navigation.** Commands, F3 and `/locate` use cube coordinates. Compasses and eyes of ender point across edges. The clock shows the local time of the face.
- **Structures.** Structures generate on all faces. Villages, ancient cities, trial chambers and mineshafts fold across an edge. Other structures move in from the edge, or they do not generate. Each world has 1 to 3 strongholds, by size.
- **Core.** Caves go down to the bedrock cube at the center. Budding bedrock on its edges and corners grows heavy cores. A lava sea fills only the middle of each bedrock face.
- **Space.** Above the build height, the world opens into space. Gravity decreases with distance and stops past the exosphere. In space, you float and see the whole planet.

### Nether

- The Nether is a cube with a 128-block cave on each face, between a bedrock floor and a bedrock roof.
- A portal leads to the same face, at half the distance from the face center.
- Each face has vanilla Nether terrain and biomes. Caves continue across the edges.
- Each world has 1 to 3 fortresses and 1 to 3 bastions, by size. Fortress corridors continue across an edge.

### Loot of a finite world

The overworld and the Nether are finite. Some structures are not sure to generate on a small cube, so 70 items can be missing from a world. The `finite_loot` setting gives each of these items another source. The setting is on by default, and you can change it on the Customize screen.

- **Armor trims.** The armorer sells one trim at each of the levels 2 to 5.
- **Music discs.** A skeleton that kills a creeper can drop one of the newer discs.
- **Banner patterns.** The cartographer sells flow and guster.
- **Enchanted books.** The librarian sells Swift Sneak III and Wind Burst III.
- **Sea items.** The fisherman sells prismarine shards, prismarine crystals, sponge and the heart of the sea.
- **Wind items.** The weaponsmith sells wind charges. The breeze rod is a gift to the hero of the village.
- **Pottery sherds.** The mason gives a sherd to the hero of the village. The wandering trader sells one.
- **Wandering trader.** Each trader also sells an echo shard, and a sniffer egg or nautilus armor.

Trial keys, camels and the maps found in chests have no new source.

### Steadfast and Heavy Boots

The Steadfast effect keeps you on the face where you stand. Use it to mine near a diagonal or deep near the core without a change of face.

- **Change of face.** Sneak on the ground to cross a diagonal. The lock then holds the new face.
- **No safety release.** If you walk off an edge with Steadfast, you fall past the side of the cube with no end.
- **Elytra.** The lock has no effect while you glide.
- **Forced face.** For map makers, the levels 1 to 6 of `/effect` force the down, up, north, south, west or east face.
- **Heavy Boots.** Make them at a smithing table from iron boots, a heavy upgrade template and a heavy core. While you wear them, you have Steadfast.
- **Heavy upgrade template.** An armorer of level 2 sells one. 7 diamonds, a template and a heavy core make 2 templates.
- **Potion of Steadfastness.** Brew it from an awkward potion and a shulker shell.

## Known limits

- A structure that folds across an edge loses the part in the corner past two edges. Strongholds, monuments and mansions do not cross an edge.
- The edge rounding stops at the surface. Underground, caves and lakes meet the diagonal in a straight line.
- The Nether edges have the smallest rounding at every setting. The Nether does not generate before the first player joins.
- Chunks are not culled across faces. On a small cube at a high view distance, the whole opposite face is sent.
- Entities and particles use the light of the face the camera is on.
- Of the shader packs, only Complementary Unbound r5.9.3 follows the shape of the cube.
- Maps are blank past the edge of a face.

## Later

- The End as a cube dimension, and more content in space.
- Distant Horizons support.
- Redstone and other mechanics that use the turn between faces.
