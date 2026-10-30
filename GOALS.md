# Cube World: goals

## What the mod is

The overworld is one cube planet that floats in empty space. Each of the six faces is a complete Minecraft world. A player can walk, build, mine, and fall on each face as on a vanilla overworld. Walking over an edge puts the player on the next face.

## Core model

These rules apply to every system in the mod:

1. **Geometry.** The cube center is `(0, centerY, 0)`. The surface (sea level) is at Chebyshev distance `R` from the center. The diameter is 24 to 128 chunks.
2. **Sectors.** Each block belongs to the face of its pyramid sector: the axis with the largest offset from the center. The sector of a block is fixed by its position.
3. **Local frame.** Each sector has a local frame in which the outward face normal is "up". Each sector behaves like a vanilla world turned so that its local up is world up.
4. **Storage is local.** Block states store their local orientation. A `type=bottom` slab on the east face sits against the local floor (world west side). Each system that reads a block state turns it by the sector: rendering, collision shapes, block logic, and worldgen.
5. **Gravity is discrete and sticky.** Gravity always points along one of the six axes, toward the center. Each face has a surface plane at distance `R` along its axis. An entity keeps its gravity while it stays outside the plane of its current face. When it goes inside that plane, it takes the gravity of the face whose plane it is outside of. If no plane matches (inside the cube), the entity keeps its gravity. If more than one plane matches, the entity takes the face with the largest distance past its plane. Example: on the top face, a player can walk out past the edge in X or Z with no change. When the player digs or falls one block below the top plane, gravity turns to the side face.
6. **Gravity changes are safe.** A gravity change turns the hitbox and the velocity with the frame. If the turned hitbox does not fit, the change waits until it fits. The camera turns smoothly over a few ticks.
7. **New entities.** A new entity starts with the gravity of the sector that it spawns in. An item that a player drops gets the gravity of that player.
8. **Server authority.** The server owns the gravity direction of each entity and sends it to clients. Client and server use the same movement code.

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

## Phases

1. Movement core: discrete gravity, oriented movement, server sync, smooth camera.
2. Terrain and decoration: correct density mapping, oceans, vanilla feature placement.
3. Block orientation: shapes, placement, falling blocks, fluids, plant support.
4. Sky light per sector.
5. Mobs: spawn, move, and path-find on all faces.

## Not in version 1

- Structures (villages, strongholds, and more).
- Nether and End changes. These dimensions stay vanilla.
- A view of the full cube from space beyond the vanilla render distance.

## Verification

- Terrain: run the dev server, load the cube chunks, and make images from the region files.
- Movement and block behavior: play-test in the Prism instance `cubeworld`.
