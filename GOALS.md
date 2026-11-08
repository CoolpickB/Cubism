# Cubism: goals

## What the mod is

The overworld is one cube planet that floats in empty space. Each of the six faces is a complete Minecraft world. A player can walk, build, mine, and fall on each face as on a vanilla overworld. Walking over an edge puts the player on the next face.

## Core model

These rules apply to every system in the mod:

1. **Geometry.** The cube center is `(0, centerY, 0)`. The surface (sea level) is at Chebyshev distance `R` from the center. The diameter is 24 to 128 chunks. The edges of the surface are rounded by `edge_rounding`.
2. **Storage.** Each face is stored upright in its own region along +X, as the pyramid wedge `max(|x|, |z|) <= y - centerY`. Thus each face is a vanilla world in which the local up is world up. `SeamLayout` maps storage positions to cube positions.
3. **Seams.** The diagonal between two wedges is a seam. Rendering, light, block updates and entity tracking cross a seam with a 90 degree turn. A block next to a seam reads the turned states of the blocks across it. A state that cannot turn reads as air from the other face.
4. **Worldgen stays in the wedge.** Writes outside the wedge of a face are refused. A feature or a structure that the diagonal would cut is whole or not placed (see Structures).
5. **Gravity.** On a face, gravity points straight down in the frame of the face. When a fall leaves the face that it started from, gravity follows `SeamLayout.airGravity`. This field points along the inward normal of nested cubes with rounded edges, so a jump off an edge arcs onto the next face. The field is continuous across the seams. Its turn over an edge has the same radius at every height: the edge rounding, but at least 12 blocks (`SeamLayout.turnRadius`). The rounding of each level is a curve of the norm of power 2.5, between a circle and the cubic norm. So the turn starts at a rate of 0 and is fastest on the diagonal, at about 4.7 degrees per block. Near the center, where a level is smaller than this radius, the levels are round.
6. **Crossing.** When the center of an entity is 1 block past the diagonal, the entity moves into the region of the next face. Its position, velocity and look turn with the same rigid motion as the seam rendering, so the view does not jump. If the turned body touches blocks, it moves to the nearest free position, at most 1 block away.
7. **Camera.** Over an edge, the camera turns with the gravity field, so the turn is equally wide at every height. On foot, the height that the turn is taken at eases after the ground, and a jump does not raise it. So a stair or a slab does not jolt the view. With **Edge Camera Tilt** off (Accessibility options), the camera on the ground stays upright on the face and turns only over the last 2 blocks before the seam, so it does not snap. In flight the camera always tilts. The eye never goes into a block.
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
- The edges of the Nether have the smallest rounding, 2 blocks, at every `edge_rounding`. Gravity and the camera turn over an edge with the smallest turn radius, 12 blocks.
- Fortresses and bastions are placed as strongholds are: 1 to 3 of each by cube size, one per face, on random faces. No face holds both.
- A bastion moves to a chunk with a bastion biome near its place. If its face has none, it generates in the biome that is there, for example basalt deltas.
- A structure in the Nether folds about the diagonal at its own height. A fortress corridor that reaches past the diagonal turns 90 degrees and continues on the next face. All pieces of one floor of a fortress fold about the same height, so the rooms stay in line with the corridors. A bastion room lies wholly on one face.
- Ruined portals and fossils keep the margin of the overworld, but the margin is measured from the edge of the face halfway up the cave. On the smallest cube, they start up to 88 blocks from the face center. A start that does not lie whole in its wedge moves inward, as in the overworld.

These parts of the Nether are not built yet:

- Edge rounding in the Nether. The overworld rounding fades with depth, so its bedrock is square. To do the same, the Nether needs storage below its floor, about 20 blocks at a rounding of 96. This changes the dimension type of the Nether, and the Nether of an older world breaks.
- Generation of the Nether before the first player joins.

The End is vanilla at this time. Before the first release, we plan this change. The design is not final:

