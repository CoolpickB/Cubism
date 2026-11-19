package dev.cubism.client.render;

import dev.cubism.client.SeamClient;
import dev.cubism.cube.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Shades block faces over a rounded edge by their direction to the surface there, not to the face. Vanilla shades each
 * face by its direction in its own face frame, so near the diagonal a top face of one face sits in the same plane as a
 * side face of the next, and the two get different shades. Here the frame of the shade turns with the surface, and the
 * two side shades meet at their mean on the diagonal, where the two faces then agree. On the flat part of a face the
 * shade is the one of vanilla.
 *
 * <p>Vanilla and the Indigo renderer of Fabric both read the shade of a face from the {@link CardinalLighting} of the
 * level, so a block gets its own table.
 */
public final class CubeShade {
	private CubeShade() {}

	/** The shade table for the faces of the block at a position. */
	public static CardinalLighting at(CardinalLighting lighting, BlockPos pos) {
		SeamLayout layout = SeamClient.layout;
		// The blocks turn with the rounding of the terrain, which is tighter than the turn of the pull.
		Vec3 gravity = layout == null ? null : layout.airGravity(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, layout.edgeRounding());
		if (gravity == null) {
			return lighting;
		}
		Vector3f up = new Vector3f((float) -gravity.x, (float) -gravity.y, (float) -gravity.z);
		// Turns a normal into a frame whose up is the surface, the shortest way from the face frame.
		Quaternionf toSurface = new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, up.x, up.y, up.z).conjugate();
		// The tilt is 45 degrees on the diagonal of an edge and more toward a corner.
		float tilt = Mth.clamp((float) Math.acos(Mth.clamp(up.y, -1.0F, 1.0F)) / Mth.HALF_PI * 2.0F, 0.0F, 1.0F);
		float side = (lighting.east() + lighting.north()) / 2.0F;
		float sideX = Mth.lerp(tilt, lighting.east(), side);
		float sideZ = Mth.lerp(tilt, lighting.north(), side);
		return new CardinalLighting(
			shade(lighting, toSurface, Direction.DOWN, sideX, sideZ),
			shade(lighting, toSurface, Direction.UP, sideX, sideZ),
			shade(lighting, toSurface, Direction.NORTH, sideX, sideZ),
			shade(lighting, toSurface, Direction.SOUTH, sideX, sideZ),
			shade(lighting, toSurface, Direction.WEST, sideX, sideZ),
			shade(lighting, toSurface, Direction.EAST, sideX, sideZ)
		);
	}

	private static float shade(CardinalLighting lighting, Quaternionf toSurface, Direction direction, float sideX, float sideZ) {
		Vector3f normal = toSurface.transform(new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ()));
		float above = Math.max(normal.y, 0.0F);
		float below = Math.max(-normal.y, 0.0F);
		return above * above * lighting.up() + below * below * lighting.down() + normal.x * normal.x * sideX + normal.z * normal.z * sideZ;
	}
}
