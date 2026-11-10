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

- **Armor trims (14).** The armorer sells one trim at each of the levels 2 to 5. Each level has its own pool, so a villager never offers the same trim twice. Rerolling the job block changes only the novice trades, so the four trims of a villager stay fixed. The pools follow the origin of the trim. Level 2 (12 emeralds, shore and biome): coast, tide, dune, wild. Level 3 (16, dark places): sentry, vex, silence, ward. Level 4 (20, trail ruins): wayfinder, raiser, shaper, host. Level 5 (24, trial chambers): flow, bolt.
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
- **Opposite face in range.** The opposite face is sent and drawn like a neighbor face, when it is within the view distance in cube space. From the surface of a large cube it is out of range. Near the core, and in open dimensions, it shows. There is no occlusion culling across faces: on a small cube at a high view distance the whole opposite face is sent, mostly deep rock (untested in game).
- **F3.** The cube face line shows the face that owns the position, also past a diagonal.
- **Shulker Treads.** Boots made of 4 shulker shells and a heavy core (`S S`, `SCS`). They have 3 armor, 2 toughness, 0.1 knockback resistance and the durability of diamond boots (429). They are repaired with shulker shells. While you wear them, you have Steadfast, as the turtle shell gives water breathing. The effect has the normal icon frame, and it ends on the tick that you take them off. A potion that runs at the same time continues.
- **Potion of Steadfastness.** An awkward potion and a shulker shell make it (3:00). Redstone makes it last 8:00. Gunpowder and dragon breath make the splash and lingering potions, and tipped arrows come from the lingering potion.
- **Lore.** The core of the overworld is intact inside the inner bedrock, and heavy cores break off it. The cores of the End collapsed into voids. Shulkers grip surfaces against the pull of these voids.
- **In space.** A locked entity in space falls toward the down of the face that it left, or of the forced face. The fall has no end.

The treads and the effect have their own textures.

Tested on the dev server: the items, the potion and the effect load, and the recipes parse. A pig with Steadfast past a diagonal stayed in its region and fell straight down in the frame of its face. A pig without the effect crossed. A locked pig past a diagonal fell to y -1300 and more, and it did not die or cross. A pig with level 1 moved to the down face and lived. Not tested by me: a player with the treads, sneaking to change face, the camera, the roll, forced faces on a player, the opposite face view, and brewing in a stand.

Next: the End: voids with a pull, shulkers on the rims of the voids, and a pull on elytra flight that Steadfast ignores. Later, some of these voids can also float far out past the cube, where a long locked fall finds them. Later, the treads become Heavy Boots: iron boots, a heavy upgrade template (armorer, level 2) and a heavy core, at a smithing table.

### Core cavern

A cavern wraps the inner bedrock cube. The heavy cores for the boots and the mace grow in it. The feature `cubism:core_cavern` (`CoreCavern`) runs in the first generation step of every biome and acts only in the overworld cube.

- **Shape.** Field lines, like iron filings around a magnet. The 8 corners of the inner bedrock cube are poles. Along each of the 12 edges, 4 arcs join the two corners. Each arc is a tube of air that is 1.6 blocks in radius at the poles, where it runs along the edge, and 3 blocks in the middle, where it lifts off and bulges away from the cube. Each arc has its own height (8 to 22 blocks) and its own angle between the two faces at the edge. Everything else is solid: the middle of a face has no air over it. The shape is in cube coordinates, so it is the same on every face and whole across the diagonals. The feature writes only into solid rock: caves, water and lava made by the vanilla generator stay, so natural caves run into and through the tubes and the lining. No lava of its own.
- **Layers, from the air outward.** 2 blocks of purpur, 3 blocks of obsidian. One cave-wall cell in 40 has crying obsidian as the lowest obsidian block and no purpur under it, so it shines into the cave.
- **Purpur.** The raw form of the purpur of the End cities, which the gravity of a core compresses. It has the strength of the vanilla block and needs a pickaxe. The vanilla Purpur Block is now called Purpur Bricks, with its stairs and slab, as prismarine has prismarine bricks.
- **Budding bedrock.** Acts as bedrock in every way. On a random tick, with a chance of 1 in 40, it tries one of its six sides, and a heavy core grows there if the side is air. It is rare: it replaces bedrock only on the edges and corners of the cube where the bedrock touches air, with a chance of 1 in 100 on an edge block and 1 in 4 on a corner block. Only the outermost two cell rows of an edge and corner count. At generation, each air side of a budding block holds a heavy core with a chance of 1 in 3, as a geode starts with some buds.
- **Shulkers.** At generation, 1 purpur block in 150 that touches the air has a shulker on its air side, at most 2 per chunk. They stay: they do not despawn and do not respawn.
- **Textures.** Purpur is animated: 4 frames with the frame order and timing of prismarine.
- **Not yet.** A recipe from purpur to purpur bricks.

