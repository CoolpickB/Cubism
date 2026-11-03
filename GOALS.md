# Cube World: goals

## What the mod is

The overworld is one cube planet that floats in empty space. Each of the six faces is a complete Minecraft world. A player can walk, build, mine, and fall on each face as on a vanilla overworld. Walking over an edge puts the player on the next face.

## Core model

These rules apply to every system in the mod:

1. **Geometry.** The cube center is `(0, centerY, 0)`. The surface (sea level) is at Chebyshev distance `R` from the center. The diameter is 24 to 128 chunks.
2. **Sectors.** The pyramid sector of a position is the face of the axis with the largest offset from the center. The sector gives the default orientation of a block at that position.
3. **Local frame.** Each face has a local frame in which the outward face normal is "up". Each face behaves like a vanilla world turned so that its local up is world up.
4. **Each block has an orientation.** Block states store their local orientation. A `type=bottom` slab with east orientation sits against the local floor (world west side). Each system that reads a block state turns it by the orientation of the block: rendering, collision shapes, block logic, and worldgen. Most blocks use the default orientation of their sector. Each chunk stores a list of the blocks that have a different orientation. The server syncs this list to clients.
5. **Orientation is sticky too.** A block that a player places gets the orientation of the gravity of that player. A worldgen feature (tree, ore vein, gravel patch, structure) gets the orientation of the face at its origin. A feature that the diagonal into the next sector would cut is not placed (see Structures).
6. **Gravity is discrete and sticky.** Gravity always points along one of the six axes, toward the center. Each face has a surface plane at distance `R` along its axis. An entity keeps its gravity while it stays outside the plane of its current face. Inside that plane, a face takes the entity only if the entity is inside the square of that face and the column above the entity, in the frame of that face, is open to the sky. If the current face matches, the entity keeps its gravity. If no face matches (a cave, or inside the cube), the entity keeps its gravity. If more than one face matches, the entity takes the face where it is highest. Example: a player walks off a cliff at the edge of the top face. The player flips when the player gets into the open air of the side face, not at the sea plane. A player in a closed tunnel under an edge does not flip.
7. **Gravity changes are safe.** A gravity change turns the hitbox and the velocity with the frame. If the turned hitbox does not fit, the change waits until it fits. The camera turns smoothly over a few ticks.
8. **New entities.** A new entity starts with the gravity of the sector that it spawns in. An item that a player drops gets the gravity of that player.
9. **Server authority.** The server owns the gravity direction of each entity and sends it to clients. Client and server use the same movement code.

## Goals for version 1

### Movement and entities

- Players: walk, sprint, sneak (with edge stop), jump, step up, land, take fall damage, swim, climb ladders, and fly in creative on all six faces.
- Walking over an edge moves the player onto the next face without a jump in position or view.
- Items, XP orbs, arrows, and falling blocks fall toward the face under them.
- Entity models render upright in the local frame.

### Blocks

- Block models, outline shapes, and collision shapes turn with the sector. Slabs, stairs, snow layers, and paths collide correctly on all faces.
- Placement direction comes from the local frame. A torch, log, or stair placed on a side face points the same way as on the top face.
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

## Phases

1. Movement core: discrete gravity, oriented movement, server sync, smooth camera.
2. Terrain and decoration: correct density mapping, oceans, vanilla feature placement.
3. Block orientation: shapes, placement, falling blocks, fluids, plant support.
4. Sky light per sector.
5. Mobs: spawn, move, and path-find on all faces.

## Not in version 1

- Structures that fold across an edge in every case: the corner past two edges is dropped, and strongholds, monuments and mansions do not cross an edge.
- Nether and End changes. These dimensions stay vanilla.
- A view of the full cube from space beyond the vanilla render distance.

## Verification

- Terrain: run the dev server, load the cube chunks, and make images from the region files.
- Movement and block behavior: play-test in the Prism instance `cubeworld`.
