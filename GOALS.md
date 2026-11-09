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

Fixes after the user's test (untested by the user): the pull over the top of the stratosphere is the pull of space also on the face that stores the entity (`SeamLayout.pullAt`), so the hand-off to the space region no longer snaps the tilt. From space, Voxy draws the faces from the farthest to the nearest (`VoxyFaces.sortFarToNear`), because each face draws without the depth of the others and the near face showed the far ones through it. The sky probe in space (`SeamLayout.ownerStorage`) scales the position toward the cube center instead of clamping each axis, so the local time follows the face under the camera and no longer saturates at a corner; the earlier `ORBIT_TWILIGHT` change in `air.fsh` is removed (the user said twilight was not the cause). Untested by the user: the lightmap and fog still come from one probe, so terrain flips between day and night lighting when the camera crosses the terminator.

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

Fixes after the next test (untested by the user): the Voxy faces now draw far to near also on the ground (camera face last), the guess for faces showing through the ground where the levels of detail meet the loaded chunks. The air shader turns from the local atmosphere to a planet-wide one with height (`orbit` in `CubeSky`, 64 to 464 blocks over the surface): the fog tint of vanilla, which is read at one face and did not match the local time of the shader, fades out, and the moon is lit. The sunrise glow and sky colors already come from per-sample local time.

Fix for Voxy levels of detail missing in space after a login or after unloading a shader pack (untested by the user): `SeamFaces.drive` made a manager only for faces whose chunks had loaded, and Voxy draws from inside the manager, so a fresh start in space had none. With Voxy, `drive` now creates the manager of every face.

Localized light in orbit (untested by the user): `FaceLightmaps` keeps a lightmap per face, read from a probe at the middle of that face, and `GameRendererLightmapMixin` hands out the lightmap of the face that draws (Sodium and Voxy without a shader pack both ask `GameRenderer.lightmap()`/`levelLightmap()` per face). The face lightmaps blend in from the camera lightmap with `CubeSky.orbit()`, so the ground is unchanged. `FogRendererMixin` pushes the environmental fog distances out with the same weight. Not done: Iris packs (their light comes from pack uniforms), entities and particles (they use the lightmap of the camera face), vanilla fog color per face.

Cloud layers take the color of their own face from orbit (`FaceLightmaps.cloudColor`, untested by the user). Open: a smooth change of light across an edge needs the lightmap read per vertex (Sodium vertex shader and Voxy) from the local time of the vertex, as the air shader does; the per-face lightmap steps at each edge.

The air shader takes the sky fog distance and the fog alpha from the planet, not the camera probe, as the orbit weight grows (`ColorModulator.w` from `CubeSky.planetSkyEnd`). Untested by the user.

The sky color table (sky, fog, stars, glow) turns from the biome under the camera to one read at the middle of a face as the orbit weight grows, and the environmental fog is gone at half the orbit weight. Reason (from screenshots 12.17.49 and 12.17.52, two spots 16 blocks apart): the table is read at the point where the camera maps onto the owner face, so a biome border under that point changed the whole sky and fog. Untested by the user.

Orbit flip fixed (user confirmed 2026-11-08): `SeamLayout.ownerStorage(Vec3)` capped the owner point at the build top, but a face region is only `spacing` wide, so a lateral coordinate past `spacing / 2` landed in the next region and `regionOfX` read the wrong face rotation (local time jumped by about 1850 ticks). The point is now capped at `min(top, spacing / 2 - 1)`, and only the part past the core is scaled, so the direction (and the local time) is kept. The earlier biome and fog theories were wrong.

The DOWN clouds vanished in space because the camera frame there is the UP frame, which made DOWN the "opposite face" that `CubeClouds` skips. In space nothing is skipped (untested by the user).

Planet seen from space (untested by the user): the server builds `PlanetMap` once per world on a worker thread (64 x 64 cells per face, ground height and a color from the biome, read from the generator in each face frame) and sends it as `PlanetPayload` after the join. The client (`PlanetView`) builds a mesh on the rounded cube surface and `CubeSky.renderPlanet` draws it between the stars and the air, only when the orbit weight is above 0. It is a fallback under the real chunks and Voxy, which draw over it. Lit with the sun direction in `planet.fsh`. Not done: smooth light across an edge (needs per-vertex local time in Sodium and Voxy shaders), the planet mesh in the first minutes of a new world (the map takes a while to generate).

