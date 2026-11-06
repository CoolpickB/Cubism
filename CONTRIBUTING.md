# Contributing to Cube World

We accept pull requests, mostly for support of other mods and shader packs. Read [GOALS.md](GOALS.md) first. It gives the core model of the cube that every system obeys.

## Report a bug

Open an issue with the **Bug report** template. Attach `logs/latest.log` and a screenshot. Many problems occur only near an edge, so give the F3 position.

## How the cube is stored

Each face is stored upright, in its own region along +X. A face is the pyramid wedge `max(|x|, |z|) <= y - centerY` of its region. As a result, each face is a vanilla world in which the local up is world up. `SeamLayout` changes storage positions to cube positions and back.

Most mod conflicts come from this layout. A mod that reads or draws by storage position gets six wedges in a row, not a cube. The diagonal between two wedges is a seam. Rendering, light, block updates and entities cross a seam with a 90 degree turn.

## Add support for a mod

Each integration has its own package and its own mixin config:

| Part | Location |
|---|---|
| Mixins | `src/client/java/dev/cubeworld/client/compat/<mod id>/mixin/` |
| Helper classes | `src/client/java/dev/cubeworld/client/compat/<mod id>/` |
| Mixin config | `src/client/resources/cubeworld.compat.<mod id>.mixins.json` |

`CompatMixinPlugin` applies a config only when the mod with that id is loaded. It takes the id from the package name, so the package name must be the exact Fabric mod id.

To add an integration:

1. Make the two packages for the mod id.
2. Copy `cubeworld.compat.voxy.mixins.json` to a new file for the mod. Change `package` and the list of mixins.
3. Add the new config to `mixins` in `src/main/resources/fabric.mod.json`, with `"environment": "client"`.
4. Add the mod to `build.gradle` as `clientCompileOnly` from the Modrinth Maven. Put its version in `gradle.properties`.

Obey these rules:

- Do not refer to a class in a `mixin` package from outside that package. Mixin does not permit it. Put shared code in the helper package. Accessor interfaces are the exception.
- Load a class that refers to the classes of another mod only when that mod is loaded.
- Use `"defaultRequire": 1` in the config. If an injection point does not match, the game crashes when the target class loads. Thus a new version of the mod that changes the target shows at once.

## Add support for a shader pack

`IrisFaceUniforms` gives every shader pack the turned matrices of each folded face. Without more work, a pack draws the faces correctly but lights them with a tilted sun.

`CubeShaderPatch` edits the source of Complementary Unbound r5.9.3 as Iris reads it. It adds the sun, sky and clouds of the cube. Each edit replaces an exact piece of text. If a pack does not contain that text, the edit does nothing.

To support another pack:

1. Find the files of the pack that compute the sun angle, the up direction, fog and clouds.
2. Add edits for those files in `CubeShaderPatch.patch`. Use the uniforms and helper functions that the Complementary edits use.
3. Make sure that the Complementary edits do not match the new pack by accident.

Give the pack name and version in the pull request. Add screenshots of an edge at day and at night.

## Test

Do a test in game before you open a pull request. Look at an edge, walk over it, and look at the faces across it. Most bugs show at the seams, not in the middle of a face.

Commit messages are plain sentences that tell what changed and why.
