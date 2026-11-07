package dev.cubism.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.AbstractSpreadingStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Fortresses and bastions of a cube Nether. With the vanilla spread, a small cube Nether gets one or two in all and
 * often no fortress, which a finite world cannot do without. Here each of them is placed like a stronghold: one per face
 * on faces from the seed, as many as there are strongholds, and no face holds both.
 */
public final class NetherComplexes {
	private static final int FORTRESS_SALT = 30084232;
	private static final int BASTION_SALT = 30084233;
	/** The height at which vanilla tests the biome of a bastion start. A fortress can start in every Nether biome. */
	private static final int BIOME_Y = 33;
	private static final int[][] BIOME_SAMPLES = {{0, 0}, {24, 24}, {24, -24}, {-24, 24}, {-24, -24}};

	private NetherComplexes() {
	}

	/**
	 * The vanilla set of fortresses and bastions, split into one set for each. A set with fixed positions is a set of
	 * concentric rings, and {@link #positions} gives the positions in place of the rings.
	 */
	public static List<Holder<StructureSet>> split(List<Holder<StructureSet>> sets) {
		List<Holder<StructureSet>> result = new ArrayList<>(sets.size() + 1);
		for (Holder<StructureSet> set : sets) {
			if (!set.is(BuiltinStructureSets.NETHER_COMPLEXES)) {
				result.add(set);
				continue;
			}
			for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
				int salt = entry.structure().is(BuiltinStructures.FORTRESS) ? FORTRESS_SALT : BASTION_SALT;
				// Count 0 keeps vanilla from searching for ring positions that are replaced anyway.
				ConcentricRingsStructurePlacement placement = new ConcentricRingsStructurePlacement(
					Vec3i.ZERO, AbstractSpreadingStructurePlacement.FrequencyReductionMethod.DEFAULT, 1.0F, salt, Optional.empty(), 0, 0, 0, HolderSet.empty()
				);
				result.add(Holder.direct(new StructureSet(entry.structure(), placement)));
			}
		}
		return result;
	}

	/** True for a set that {@link #split} made. */
	public static boolean isComplex(StructureSet set) {
		// Vanilla never places a fortress or a bastion by rings.
		return set.structures().size() == 1 && set.placement() instanceof ConcentricRingsStructurePlacement
			&& (set.structures().get(0).structure().is(BuiltinStructures.FORTRESS) || set.structures().get(0).structure().is(BuiltinStructures.BASTION_REMNANT));
	}

	/**
	 * The start chunks of a split set. A start in a biome that the structure cannot use (a bastion in basalt deltas) moves
	 * to the nearest chunk of its face that has one, then to a face that holds neither kind. Some seeds
	 * have no such chunk, so a bastion of a cube Nether generates in every biome (see StructureMixin).
	 */
	public static List<ChunkPos> positions(SeamLayout nether, long seed, StructureSet set, BiomeResolver biomes) {
		Random random = new Random(seed * 0x9E3779B97F4A7C15L + 0x4E37);
		List<Integer> faces = new ArrayList<>();
		for (int face = 0; face < SeamLayout.FACES.length; face++) {
			faces.add(face);
		}
		Collections.shuffle(faces, random);
		int count = StrongholdPositions.count(nether.overworldRadius() / 8);
		Holder<Structure> structure = set.structures().get(0).structure();
		boolean fortress = structure.is(BuiltinStructures.FORTRESS);
		Predicate<ChunkPos> valid = chunk -> {
			// Vanilla tests the biome at the middle of the start piece, which lies up to about 20 blocks from the corner
			// of the chunk in a direction that the rotation of the piece decides.
			for (int[] offset : BIOME_SAMPLES) {
				int x = chunk.getMinBlockX() + offset[0];
				int z = chunk.getMinBlockZ() + offset[1];
				if (!structure.value().biomes().contains(biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(BIOME_Y), QuartPos.fromBlock(z)))) {
					return false;
				}
			}
			return true;
		};
		int reach = StructureMargins.faceReach(nether);
		List<ChunkPos> positions = new ArrayList<>();
		for (int i = 0; i < 2 * count; i++) {
			// Both kinds draw every position, so the faces of one kind do not move with the count of the other.
			int x = Math.floorDiv(nether.offsetX(faces.get(i)) + towardCenter(random, reach), 16);
			int z = Math.floorDiv(towardCenter(random, reach), 16);
			if (i < count == fortress) {
				ChunkPos found = nearestValid(nether, new ChunkPos(x, z), faces.get(i), valid);
				for (int spare = 2 * count; found == null && spare < faces.size(); spare++) {
					found = nearestValid(nether, new ChunkPos(Math.floorDiv(nether.offsetX(faces.get(spare)), 16), 0), faces.get(spare), valid);
				}
				positions.add(found == null ? new ChunkPos(x, z) : found);
			}
		}
		return positions;
	}

	/**
	 * An offset in [-reach, reach]. A start at the reach stands at the edge of its face halfway up the cave and reaches
	 * across the diagonal, and the part past it folds onto the next face. The sum of two even draws makes offsets near the middle more likely than near the
	 * edge, so that fewer structures cross an edge, without a bound that keeps them all in the middle of a large face.
	 */
	private static int towardCenter(Random random, int reach) {
		return (int) Math.round((random.nextDouble() + random.nextDouble() - 1) * reach);
	}

	/** The chunk within {@link StructureMargins#faceReach} of the middle of the face, nearest to {@code start}, that passes {@code valid}, or null. */
	private static @Nullable ChunkPos nearestValid(SeamLayout nether, ChunkPos start, int face, Predicate<ChunkPos> valid) {
		int min = Math.floorDiv(-StructureMargins.faceReach(nether), 16);
		int max = Math.floorDiv(StructureMargins.faceReach(nether) - 1, 16);
		int faceChunk = nether.offsetX(face) / 16;
		ChunkPos best = null;
		long bestDistance = Long.MAX_VALUE;
		for (int dx = min; dx <= max; dx++) {
			for (int z = min; z <= max; z++) {
				ChunkPos chunk = new ChunkPos(faceChunk + dx, z);
				long distance = (long) (chunk.x() - start.x()) * (chunk.x() - start.x()) + (long) (z - start.z()) * (z - start.z());
				if (distance < bestDistance && valid.test(chunk)) {
					best = chunk;
					bestDistance = distance;
				}
			}
		}
		return best;
	}
}
