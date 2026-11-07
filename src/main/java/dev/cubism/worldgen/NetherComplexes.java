package dev.cubism.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.AbstractSpreadingStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Fortresses and bastions of a cube Nether. With the vanilla spread, a small cube Nether gets one or two in all and
 * often no fortress, which a finite world cannot do without. Here each of them is placed like a stronghold: one per face
 * on faces from the seed, as many as there are strongholds, and no face holds both.
 */
public final class NetherComplexes {
	/** The largest distance of a start from the middle of its face, as a share of the radius of the core. */
	private static final double SPREAD = 1.0 / 3.0;
	private static final int FORTRESS_SALT = 30084232;
	private static final int BASTION_SALT = 30084233;

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

	public static List<ChunkPos> positions(SeamLayout nether, long seed, StructureSet set) {
		Random random = new Random(seed * 0x9E3779B97F4A7C15L + 0x4E37);
		List<Integer> faces = new ArrayList<>();
		for (int face = 0; face < SeamLayout.FACES.length; face++) {
			faces.add(face);
		}
		Collections.shuffle(faces, random);
		int count = StrongholdPositions.count(nether.overworldRadius() / 8);
		boolean fortress = set.structures().get(0).structure().is(BuiltinStructures.FORTRESS);
		int spread = (int) (nether.radius * SPREAD);
		List<ChunkPos> positions = new ArrayList<>();
		for (int i = 0; i < 2 * count; i++) {
			// Both kinds draw every position, so the faces of one kind do not move with the count of the other.
			int x = Math.floorDiv(nether.offsetX(faces.get(i)) + (int) Math.round((random.nextDouble() * 2 - 1) * spread), 16);
			int z = Math.floorDiv((int) Math.round((random.nextDouble() * 2 - 1) * spread), 16);
			if (i < count == fortress) {
				positions.add(new ChunkPos(x, z));
			}
		}
		return positions;
	}
}