- The main island of the End is on one face. The outer islands have their own gravity, and the player changes face between them.

### Loot of a finite world

The overworld and the Nether are finite, as in a skyblock or superflat world. The End stays vanilla and has no limit. A check of three cubes of size 24 found 70 items that have no source unless a structure generates. These structures are not sure to generate: village, trial chambers, mansion, monument, ancient city, temples, outposts, trail ruins, shipwrecks and buried treasure. These structures always generate: stronghold, fortress, bastion, dungeons, mineshafts, ruined portals and the End. We do not force a missing structure. We give each item another source. The loot tables of fishing and bartering do not change.

The setting `finite_loot` (Customize screen, "Finite-cube loot") turns these sources on. It is on in the `cubism:cube` preset and is saved with the world. A world without the field keeps vanilla trades, gifts and drops. Every price is in emeralds. A villager offers a trade in addition to its vanilla trades, and a vanilla trade keeps its chance.

Sources, by item:

- **Armor trims (14).** The armorer sells one trim at each of the levels 2 to 5. Each level has its own pool, so a villager never offers the same trim twice. Rerolling the job block changes only the novice trades, so the four trims of a villager stay fixed. Level 2 (12 emeralds): coast, dune, wild, sentry. Level 3 (16): tide, vex, silence, ward. Level 4 (20): raiser, host, shaper. Level 5 (24): wayfinder, flow, bolt.
- **Music discs.** A skeleton that kills a creeper has a 25 percent chance to give one more disc: relic, creator, creator music box, precipice or disc 5. It comes in addition to the vanilla disc.
- **Banner patterns.** The cartographer sells flow at level 3 and guster at level 4 (16 emeralds).
- **Enchantments.** The librarian sells Swift Sneak III at level 3 and Wind Burst III at level 4 (30 emeralds).
- **Sea items.** The fisherman sells prismarine shards (1 emerald for 2) and prismarine crystals (3 for 2) at level 2, sponge at level 3 (12), and the heart of the sea at level 5 (64). Prismarine, prismarine bricks, dark prismarine and the sea lantern are crafted from the shards and crystals.
- **Wind items.** The weaponsmith sells 4 wind charges for 3 emeralds at level 5. The breeze rod is a gift of the weaponsmith to a hero of the village. The mace also needs a heavy core, which has its own design.
- **Pottery sherds (20).** The mason gift to a hero of the village includes one random sherd. The wandering trader has one sherd slot (2 emeralds).
- **Wandering trader.** The trader has three more trades, from sets of their own, so the vanilla trades keep their chance. Every trader offers one echo shard (6 emeralds) and one random sherd (2). One more uncommon slot is the sniffer egg (5) or copper, iron, golden or diamond nautilus armor (4, 5, 6, 8). These prices follow the vanilla trader, whose dearest trade is 6. A sniffer egg gives back sniffers, torchflower seeds and pitcher pods.
- **Cartographer maps.** No change. The trade file of a map has a `filtered` step that discards the map if the structure search finds nothing. The villager then picks another trade, so a map never shows for a structure that did not generate.

How it works: the mixins `VillagerMixin` and `WanderingTraderMixin` add the trade set `cubism:<profession>/level_<n>` (or `cubism:wandering_trader/common` and `/uncommon`) after the vanilla set. The disc and gift pools are in override files of the vanilla loot tables `entities/creeper`, `gameplay/hero_of_the_village/mason_gift` and `weaponsmith_gift`. They use the loot condition `cubism:finite_loot`, which reads the setting of the overworld generator. Another mod that replaces one of these three loot tables replaces our pool too.

Not covered, by decision:

- Trial keys and ominous trial keys (no trial chambers, so nothing uses them).
- The heavy core and the mace (own design).
- Camels and camel husks.
- The loot-only maps of chests (buried treasure, abandoned camp, desert pyramid, buried mineshaft, buried ancient city, warm ocean ruins).

