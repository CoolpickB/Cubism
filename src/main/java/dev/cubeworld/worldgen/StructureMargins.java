package dev.cubeworld.worldgen;

import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * How far from the edge of a face a structure must start so that it lies on one face. A structure that crosses an
 * edge as one block cannot be folded without a cut: a piece that crosses the edge row lies on both sides of it. Such
 * a structure does not generate near an edge. Its start chunk moves inward to the margin instead, so the structure
 * generates whole where the placement of vanilla put its cell, and no start is lost.
 *
 * <p>Jigsaw structures that grow over the surface (villages), mineshafts and strongholds have no margin: the fold or
 * their own placement handles the edge.
 */
public final class StructureMargins {
	public static final int NONE = -1;
	private static final int DEFAULT = 40;
	private static final int MONUMENT = 45;
	private static final int MANSION = 86;
	/**
	 * The ancient city has to keep its center whole, because the portal is in it. The lowest block of the center is
	 * 115 below the surface (the start is at y -27, the anchor is 24 blocks above the foot of the template, and the
	 * surface is at y 64). The wedge of a face is 115 blocks narrower there, on each side. The template is 41 long, and
	 * the anchor is up to 4 blocks off its middle, so the center reaches 25 blocks from the start. Half a chunk more
	 * comes from measuring from the chunk center.
	 */
	private static final int ANCIENT_CITY = CubePlanetMath.TOP_SURFACE_Y + 27 + 24 + 25 + 8;

	/** The layout that the code on this thread places structures for, instead of the active one: the Customize map uses it. */
	private static final ThreadLocal<SeamLayout> FORCED = new ThreadLocal<>();
	private static final Map<StructurePlacement, Integer> MARGINS = Collections.synchronizedMap(new WeakHashMap<>());
	/** The structure states of cube generators. The Nether and the End of a cube world share placements but not the cube. */
	private static final Set<ChunkGeneratorStructureState> CUBE_STATES = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
	private static final ThreadLocal<Boolean> OTHER_GENERATOR = ThreadLocal.withInitial(() -> false);

	private StructureMargins() {
	}

	public static @Nullable SeamLayout layout() {
		if (OTHER_GENERATOR.get()) {
			return null;
		}
		SeamLayout forced = FORCED.get();
		return forced != null ? forced : SeamLayout.active();
	}

	/** Runs {@code action} with the placement of structures worked out for {@code layout}. */
	public static void withLayout(SeamLayout layout, Runnable action) {
		SeamLayout before = FORCED.get();
		FORCED.set(layout);
		try {
			action.run();
		} finally {
			FORCED.set(before);
		}
	}

	public static boolean isCube(ChunkGeneratorStructureState state) {
		return CUBE_STATES.contains(state);
	}

	/** Between this and {@link #leaveOtherGenerator}, the placements do not move: the code works for a generator that is not a cube. */
	public static void enterOtherGenerator() {
		OTHER_GENERATOR.set(true);
	}

	public static void leaveOtherGenerator() {
		OTHER_GENERATOR.set(false);
	}

	public static void register(ChunkGeneratorStructureState state) {
		CUBE_STATES.add(state);
		for (Holder<StructureSet> set : state.possibleStructureSets()) {
			int margin = NONE;
			for (StructureSet.StructureSelectionEntry entry : set.value().structures()) {
				margin = Math.max(margin, of(entry.structure()));
			}
			MARGINS.put(set.value().placement(), margin);
		}
	}

	/** The margin of the placement in blocks, or {@link #NONE}. */
	public static int of(StructurePlacement placement) {
		return MARGINS.getOrDefault(placement, NONE);
	}

	public static int of(Holder<Structure> structure) {
		StructureType<?> type = structure.value().type();
		if (structure.is(BuiltinStructures.ANCIENT_CITY)) {
			return ANCIENT_CITY;
		}
		if (type == StructureType.JIGSAW || type == StructureType.MINESHAFT || type == StructureType.STRONGHOLD || type == StructureType.BURIED_TREASURE) {
			return NONE;
		}
		if (structure.is(BuiltinStructures.OCEAN_MONUMENT)) {
			return MONUMENT;
		}
		if (structure.is(BuiltinStructures.WOODLAND_MANSION)) {
			return MANSION;
		}
		return DEFAULT;
	}

	/** The chunk, moved inward so that its center is {@code margin} blocks from the edges of its face. */
	public static ChunkPos clamp(SeamLayout layout, int margin, ChunkPos chunk) {
		int centerX = chunk.getMiddleBlockX();
		int centerZ = chunk.getMiddleBlockZ();
		if (!layout.inRegion(centerX, centerZ)) {
			return chunk;
		}
		int limit = layout.radius - margin;
		int x = clampAxis(chunk.x(), layout.offsetX(layout.faceIndexAt(centerX)), limit);
		int z = clampAxis(chunk.z(), 0, limit);
		return x == chunk.x() && z == chunk.z() ? chunk : new ChunkPos(x, z);
	}

	private static int clampAxis(int chunk, int center, int limit) {
		int low = Math.ceilDiv(center - limit - 8, 16);
		int high = Math.floorDiv(center + limit - 8, 16);
		return low > high ? chunk : Math.max(low, Math.min(high, chunk));
	}

	/**
	 * Whether a generated start lies where the layout can show it whole. A structure with a margin must lie in the square
	 * of its face. The ancient city must have its center piece in the wedge of its face, down to the lowest block.
	 */
	public static boolean fits(SeamLayout layout, Holder<Structure> structure, StructureStart start) {
		if (structure.is(BuiltinStructures.ANCIENT_CITY)) {
			List<StructurePiece> pieces = start.getPieces();
			return pieces.isEmpty() || insideWedge(layout, pieces.getFirst().getBoundingBox());
		}
		if (of(structure) == NONE) {
			return true;
		}
		int offset = layout.offsetX(layout.faceIndexAt(start.getChunkPos().getMiddleBlockX()));
		BoundingBox box = start.getBoundingBox();
		return box.minX() >= offset - layout.radius && box.maxX() <= offset + layout.radius
			&& box.minZ() >= -layout.radius && box.maxZ() <= layout.radius;
	}

	private static boolean insideWedge(SeamLayout layout, BoundingBox box) {
		for (int y : new int[] {box.minY(), box.maxY()}) {
			for (int x : new int[] {box.minX(), box.maxX()}) {
				for (int z : new int[] {box.minZ(), box.maxZ()}) {
					if (!layout.insideWedge(x, y, z)) {
						return false;
					}
				}
			}
		}
		return true;
	}
}
