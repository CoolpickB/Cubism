package dev.cubism.worldgen.structure;

import net.minecraft.core.Holder;
import dev.cubism.cube.CubePlanetMath;
import dev.cubism.cube.SeamLayout;
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
import java.util.WeakHashMap;

/**
 * How far from the edge of a face a structure must start so that it lies on one face. A structure that crosses an
 * edge as one block cannot be folded without a cut: a piece that crosses the edge row lies on both sides of it. Such
 * a structure does not generate near an edge. The start chunks of its placement are squeezed toward the center of
 * their face, so that every start lies at least the margin from the edges and no start is lost. A start that still
 * does not fit, because the structure reaches deep below the surface where the wedge is narrower, tries again further
 * inward (see {@link #inward}).
 *
 * <p>Jigsaw structures that grow over the surface (villages), mineshafts, Nether fortresses and strongholds have no margin: the fold or
 * their own placement handles the edge. The ancient city has none either. It lies 90 to 150 blocks down (see
 * JigsawStructureMixin), where the wedge of a face is much narrower than the surface, so moving every cell inward would
 * put several times as many cities in that narrow space as the cells hold. Its passages fold across the diagonal and its
 * rooms stay whole (see {@link WholeRooms}). A city whose center piece is not whole in its wedge does not generate (see
 * {@link #fits}).
 */
public final class StructureMargins {
	public static final int NONE = -1;
	private static final int DEFAULT = 40;
	private static final int MONUMENT = 45;
	private static final int MANSION = 86;

	private static final Map<StructurePlacement, Integer> MARGINS = Collections.synchronizedMap(new WeakHashMap<>());
	/** The cube of each structure state of a cube generator. The levels of a world share placements but not the cube. */
	private static final Map<ChunkGeneratorStructureState, SeamLayout> CUBE_STATES = Collections.synchronizedMap(new WeakHashMap<>());

	private StructureMargins() {
	}

	/** The cube of a structure state, or null for the state of a generator without a cube. */
	public static @Nullable SeamLayout layout(ChunkGeneratorStructureState state) {
		return CUBE_STATES.get(state);
	}