Tested on the dev server (size 24): the feature runs without errors in 78 s of generation. In the sampled area there are 6 budding blocks and 2 heavy cores at generation. With the random tick speed at 4000 for 60 s, the cores grew to 6, which is every free air side. Crying obsidian is next to air. 14 shulkers in the cavern of the whole cube, all at its depth, persistent. Not tested: a larger size, where vanilla caves reach the tubes, and walking in the cavern.

### Outer space

The overworld has space around the cube. Space has three layers:

- **Stratosphere.** From the surface to build height. Gravity is the pull of the box, as on the ground.
- **Empty air.** From build height to the exosphere. Gravity still pulls toward the planet. The pull changes from the box to a sphere as the height increases.
- **Open space.** Past the exosphere, gravity is zero. The exosphere is a sphere 20 blocks larger than the distance to a corner of the build-height box (about 684 blocks from the center on size 24).

Storage: space is a 7th region in the same world, at x = -10,000,000. It stores plain cube positions. An entity moves to space when its face cannot store its position. This occurs 24 blocks above build height, and near the corners, where the position goes out of the strip of the face. An entity moves back to a face 8 blocks above build height, when the strip of that face can store it. Space chunks are empty air with the void biome. Mobs do not spawn there.

Commands take cube positions, so `/summon armor_stand 0 1000 0` puts an armor stand in space. Relative positions stay in storage: `execute positioned 0 1000 0 run forceload add ~-32 ~-32 ~32 ~32` loads space chunks.

Status: the user played the space work in game on 2026-11-08 and it works. Items marked "untested" were built after that test or were not checked alone. The dev server (size 24) also tested the storage: an armor stand at cube y 500 fell through space, moved to the up face and landed. Stands at y 750 and 1000 floated. A stand at the corner (350, 380, 350) moved to space and landed on the up face.

#### Gravity and movement

- **Pull.** The pull of the planet fades past the exosphere over 120 blocks with a smoothstep (`EXOSPHERE_FADE`). Jump and sneak push by the share that the pull has lost, so there is no switch from falling to swimming. Above the top of the stratosphere, a face also uses the pull of space (`SeamLayout.pullAt`), so the hand-off to the space region does not snap the tilt.
- **Floating.** Past the exosphere, jump rises and sneak sinks along the view up, which is away from the center of the cube. The movement keys move level in the view frame (`CubeFlight.spaceMoveFrame`), not in the frame of the UP face. The player model turns about its center.
- **View frame.** In space, the view frame of the local player turns from the frame of the last tick by the shortest turn of its up (`CubeFlight.spaceFrame`). The frame does not spin directly under the DOWN face. The frame eases when a Steadfast lock begins or ends.
- **Flight.** Creative and spectator flight turn over an edge with a radius of `12 + 0.5 x height over the ground` (`SeamLayout.flightRadius`). A flip at height is slow and meets the round pull of space without a step. Walking and elytra keep the radius of 12.
- **Steadfast.** A locked fall goes on through space. The sky of a locked fall does not go dark, because `FogRendererMixin` turns off the vanilla fade of the fog color below the bottom of the world.
- **Decided against.** The planet-ahead view, in which the camera turns to face the planet, and special elytra rules in space.

#### Drawing the planet

- **Culling.** In space, `SeamLayout.faceCanBeSeen` tests each face against the rounded body of the ground (64 blocks smaller, so hills over the horizon stay in view). A face that the planet hides gets no Sodium or Voxy draw, no seam sections and no entities (`SeamClient.faceHidden`). Culling per chunk across a face boundary is not built.
- **Voxy.** Voxy loads the row of faces around the middle of storage, so the far levels of detail stay loaded at any distance. In space, `SeamFaces.drive` skips its reach check when Voxy is installed and makes a manager for every face. Voxy draws the faces from the farthest to the nearest (`VoxyFaces.sortFarToNear`), with the camera face last, because each face draws without the depth of the others.
- **Planet mesh.** The server builds `PlanetMap` when the world loads (64 x 64 cells per face: ground height, a color from the biome, and a cold flag). A new world waits for it at the end of the pre-generation. On the dev server, it took 33 s in a pre-generation of 72 s. The server sends it as `PlanetPayload` after the join. `PlanetView` makes columns on the rounded cube. Each top sits 6 blocks under the lowest of its four nodes, so real terrain covers it. `CubeSky.renderPlanet` draws it between the stars and the air when the orbit weight is above 0.
- **Orbit weight.** `CubeSky.orbit()` goes from 0 at 64 blocks over the surface to 1 at 464. It blends the local sky into a planet-wide one: the fog tint of vanilla fades out, and the moon is lit.
- **Light and fog by face.** The sky probe in space reads the face under the camera (`SeamLayout.ownerStorage`). `FaceLightmaps` keeps one lightmap per face, and `GameRendererLightmapMixin` gives the face that draws its own lightmap. They blend in with the orbit weight. The sky color table, the fog distance and the fog alpha turn from the camera probe to the middle of a face as the orbit weight grows (`CubeSky.planetSkyEnd`).
- **Edge light.** Both faces of an edge get the same light at the diagonal. The blend has a weight of 0.5 at the diagonal and falls to 0 over 32 blocks (`FaceLightBlend`). Sodium does it in the vertex shader (`TerrainShaderPatch`). Voxy does it per fragment (`quads.frag`, `VoxyLightMapHelperMixin`). It is off with an Iris pack, which does its own light.
- **Clouds and weather.** Each cloud vertex is lit by the sun at its place on the rounded cube, and takes the color of its own face (`FaceLightmaps.cloudColor`). The DOWN clouds draw in space. `LevelRainMixin` scales the rain level by `1 - CubeSky.fogClear()`, which goes from 0 at the top of the stratosphere to 1 over 20 blocks. Sky, fog, sun, moon, rain, sound and the lightning flash fade together.
- **Voxy seam band.** `fillPastWedge` fills the voxels past the wedge of a face with the blocks of that face, mirrored across the diagonal. It takes them from the section itself first, because Voxy stores a chunk again as it unloads, when the chunks around it are gone.