Tested on the dev server (size 24): each armorer level offers exactly one trim from its pool, the fisherman, cartographer, librarian and weaponsmith offer their trades, a wandering trader offers 11 trades (9 in vanilla), and the gift tables and the creeper drop give the added items. Not tested: the setting turned off, the Customize screen toggle, and a raid.

### Gravity lock

The Steadfast effect keeps an entity on the face where it stands. Mining near a diagonal or deep near the core then does not turn the player onto the next face.

- **How it works.** An entity with Steadfast does not cross a diagonal. It stays in the region of its face, under the vanilla pull. Past the diagonal, collision reads the turned blocks of the next face, so the entity walks on that rock as on a wall. The edge pull and the edge camera tilt are off while the effect lasts.
- **Change of face.** A player who sneaks on the ground crosses a diagonal as usual. The sneak must start on the ground, so a creative flight down with sneak held does not cross at the landing. The lock then holds the face that the player stands on.
- **No safety release.** A locked player who walks off a surface edge falls past the side of the cube. The fall goes on without end, also when it drifts out to the side, or until the entity lands on the side of a block of a face that it passes. The storage of a face ends at the top of the inner bedrock, but a locked entity below it does not fall out of the world. Only inside the inner bedrock cube, the core, the void still kills.
- **Elytra.** The lock has no effect while you glide.
- **Forced face.** The level of the effect can name a face, for map makers. Level 0 keeps the current face. The command levels 1 to 6 force down, up, north, south, west and east, so `/effect give @s cubism:steadfast 30 2` keeps a player on the up face. A forced entity moves to that face at once, and sneaking does not release it. A higher level replaces a lower one. The effect name shows the face, for example "Steadfast (Up)".
- **Running out.** In the last 5 s of a potion or command effect, the camera rolls a little from side to side. The treads do not cause this roll.
- **View past the side.** A locked camera past the wedge of its region also draws the opposite face of that region, which comes into view from the side of the cube.
- **F3.** The cube face line shows the face that owns the position, also past a diagonal.
- **Shulker Treads.** Boots made of 4 shulker shells and a heavy core (`S S`, `SCS`). They have 3 armor, 2 toughness, 0.1 knockback resistance and the durability of diamond boots (429). They are repaired with shulker shells. While you wear them, you have Steadfast, as the turtle shell gives water breathing. The effect has the normal icon frame, and it ends on the tick that you take them off. A potion that runs at the same time continues.
- **Potion of Steadfastness.** An awkward potion and a shulker shell make it (3:00). Redstone makes it last 8:00. Gunpowder and dragon breath make the splash and lingering potions, and tipped arrows come from the lingering potion.
- **Lore.** The core of the overworld is intact inside the inner bedrock, and heavy cores break off it. The cores of the End collapsed into voids. Shulkers grip surfaces against the pull of these voids.
- **In space.** A locked entity in space falls toward the down of the face that it left, or of the forced face. The fall has no end.

The treads and the effect have their own textures.

Tested on the dev server: the items, the potion and the effect load, and the recipes parse. A pig with Steadfast past a diagonal stayed in its region and fell straight down in the frame of its face. A pig without the effect crossed. A locked pig past a diagonal fell to y -1300 and more, and it did not die or cross. A pig with level 1 moved to the down face and lived. Not tested by me: a player with the treads, sneaking to change face, the camera, the roll, forced faces on a player, the opposite face view, and brewing in a stand.

Next: heavy core deposits above the inner bedrock, more of them near the corners of the core. Then the End: voids with a pull, shulkers on the rims of the voids, and a pull on elytra flight that Steadfast ignores. Later, some of these voids can also float far out past the cube, where a long locked fall finds them.

### Outer space

The overworld has space around the cube. Space has three layers:

- **Stratosphere.** From the surface to build height. Gravity is the pull of the box, as on the ground.
- **Empty air.** From build height to the exosphere. Gravity still pulls toward the planet. The pull changes from the box to a sphere as the height increases.
- **Open space.** Past the exosphere, gravity is zero. The exosphere is a sphere 20 blocks larger than the distance to a corner of the build-height box (about 684 blocks from the center on size 24).