Smooth light across an edge, Sodium (built 2026-11-08, untested by the user): `TerrainShaderPatch` rewrites `block_layer_opaque.vsh` as it loads. The vertex color reads the lightmap of its own face and of the four faces across the edges, with a weight that is 0.5 at the diagonal and falls to 0 over 32 blocks (`FaceLightBlend`, `FaceLightmaps.neighbors`). Both faces of an edge then get the same light at the diagonal. The extra lightmaps and the camera in the face frame go in through `ShaderChunkRendererMixin` (bind group), `DefaultChunkRendererMixin` (bind) and `ShaderManagerMixin` (patch). Off with an Iris pack. Voxy (built 2026-11-08, untested): the same blend per fragment in `quads.frag`. The vertex stage no longer applies the light, it passes the light id in the top bits of `interData.w`. `VoxyLightMapHelperMixin` binds the lightmaps of the face and its four neighbors to units 10 to 14. Not done: Voxy with an Iris pack (the pack does its own light).

The black silhouettes through water near the ground (screenshots of 2026-11-08) are not the planet mesh (a later fade did nothing, reverted). The user says they are Voxy: the band where Voxy fades from the vanilla chunks to its levels of detail (`blit_texture_depth_cutout.frag`, HAS_FADE). Voxy's fog and fade option off did not change it. `/cubevoxy only 1` and `only 4` both show the band on the EAST-SOUTH seam. Likely cause: the coarse levels of Voxy merge voxels across the diagonal, and the voxels past the wedge held the real blocks of the next face, so its sea floor showed over this face's water. `fillPastWedge` now fills them with the face's own blocks mirrored across the diagonal (built 2026-11-08, untested). Voxy keeps the old levels of detail in its cache until it stores the chunks again. That did not fix it: the band shows only as chunks unload, not as they load. Voxy stores a chunk again as it unloads, when the chunks around it may be gone, so the fill found nothing and Voxy drew the cut faces of the ground along the diagonal. The fill now takes the mirror from the section's own data first. Fixed (confirmed by the user 2026-11-08). With Voxy on, parts of the planet mesh showed past the edges of other faces. Each column top of the mesh now sits 6 blocks under the lowest of its four nodes, so the real terrain covers it (not tested in game).

Weather over the air (built 2026-11-08, untested): rain thickened the fog over the whole cube, hid the sun, moon and stars, and switched off at once at the handoff to space. On the client, `LevelRainMixin` scales `Level.getRainLevel` by `1 - CubeSky.fogClear()`, which goes from 0 at the top of the stratosphere to 1 over 20 blocks, before the handoff at 24. Everything that reads the rain level fades together: sky, fog, sun and moon, rain columns, sounds and thunder. `fogClear` also goes to 1 near the side limit where a camera over an edge or corner moves to space (`SeamLayout.sideSpaceLevel`, about 96 blocks over the surface, far under the stratosphere). `FogRendererMixin` also uses `fogClear`, and `ClientLevelMixin` fades the sky flash of lightning with it.

Clouds from orbit (built 2026-11-08, untested): each cloud vertex is lit by the sun at its place on the rounded cube, as the planet mesh is, blended in with `CubeSky.orbit()`. Before, a whole layer took the color of its face center.

Complementary in space, first pass (built 2026-11-08, untested): the pack gets `cubism_orbit` (0 on the ground, 1 well above the air, as `CubeSky.orbit()`). `CubeShaderPatch` uses it so that the pack's sky goes black in orbit, and the fog at the end of the view and the haze no longer cover the planet. The stars of the pack also show by day and below the local horizon. The sun and moon set behind the planet instead of at the horizon of the ground under the camera. The planet mesh also draws with a pack: Iris keeps the mod's own program for a pipeline that it has no program for. The mesh writes no depth, so the pack keeps it as sky color. Expected open points: clouds, volumetric light and the colors of the sky glare follow the time under the camera, not the time at each point. The colors of the planet mesh go through the pack's tone mapping.
Second pass (2026-11-08, untested): the light shafts of the pack fade with `cubism_orbit`. They are traced from the camera, and past the shadow map they count all air as lit, so in space half the sky looked sunny. The cloud march stops where the ray goes under the sea into the planet. Before this, the search for the next cloud band found the clouds on the far side of the planet and drew them over it.
Third pass (2026-11-08, untested): in orbit, each cloud of the pack takes the light of the sun at its place on the cube, not the colors of the time at the camera, and does not fade toward the sky color, which is black there. The night nebula shows at any time in orbit, as the stars do. Left as is: the aurora of the cube is not drawn from above its top height (560); terrain lighting already follows each point.
Fourth pass (2026-11-08, untested). The user confirmed that the world renders with the pack and that the planet mesh colors look right. Changes:
- Clouds, both styles: a cloud takes the light of the sun at its own place where the time there is not the time at the camera, also on the ground. Before, the clouds of a dark face looked bright when seen from a bright face.
- Reimagined clouds: a march step out of the thin band no longer reads the cloud map. Above the band it gave more than full light, which made the bright spots along the rim of the planet. A ray that leaves the band looks for the next entry, so the clouds past an edge and on the far side show.
- The search for the cloud band holds its step under the depth of the band, so it does not step over a thin band. The cloud heights of the pack (`CLOUD_ALT1`, `CLOUD_ALT2`, and the second reimagined layer) work below the sea too.
- Cloud shadows (`CLOUD_SHADOWS`) use the height over the rounded cube, the light at the point, and the plane of the face under it.
- Auroras: a ring around each pole of the sky, the two corners on the sun axis. The ring shows from the ground and from orbit, at night under it, with the moon condition of the pack. The aurora over the camera from the biome condition of the pack fades out in orbit. The aurora is now drawn from inside and from above its band, and over the ground of the planet from orbit.
- The night nebula no longer draws over the planet.
- Terrain and Voxy with a pack: the direct light is turned by the tilt of the rounded up near an edge, so it turns over the same width as the time of day. Before, it changed in a hard line at the edge.

