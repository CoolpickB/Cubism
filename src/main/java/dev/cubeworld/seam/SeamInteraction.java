package dev.cubeworld.seam;

import com.mojang.math.OctahedralGroup;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3i;
import org.jspecify.annotations.Nullable;

/**
 * Lets players and mobs act on what they see across a seam. For blocks, the raycast runs in the region of the player
 * and reads each block outside the wedge from the face that owns it. The hit then moves to the owner position, so
 * that breaking, placing and opening act on the real block, and the server checks the reach in the region of the
 * player. For entities, positions move between face regions with the same rigid motion as the seam rendering.
 */
public final class SeamInteraction {
	private SeamInteraction() {}

	/**
	 * The vanilla raycast, except that a block outside the wedge is tested in the frame of its owner face: the ray
	 * moves there with the face motion, and the hit comes back. So the shape of the block has the orientation that
	 * the seam rendering shows.
	 */
	public static BlockHitResult clip(Level level, SeamLayout layout, ClipContext context) {
		int[] owner = new int[3];
		return BlockGetter.traverseBlocks(context.getFrom(), context.getTo(), context, (c, pos) -> {
			if (layout.insideWedge(pos.getX(), pos.getY(), pos.getZ()) || !layout.ownerStorage(pos.getX(), pos.getY(), pos.getZ(), owner)) {
				return clipBlock(level, c, c.getFrom(), c.getTo(), pos);
			}
			int face = layout.faceIndexAt(pos.getX());
			int ownerFace = layout.faceIndexAt(owner[0]);
			int[] there = layout.motion(face, ownerFace);
			BlockHitResult hit = clipBlock(level, c, move(there, c.getFrom()), move(there, c.getTo()), new BlockPos(owner[0], owner[1], owner[2]));
			if (hit == null) {
				return null;
			}
			int[] back = layout.motion(ownerFace, face);
			Vec3 normal = rotate(back, Vec3.atLowerCornerOf(hit.getDirection().getUnitVec3i()));
			return new BlockHitResult(move(back, hit.getLocation()), Direction.getApproximateNearest(normal.x, normal.y, normal.z), pos.immutable(), hit.isInside());
		}, c -> {
			Vec3 delta = c.getFrom().subtract(c.getTo());
			return BlockHitResult.miss(c.getTo(), Direction.getApproximateNearest(delta.x, delta.y, delta.z), BlockPos.containing(c.getTo()));
		});
	}

	/** The per-block step of the vanilla raycast in {@link BlockGetter#clip}, for a ray from {@code from} to {@code to}. */
	private static @Nullable BlockHitResult clipBlock(Level level, ClipContext context, Vec3 from, Vec3 to, BlockPos pos) {
		BlockState blockState = level.getBlockState(pos);
		FluidState fluidState = level.getFluidState(pos);
		BlockHitResult blockResult = level.clipWithInteractionOverride(from, to, pos, context.getBlockShape(blockState, level, pos), blockState);
		BlockHitResult liquidResult = context.getFluidShape(fluidState, level, pos).clip(from, to, pos);
		double blockDistanceSquared = blockResult == null ? Double.MAX_VALUE : from.distanceToSqr(blockResult.getLocation());
		double liquidDistanceSquared = liquidResult == null ? Double.MAX_VALUE : from.distanceToSqr(liquidResult.getLocation());
		return blockDistanceSquared <= liquidDistanceSquared ? blockResult : liquidResult;
	}

	public static Vec3 move(int[] m, Vec3 v) {
		return rotate(m, v).add(m[9], m[10], m[11]);
	}

	/** Moves a block hit outside its wedge to the owner position, with the same rigid motion as the rendering. */
	public static HitResult toOwner(SeamLayout layout, HitResult hit) {
		if (!(hit instanceof BlockHitResult block) || hit.getType() == HitResult.Type.MISS) {
			return hit;
		}
		BlockPos pos = block.getBlockPos();
		int[] owner = new int[3];
		if (layout.insideWedge(pos.getX(), pos.getY(), pos.getZ()) || !layout.ownerStorage(pos.getX(), pos.getY(), pos.getZ(), owner)) {
			return hit;
		}
		int[] m = layout.motion(layout.faceIndexAt(pos.getX()), layout.faceIndexAt(owner[0]));
		Vec3 location = move(m, block.getLocation());
		Vec3 normal = rotate(m, Vec3.atLowerCornerOf(block.getDirection().getUnitVec3i()));
		Direction direction = Direction.getApproximateNearest(normal.x, normal.y, normal.z);
		return new BlockHitResult(location, direction, new BlockPos(owner[0], owner[1], owner[2]), block.isInside());
	}