Storage: space is a 7th region in the same world, at x = -10,000,000. It stores plain cube positions. An entity moves to space when its face cannot store its position. This occurs 24 blocks above build height, and near the corners, where the position goes out of the strip of the face. An entity moves back to a face 8 blocks above build height, when the strip of that face can store it. Space chunks are empty air with the void biome. Mobs do not spawn there.

Commands take cube positions, so `/summon armor_stand 0 1000 0` puts an armor stand in space. Relative positions stay in storage: `execute positioned 0 1000 0 run forceload add ~-32 ~-32 ~32 ~32` loads space chunks.

Tested on the dev server (size 24): an armor stand at cube y 500 fell through space, moved to the up face and landed. Stands at y 750 and 1000 floated with no pull. A stand at a corner (350, 380, 350) moved to space, fell toward the center, and landed on the up face. Not tested: a player, the client view from space, and Steadfast in space.

Past the exosphere, jump rises and sneak sinks along the view up, which is away from the cube center (the first version used the cube up axis and worked; the view up is untested by the user). The player model turns about its center in space, because it pivoted at the feet while the hitbox stayed upright (untested). From space, the planet shows as a blue glow with day and night colors and no terrain, because only loaded chunks draw. The sky of a Steadfast fall turned dark long before space, because vanilla fades the fog color to black below the bottom of the world (`FogRenderer.computeFogColor`) and a locked fall keeps the storage depth of its face. `FogRendererMixin` turns the fade off on the cube (not yet tested by the user). A fall through space also reads the sky and biome of the face that owns its position (`SeamLayout.ownerStorage`), because space chunks are the void biome.

Built 2026-11-08, untested by the user: the pull in space fades past the exosphere over 120 blocks with a smoothstep (`EXOSPHERE_FADE`), and jump and sneak push by the share that the pull has lost, so there is no switch from falling to swimming. Creative and spectator flight turn over an edge with a radius of `12 + 0.5 x height over the ground` (`SeamLayout.flightRadius`, eased in and out with the flight), so a flip at height is slow and meets the round pull of space without a step. Walking and elytra keep the radius of 12. The view frame eases when a Steadfast lock begins or ends. Not built: a view frame that accumulates its turns in space. The frame is rebuilt from the pull each tick, so directly under the cube, opposite the reference direction, the roll can flip.

Fixes after the user's test (untested by the user): the pull over the top of the stratosphere is the pull of space also on the face that stores the entity (`SeamLayout.pullAt`), so the hand-off to the space region no longer snaps the tilt. From space, Voxy draws the faces from the farthest to the nearest (`VoxyFaces.sortFarToNear`), because each face draws without the depth of the others and the near face showed the far ones through it. The air shader makes the night side turn dark 2.5 times faster past the terminator when the camera is above the air (`ORBIT_TWILIGHT` in `air.fsh`), because the ground twilight of about 30 degrees made the globe look lit until the camera was over the night side.

Next, in this order:
1. Culling that knows the faces. Built, untested by the user: in space, `SeamLayout.faceCanBeSeen` tests each face against the rounded body of the ground (64 blocks smaller, so hills over the horizon stay in view). A face that the planet hides gets no Sodium or Voxy draw, no seam sections and no entities (`SeamClient.faceHidden`). Per-chunk occlusion across a face boundary is not built.
2. The planet from far away. Voxy already loads the whole row of faces around the middle of storage, so the far levels of detail stay loaded at any distance. What stopped the draw was the reach check of `SeamFaces.drive`, which skipped a face whose folded camera was further than the chunks reach. With Voxy installed that check is off in space (built, untested by the user). Without Voxy: draw our own rounded cube with averaged LOD textures (for example 64x64 per face), not built.
3. The planet-ahead view past the exosphere, and flight keys that follow the view.
4. Iris and Xaero in space. Later, other dimensions can get space too.

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
