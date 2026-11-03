package dev.cubeworld.worldgen;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;

/**
 * Keeps the rooms of an underground jigsaw structure (an ancient city, trial chambers) whole at the diagonal. The fold
 * about the hinge (see {@link SeamFold.Fold#atDepth}) lets each face draw its own side of the diagonal. For a passage
 * that makes a clean corner, because its cross section is the same along its length. For a room it shows a sliver: the
 * part of the room near the diagonal appears on both faces. So a room must lie wholly on one face, and only that face
 * draws it. Passages and small pieces still cross.
 */
public final class WholeRooms {
	/** Pieces this low, such as a chest or a spawner, are no taller than the slivers of the corner. */
	private static final int SMALL_HEIGHT = 4;
	private static final String[] PASSAGES = {
		"trial_chambers/corridor/", "trial_chambers/hallway/", "ancient_city/walls/", "ancient_city/city_center/walls/", "ancient_city/city/entrance/"
	};

	private WholeRooms() {
	}

	/** Every piece taller than a small one is a room, unless it is a single template from a passage pool. */
	public static boolean isRoom(StructurePoolElement element, BoundingBox box) {
		if (box.getYSpan() <= SMALL_HEIGHT) {
			return false;
		}
		if (!(element instanceof SinglePoolElement single)) {
			return true;
		}
		String path = single.getTemplateLocation().getPath();
		if (path.contains("/atrium/")) {
			return true;
		}
		for (String passage : PASSAGES) {
			if (path.startsWith(passage)) {
				return false;
			}
		}
		return true;
	}

	public static boolean isRoom(StructurePiece piece) {
		return piece instanceof PoolElementStructurePiece pool && isRoom(pool.getElement(), piece.getBoundingBox());
	}

	/**
	 * Whether a room of a structure with its hinge at {@code hingeDepth} lies wholly on one face: in the wedge of its own
	 * face, or, folded over the nearest edge, in the wedge of the next face, but not both.
	 */
	public static boolean onOneFace(SeamLayout layout, BoundingBox box, int hingeDepth) {
		int face = layout.faceIndexAt((box.minX() + box.maxX()) / 2);
		int dx = (box.minX() + box.maxX()) / 2 - layout.offsetX(face);
		int dz = (box.minZ() + box.maxZ()) / 2;
		int edge = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? 0 : 1) : (dz > 0 ? 2 : 3);
		BoundingBox folded = layout.fold.get(face, edge).atDepth(hingeDepth).apply(box);
		return inWedge(layout, box) != inWedge(layout, folded);
	}

	/** Whether all of the box lies in the wedge of the face whose region holds it. */
	public static boolean inWedge(SeamLayout layout, BoundingBox box) {
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