Fifth pass (2026-11-08, untested). The user found that the clouds drew in front of the hills of the planet mesh, and that the aurora came and went at a crossing of faces. Changes:
- The moon phase follows the clock of the world. Before, it took the local time and changed where the local day number changed, at a crossing of faces.
- The server sends a cold flag for each node of the planet map. The client puts the column tops and the cold flags of the mesh in a texture (`cubism:dynamic/planet`), which a pack reads as `cubism_planetTex`. A mixin adds the `customTexture` line to the properties of the pack.
- Clouds, both styles: in orbit the march stops where the ray meets a column of the planet mesh.
- Auroras: no more rings at the poles. The aurora condition of the pack works over the whole cube: "Snowy Biome" shows the aurora over cold cells of the planet at night there, "Full Moon" everywhere at night on full moon nights, "Full Moon in Snowy Biome" over cold cells on full moon nights, "Full Moon or Snowy Biome" both, "Every Night" everywhere. In Voxy water reflections the biome at the camera counts, as Voxy binds no texture of the pack.
- After user test: the cold-biome auroras work. The aurora now also draws in front of the ground below orbit. A ray that leaves the band through the bottom looks for the band again past an edge, and stops only at the sea level of the planet. Before, the aurora below vanished once the camera was inside the band.
- The last frame that a pack reprojects is carried into the frame of the space region at the handoff, from the bottom face too. This change did not stop the bright flash at the handoff.
- The bright flash of a face at the handoff to space and back is fixed (confirmed by the user). Iris draws the shadow pass first, and it puts only the old primary manager into its shadow state. The new primary manager then drew its empty shadow lists, so the shadow map was empty for one frame. `SeamFaces.beginShadowScope` puts the new manager into the shadow state at the swap.

Space, last pass (2026-11-08, confirmed by the user):
- A player that floats in space without flight moves level in the view frame. Before this, the keys moved level in the frame of the UP face, which the view shows tilted on the other sides of the cube.
- In space, the view frame of the local player turns from the frame of the last tick by the shortest turn of its up (`CubeFlight.spaceFrame`). Before this, the frame spun about its up directly under the DOWN face.
- Xaero: in space, the minimap and the world map show the player at a fixed point. The point is past the middle of the row of faces, 512 blocks out from the face strips (`XaeroSpace`).
- The server starts the planet map when the world loads. A new world waits for the map at the end of the pre-generation. On the dev server, the map took 33 s, in a pre-generation of 72 s.
- Voxygen scans the cube (untested in game). Voxygen scans a circle around the player's chunk in storage, so in space it scanned empty space and on a face it scanned storage outside the cube. `EngineManagerMixin` sets its scan center to the middle of the row of faces when the player is in space (on a face it keeps the player's chunk), and `TargetMixin` makes chunks outside the six face regions count as out of range. Chunks of a tile that touches a face are still made. The count of chunks that remain, which Voxygen shows, still includes the chunks outside the faces.
- The loading screen cube turns to the next face when 95% of the cells of a face are full, not 100%. A column that unloaded between two polls showed in a low status for good, so the pre-generation now marks a column full when it is ready. The client logs the cells that are not full at each turn (`visualizer leaves it`).
- Not built, by decision: the planet-ahead view, and elytra rules in space.
