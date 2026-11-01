package dev.cubeworld.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which structures a seed is likely to have, without generating terrain, for the Customize screen. A structure counts
 * when a chunk of a face is a placement chunk of its set, as the generator works it out, and the surface biome there
 * is one that the structure allows. Terrain rules, such as the depth of water for a monument, are not checked, so a
 * structure here can still fail to generate. Strongholds are not here: every world has them.
 */
public final class CubeStructureMap {
	private CubeStructureMap() {
	}

	/** The structure sets that a cube world can have, in the order of the registry. */
	public static List<Holder<StructureSet>> candidates(HolderLookup<StructureSet> sets, MultiNoiseBiomeSource biomes) {
		Set<Holder<Biome>> possible = biomes.possibleBiomes();
		return sets.listElements()
			.filter(set -> set.value().placement() instanceof RandomSpreadStructurePlacement)
			.filter(set -> set.value().structures().stream().anyMatch(entry -> entry.structure().value().biomes().stream().anyMatch(possible::contains)))
			.map(set -> (Holder<StructureSet>) set)
			.toList();
	}

	/**
	 * The keys of the candidate sets that the seed has. With {@code wanted}, the scan ends as soon as every wanted set
	 * is found, and the result holds no more than what the scan found until then.
	 */
	public static Set<ResourceKey<StructureSet>> find(
		CubeBiomeMap.Sampler sampler,
		CubeSettings cube,
		HolderLookup<StructureSet> sets,
		MultiNoiseBiomeSource biomes,
		List<Holder<StructureSet>> candidates,
		@Nullable Set<ResourceKey<StructureSet>> wanted
	) {
		Set<ResourceKey<StructureSet>> found = new HashSet<>();
		List<Holder<StructureSet>> open = candidates.stream().filter(set -> wanted == null || set.unwrapKey().map(wanted::contains).orElse(false)).toList();
		scan(sampler, cube, sets, biomes, open, (set, point) -> {
			set.unwrapKey().ifPresent(found::add);
			return true;
		});
		return found;
	}

	/**
	 * The places of one structure set on the surface of the cube, as points of a cube with a radius of 1 and its center at
	 * the origin. Every place that {@link #find} would count is here.
	 */
	public static List<Vector3f> positions(
		CubeBiomeMap.Sampler sampler, CubeSettings cube, HolderLookup<StructureSet> sets, MultiNoiseBiomeSource biomes, Holder<StructureSet> set
	) {
		List<Vector3f> points = new ArrayList<>();
		float radius = new SeamLayout(cube.diameterChunks()).radius;
		scan(sampler, cube, sets, biomes, List.of(set), (hit, point) -> {
			points.add(new Vector3f(point[0], point[1], point[2]).div(radius));
			return false;
		});
		return points;
	}

	/** Called for a chunk where a set can place a structure. Returns true when the set needs no more chunks. */
	private interface Hit {
		boolean accept(Holder<StructureSet> set, int[] cubePoint);
	}

	private static void scan(
		CubeBiomeMap.Sampler sampler, CubeSettings cube, HolderLookup<StructureSet> sets, MultiNoiseBiomeSource biomes,
		List<Holder<StructureSet>> open, Hit hit
	) {
		ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForNormal(sampler.randomState(), sampler.seed(), ChunkPos.ZERO, biomes, sets);
		SeamLayout layout = new SeamLayout(cube.diameterChunks());
		Set<Holder<StructureSet>> remaining = new HashSet<>(open);
		int r = layout.radius;
		int[] cube3 = new int[3];
		for (int face = 0; face < SeamLayout.FACES.length && !remaining.isEmpty(); face++) {
			int center = layout.offsetX(face);
			for (int chunkX = Math.floorDiv(center - r, 16); chunkX <= Math.floorDiv(center + r, 16); chunkX++) {
				for (int chunkZ = Math.floorDiv(-r, 16); chunkZ <= Math.floorDiv(r, 16); chunkZ++) {
					int x = chunkX * 16 + 8;
					int z = chunkZ * 16 + 8;
					if (Math.abs(x - center) > r || Math.abs(z) > r) {
						continue;
					}
					Holder<Biome> biome = null;
					for (Holder<StructureSet> set : remaining.toArray(new Holder[0])) {
						if (!set.value().placement().isStructureChunk(state, chunkX, chunkZ)) {
							continue;
						}
						if (biome == null) {
							layout.toCube(face, x, layout.centerY + r, z, cube3);
							biome = sampler.surfaceBiomeAt(cube3[0], cube3[1], cube3[2]);
						}
						Holder<Biome> here = biome;
						if (set.value().structures().stream().anyMatch(entry -> entry.structure().value().biomes().contains(here)) && hit.accept(set, cube3)) {
							remaining.remove(set);
						}
					}
				}
			}
		}
	}
}