	public static void register(ChunkGeneratorStructureState state, SeamLayout layout) {
		CUBE_STATES.put(state, layout);
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
		if (type == StructureType.JIGSAW || type == StructureType.MINESHAFT || type == StructureType.FORTRESS || type == StructureType.STRONGHOLD
			|| type == StructureType.BURIED_TREASURE) {
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

	/**
	 * Half the width of a face where its structures stand. In the Nether, the face widens from the floor to the roof, and
	 * a structure stands in the cave, so this is the width halfway up the cave.
	 */
	public static int faceReach(SeamLayout layout) {
		return layout.nether ? layout.radius + layout.openHeight / 2 : layout.radius;
	}

	/**
	 * The chunk, moved toward the center of its face so that the whole region of the face maps onto the square that lies
	 * {@code margin} blocks inside the edges at {@link #faceReach}. A uniform squeeze keeps the starts of a region spread out: moving only the
	 * starts near an edge would put them all on the line at the margin.
	 */
	public static ChunkPos squeeze(SeamLayout layout, int margin, ChunkPos chunk) {
		int centerX = chunk.getMiddleBlockX();
		int centerZ = chunk.getMiddleBlockZ();
		int reach = faceReach(layout);
		if (!layout.inRegion(centerX, centerZ) || margin >= reach) {
			return chunk;
		}
		int faceX = layout.offsetX(layout.faceIndexAt(centerX));
		double scale = (double) (reach - margin) / (layout.spacing / 2);
		int x = Math.floorDiv(faceX + (int) Math.round((centerX - faceX) * scale), 16);
		int z = Math.floorDiv((int) Math.round(centerZ * scale), 16);
		return x == chunk.x() && z == chunk.z() ? chunk : new ChunkPos(x, z);
	}

	/** How far, in blocks, {@link #squeeze} can move a start chunk. */
	public static int maxSqueeze(SeamLayout layout, int margin) {
		return Math.max(0, layout.spacing / 2 - (faceReach(layout) - margin));
	}

	/** How many chunks a start that does not fit can move inward to try again. */
	public static final int MAX_INWARD = 4;

	/**
	 * The chunk moved {@code steps} chunks toward the center of its face, on each axis where the start {@code box}
	 * leaves its wedge or the square of its face.
	 */
	public static ChunkPos inward(SeamLayout layout, BoundingBox box, ChunkPos chunk, int steps) {
		int faceX = layout.offsetX(layout.faceIndexAt(chunk.getMiddleBlockX()));
		// The Nether wedge is narrowest at the bottom of the box, and the overworld wedge also at the edges of the face.
		int width = layout.nether ? box.minY() - layout.centerY : Math.min(layout.radius, box.minY() - layout.centerY);
		int dx = box.maxX() - faceX > width ? -1 : box.minX() - faceX < -width ? 1 : 0;
		int dz = box.maxZ() > width ? -1 : box.minZ() < -width ? 1 : 0;
		return new ChunkPos(chunk.x() + dx * steps, chunk.z() + dz * steps);
	}

	/** The top of a mineshaft, as vanilla keeps it: 10 blocks below sea level. */
	private static final int MINESHAFT_TOP = CubePlanetMath.TOP_SURFACE_Y - 11;

	/**
	 * Makes a generated start fit where the layout can show it, and returns false when it cannot. A mineshaft is lifted
	 * until its first room is in the wedge: vanilla puts it anywhere from 10 to 190 blocks down, and the wedge of a
	 * face is narrow down there, so nearly every mineshaft would start outside it. A stronghold is lifted until all of
	 * it is in the wedge, so that its portal room is never cut off. Otherwise this is {@link #fits}.
	 */
	public static boolean settle(SeamLayout layout, Holder<Structure> structure, StructureStart start) {
		StructureType<?> type = structure.value().type();
		if (type != StructureType.MINESHAFT && type != StructureType.STRONGHOLD) {
			return fits(layout, structure, start);
		}
		List<StructurePiece> pieces = start.getPieces();
		BoundingBox whole = StructurePiece.createBoundingBox(pieces.stream());
		BoundingBox room = type == StructureType.MINESHAFT ? pieces.getFirst().getBoundingBox() : whole;
		int top = whole.maxY();
		for (int lift = 0; lift <= Math.max(0, MINESHAFT_TOP - top); lift++) {
			if (WholeRooms.inWedge(layout, new BoundingBox(room.minX(), room.minY() + lift, room.minZ(), room.maxX(), room.minY() + lift, room.maxZ()))) {
				if (lift > 0) {
					for (StructurePiece piece : pieces) {
						piece.move(0, lift, 0);
					}
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a generated start lies where the layout can show it whole. A structure with a margin must lie in the square
	 * of its face and in its wedge, down to its lowest block: the wedge is narrower than the face by the depth, so the
	 * base of a monument or the basement of an igloo near an edge would be cut off. A Nether wedge is wider than the
	 * square of its floor, so there the wedge alone decides. The ancient city must have its center
	 * piece, with the portal, in the wedge of its face.
	 */
	public static boolean fits(SeamLayout layout, Holder<Structure> structure, StructureStart start) {
		if (structure.is(BuiltinStructures.ANCIENT_CITY)) {
			List<StructurePiece> pieces = start.getPieces();
			return pieces.isEmpty() || WholeRooms.inWedge(layout, pieces.getFirst().getBoundingBox());
		}
		if (of(structure) == NONE) {
			return true;
		}
		int offset = layout.offsetX(layout.faceIndexAt(start.getChunkPos().getMiddleBlockX()));
		BoundingBox box = start.getBoundingBox();
		boolean inSquare = layout.nether || box.minX() >= offset - layout.radius && box.maxX() <= offset + layout.radius
			&& box.minZ() >= -layout.radius && box.maxZ() <= layout.radius;
		return inSquare && WholeRooms.inWedge(layout, box);
	}
}