#### Complementary

`CubeShaderPatch` and the `cubism_orbit` uniform (the same value as `CubeSky.orbit()`) do the following:

- The sky of the pack goes black in orbit. The fog at the end of the view and the haze do not cover the planet.
- The stars show by day and below the local horizon. The night nebula shows at any time in orbit, and it does not draw over the planet. The sun and moon set behind the planet.
- The light shafts of the pack fade, because they count all air as lit.
- Clouds of both styles take the light of the sun at their own place, in orbit and on the ground. The march stops where the ray goes under the sea, and where it meets a column of the planet mesh. The mesh writes no depth, so the pack reads its column tops from a texture (`cubism:dynamic/planet`, bound as `cubism_planetTex` by `IrisShaderPackMixin`). The cloud band is searched again after an edge, so the clouds past an edge and on the far side show. The cloud shadows use the height over the rounded cube.
- Terrain and Voxy turn the direct light by the tilt of the rounded up near an edge, so the light turns over the same width as the time of day.
- The moon phase follows the clock of the world, so it does not change at a crossing of faces.
- Auroras hang over the rounded surface, between 320 and 560 blocks. The aurora condition of the pack works over the whole cube: a snowy biome is a cold cell of the planet. The aurora draws from inside and from above its band, and in front of the ground below orbit. It ends at the planet mesh (untested).
- The shadow pass of Iris draws first and scopes only the old primary manager. `SeamFaces.beginShadowScope` puts the new manager in the shadow state at the swap, so a face does not flash bright at the handoff.
- The last frame that the pack reprojects is carried into the frame of the space region at the handoff.

#### Other mods and pre-generation

- **Xaero.** In space, the minimap and the world map show the player at a fixed point, past the middle of the row of faces and 512 blocks out from the strips (`XaeroSpace`). The minimap is blank there, and the world map pans to the faces.
- **Voxygen.** Voxygen scans a circle around the player's chunk in storage. In space that circle is empty space. `EngineManagerMixin` moves the center to the chunk of the face under the player when the player is in space. `TargetMixin` makes a chunk outside the six face regions count as out of range. The chunks of a tile that touches a face are still made. The count of remaining chunks that Voxygen shows includes the chunks outside the faces (untested in game).
- **Loading screen.** The cube turns to the next face when 95% of the cells of a face are full. The pre-generation marks a column full when it is ready, because a chunk can unload between two polls of the client. The client logs the cells that are not full at each turn (`visualizer leaves it`).

#### Open points

- Voxy with an Iris pack: the pack does its own light, so the edge light blend is off.
- Entities and particles use the lightmap of the camera face, and the vanilla fog color is not per face.
- Culling per chunk across a face boundary.
- Other dimensions can get space later.

## Phases

1. Movement core: crossing, edge gravity, server sync, camera.
2. Terrain and decoration: correct density mapping, oceans, vanilla feature placement.
3. Seams: block updates, falling blocks, fluids and plant support across a seam.
4. Light across seams.
5. Mobs: spawn, move, and path-find on all faces.

## Not in version 1

- Structures that fold across an edge in every case: the corner past two edges is dropped, and strongholds, monuments and mansions do not cross an edge.

## Verification

- Terrain: run the dev server, load the cube chunks, and make images from the region files.
- Movement and block behavior: play-test in the Prism instance `cubeworld`.
