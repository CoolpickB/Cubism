# Cubism: goals

## What the mod is

The overworld is one cube planet that floats in empty space. Each of the six faces is a complete Minecraft world. A player can walk, build, mine, and fall on each face as on a vanilla overworld. Walking over an edge puts the player on the next face.

## Core model

These rules apply to every system in the mod:

1. **Geometry.** The cube center is `(0, centerY, 0)`. The surface (sea level) is at Chebyshev distance `R` from the center. The diameter is 24 to 128 chunks. The edges of the surface are rounded by `edge_rounding`.
2. **Storage.** Each face is stored upright in its own region along +X, as the pyramid wedge `max(|x|, |z|) <= y - centerY`. Thus each face is a vanilla world in which the local up is world up. `SeamLayout` maps storage positions to cube positions.
3. **Seams.** The diagonal between two wedges is a seam. Rendering, light, block updates and entity tracking cross a seam with a 90 degree turn. A block next to a seam reads the turned states of the blocks across it. A state that cannot turn reads as air from the other face.
4. **Worldgen stays in the wedge.** Writes outside the wedge of a face are refused. A feature or a structure that the diagonal would cut is whole or not placed (see Structures).
5. **Gravity.** On a face, gravity points straight down in the frame of the face. When a fall leaves the face that it started from, gravity follows `SeamLayout.airGravity`. This field points along the inward normal of nested rounded cubes, so a jump off an edge arcs onto the next face. The field is continuous across the seams, above and below the surface.
6. **Crossing.** When the center of an entity is 1 block past the diagonal, the entity moves into the region of the next face. Its position, velocity and look turn with the same rigid motion as the seam rendering, so the view does not jump. If the turned body touches blocks, it moves to the nearest free position, at most 1 block away.
7. **Camera.** On the ground over a rounded edge, the camera tilts with the ground. With **Edge Camera Tilt** off (Accessibility options), the camera stays upright on the face and turns over 8 ticks at the seam. In flight the camera always tilts. The eye never goes into a block.
8. **Server authority.** The server crosses entities other than players. A player crosses on its own client, and the server follows with the next movement. The server crosses a player itself only 4 blocks past the diagonal.
9. **New entities.** A new entity starts upright in the wedge where it spawns. An item that a player drops gets the gravity of that player.

## Goals for version 1

### Movement and entities

- Players: walk, sprint, sneak (with edge stop), jump, step up, land, take fall damage, swim, climb ladders, and fly in creative on all six faces.
- Walking over an edge moves the player onto the next face without a jump in position or view.
- Items, XP orbs, arrows, and falling blocks fall toward the face under them.
- Entity models render upright in the local frame.

### Blocks

- Block models, shapes and placement act as vanilla on each face, because each face is stored upright.
- Next to a seam, blocks see the turned blocks across it. Blocks that cannot turn 90 degrees (repeaters, dust, slabs, stairs and more) read as air from the other face.
- Gravity blocks (sand, gravel, concrete powder) fall toward the center.
- Water and lava flow downhill in the local frame on all faces.
- Plants, torches, and other attached blocks survive or break against their local support.

### Terrain

- Terrain is seamless across edges: no cliffs or gaps that come from the face boundary.
- Heights, biomes, caves, and ores follow vanilla rules, with depth measured from the local surface.
- Oceans and rivers fill to sea level on all faces.
- Decoration uses the vanilla placed features with their own counts and rarity: trees, flowers, grass, ores, and more. The decoration density on each face matches vanilla.
- Worldgen never reads or writes outside the region that vanilla permits ("unsafe terrain read" errors are a defect).

### Lighting

- Sky light comes from the local up direction of each sector. The bottom face is not dark at noon, and caves on side faces are dark.

### Navigation

- Absolute command coordinates are cube coordinates, the same as F3 shows. All-relative coordinates stay in the local frame of the face.
- `/locate`, F3 + C, F3 + I and F3 + B give cube coordinates. `/locate` gives the distance over the surface.
- Compasses and eyes of ender point along the straight line in the unfolded plane of the face: over one edge for a neighbor face, over two for the opposite face.
- The clock shows the local time of the face. Maps show blank past the edge of a face.

### Structures

- Structures generate on all faces. A feature or a structure that the diagonal between two faces would cut is whole or not placed at all: features are undone when cut, and rigid structures move inward from the edge or do not generate.
- Jigsaw structures (villages, ancient cities, trial chambers) and mineshafts cross an edge: the part past the edge folds onto the next face. Underground, a passage folds about the diagonal at its depth, and a room lies wholly on one face.
- Strongholds: one per face on random faces, 1 to 3 by cube size.
- The Customize screen shows only structures that the real generator places.

### Nether and End

The Nether is a cube. Its faces carry the vanilla Nether cave on a core of netherrack:

- A bedrock floor and a bedrock roof close the cave on all six faces, 128 blocks apart.
- At the lava sea, a Nether face is half as wide as an overworld face.
- A portal leads to the same face. A Nether position is half as far from its face center as the overworld position. Past the edge of the overworld face, the exit moves in to 8 blocks from the edge.
- Each face reads the 3D terrain noise in its own frame, so the terrain of each face is vanilla. Near an edge, the noise of the two faces mixes, so caves continue across the edge.
- The client shows the other faces of the Nether, and gravity and the camera turn at an edge as in the overworld.
- The biomes are read on the cube halfway up the cave, so a biome is as large as in vanilla.
- Fortresses and bastions are placed as strongholds are: 1 to 3 of each by cube size, one per face, on random faces. No face holds both.
- A bastion moves to a chunk with a bastion biome near its place. If its face has none, it generates in the biome that is there, for example basalt deltas.
- A structure in the Nether folds about the diagonal at its own height. A fortress corridor that reaches past the diagonal turns 90 degrees and continues on the next face. A bastion room lies wholly on one face.

These parts of the Nether are not built yet:

- A check of the loot of a finite world.
- Generation of the Nether before the first player joins.
- Ruined portals and fossils in the Nether use the margin rule of the overworld. On the smallest cube, this rule moves their starts to 24 blocks or less from the face center.

The End is vanilla at this time. Before the first release, we plan this change. The design is not final:

- The main island of the End is on one face. The outer islands have their own gravity, and the player changes face between them.

## Phases

1. Movement core: crossing, edge gravity, server sync, camera.
2. Terrain and decoration: correct density mapping, oceans, vanilla feature placement.
3. Seams: block updates, falling blocks, fluids and plant support across a seam.
4. Light across seams.
5. Mobs: spawn, move, and path-find on all faces.

## Not in version 1

- Structures that fold across an edge in every case: the corner past two edges is dropped, and strongholds, monuments and mansions do not cross an edge.
- A view of the full cube from space beyond the vanilla render distance.

## Verification

- Terrain: run the dev server, load the cube chunks, and make images from the region files.
- Movement and block behavior: play-test in the Prism instance `cubeworld`.