	/**
	 * Where a block of another face region shows in the region of {@code storageX}: outside the wedge of that
	 * region, at the place that the seam rendering draws it. Returns null when both are in the same region.
	 */
	public static @Nullable BlockPos inRegionOf(SeamLayout layout, BlockPos pos, int storageX) {
		if (!layout.inRegion(pos.getX(), pos.getZ())) {
			return null;
		}
		int from = layout.faceIndexAt(pos.getX());
		int to = layout.faceIndexAt(storageX);
		if (from == to) {
			return null;
		}
		int[] p = new int[3];
		layout.toCube(from, pos.getX(), pos.getY(), pos.getZ(), p);
		layout.toStorage(to, p[0], p[1], p[2], p);
		return new BlockPos(p[0], p[1], p[2]);
	}

	/** The position of the real block for a storage position: itself inside its wedge, else the owner position. */
	public static BlockPos owner(SeamLayout layout, BlockPos pos) {
		int[] owner = new int[3];
		if (layout.insideWedge(pos.getX(), pos.getY(), pos.getZ()) || !layout.ownerStorage(pos.getX(), pos.getY(), pos.getZ(), owner)) {
			return pos;
		}
		return new BlockPos(owner[0], owner[1], owner[2]);
	}

	/** The block rotation of the face motion between two faces, for shapes that turn about the block center. */
	public static OctahedralGroup blockRotation(SeamLayout layout, int fromFace, int toFace) {
		int[] m = layout.motion(fromFace, toFace);
		for (OctahedralGroup group : OctahedralGroup.values()) {
			Vector3i x = group.rotate(new Vector3i(1, 0, 0));
			Vector3i y = group.rotate(new Vector3i(0, 1, 0));
			Vector3i z = group.rotate(new Vector3i(0, 0, 1));
			if (x.x == m[0] && x.y == m[3] && x.z == m[6] && y.x == m[1] && y.y == m[4] && y.z == m[7] && z.x == m[2] && z.y == m[5] && z.z == m[8]) {
				return group;
			}
		}
		throw new IllegalStateException("The face motion is not a block rotation");
	}

	/** The motion from the face region of the first position to that of the second, or null when they share a region or one is in none. */
	public static int @Nullable [] regionMotion(SeamLayout layout, double fromX, double fromZ, double toX, double toZ) {
		int from = regionFace(layout, fromX, fromZ);
		int to = regionFace(layout, toX, toZ);
		return from < 0 || to < 0 || from == to ? null : layout.motion(from, to);
	}

	/** Where a position of one face region shows in the region of {@code (atX, atZ)}, across the seam. */
	public static Vec3 intoRegionOf(SeamLayout layout, Vec3 pos, double atX, double atZ) {
		int[] m = regionMotion(layout, pos.x, pos.z, atX, atZ);
		return m == null ? pos : move(m, pos);
	}

	/** Where a box of one face region shows in the region of {@code (atX, atZ)}. The center gives the region of the box. */
	public static AABB intoRegionOf(SeamLayout layout, AABB box, double atX, double atZ) {
		Vec3 center = box.getCenter();
		int[] m = regionMotion(layout, center.x, center.z, atX, atZ);
		return m == null ? box : new AABB(move(m, box.getMinPosition()), move(m, box.getMaxPosition()));
	}

	/** A direction of the region of {@code (fromX, fromZ)} turned into the region of {@code (toX, toZ)}. */
	public static Vec3 turnIntoRegionOf(SeamLayout layout, Vec3 direction, double fromX, double fromZ, double toX, double toZ) {
		int[] m = regionMotion(layout, fromX, fromZ, toX, toZ);
		return m == null ? direction : rotate(m, direction);
	}

	public static int regionFace(SeamLayout layout, double x, double z) {
		int bx = Mth.floor(x);
		return layout.inRegion(bx, Mth.floor(z)) ? layout.faceIndexAt(bx) : -1;
	}

	public static Vec3 rotate(int[] m, Vec3 v) {
		return new Vec3(
			m[0] * v.x + m[1] * v.y + m[2] * v.z,
			m[3] * v.x + m[4] * v.y + m[5] * v.z,
			m[6] * v.x + m[7] * v.y + m[8] * v.z
		);
	}
}
