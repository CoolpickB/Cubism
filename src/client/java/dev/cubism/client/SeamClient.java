package dev.cubism.client;

import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

public final class SeamClient {
	/** True when Sodium draws the terrain. Sodium replaces the vanilla section renderer that the seam renderer hooks. */
	public static final boolean SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
	public static final boolean IRIS = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris");
	public static final boolean VOXY = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("voxy");
	public static final boolean ENTITY_CULLING = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("entityculling");
	/** The seam layout of the level the client plays in, or null when the level has no seams. */
	public static volatile @Nullable SeamLayout layout;
	/**
	 * The turn from the frame of the face region of the camera to the up that the camera shows: the air frame in
	 * flight, and the turn that eases out after a crossing. Set by the camera each frame. Render thread only.
	 */
	public static final Quaternionf lightFrame = new Quaternionf();
	/** The chunk center and view radius that the server last gave the client chunk cache. */
	public static volatile int viewCenterX;
	public static volatile int viewCenterZ;
	public static volatile int serverViewRadius;

	private SeamClient() {}

	/**
	 * The motion that moves a position in another face region to where it shows from the camera, or null when the
	 * position is in the region of the camera or in no region, or on an opposite face out of view range.
	 */
	public static int @Nullable [] motionToCamera(double x, double z) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null) {
			return null;
		}
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		int[] m = SeamInteraction.regionMotion(layout, x, z, camera.x, camera.z);
		return m == null || m[4] == -1 && !seesOpposite(layout, camera, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0) ? null : m;
	}

	/**
	 * True when the opposite face of the camera region can be within {@code reach} blocks of the camera. The opposite
	 * wedge meets the camera face only at the center of the cube, so from the surface of a large cube it is far out of
	 * range, and near the core it is close. Past the wedge (a Steadfast lock) and in space every face can be in view.
	 */
	public static boolean seesOpposite(SeamLayout layout, Vec3 camera, double reach) {
		int face = SeamInteraction.regionFace(layout, camera.x, camera.z);
		if (face == SeamLayout.SPACE || layout.crossingFace(camera.x, camera.y, camera.z, 0.0) >= 0) {
			return true;
		}
		if (face < 0) {
			return false;
		}
		Vec3 c = layout.cubeOffset(face, camera.x, camera.y, camera.z);
		Vec3 down = layout.faceDown(face);
		double up = -(down.x * c.x + down.y * c.y + down.z * c.z);
		double sides = (down.x == 0.0 ? Math.abs(c.x) : 0.0) + (down.y == 0.0 ? Math.abs(c.y) : 0.0) + (down.z == 0.0 ? Math.abs(c.z) : 0.0);
		// The opposite wedge is a cone from the center. From inside the camera wedge, its nearest point is the center, or a
		// point on its edge ray toward the camera's corner when the camera is closer to that corner than to the center.
		double along = (sides - up) / Math.sqrt(3.0);
		double distanceSqr = c.lengthSqr() - (along > 0.0 ? along * along : 0.0);
		return distanceSqr <= reach * reach;
	}

	/** True when the camera is in space, where the faces are in view from further away than the chunks reach. */
	public static boolean faceFarView(SeamLayout layout, Vec3 camera) {
		return SeamInteraction.regionFace(layout, camera.x, camera.z) == SeamLayout.SPACE;
	}

	/**
	 * True when the camera is in space and the planet hides the whole face, so nothing of it needs a draw or a trace. In
	 * space every face is in the frustum of the camera, but the far side of the planet cannot be seen.
	 */
	public static boolean faceHidden(SeamLayout layout, Vec3 camera, int face) {
		if (face == SeamLayout.SPACE || SeamInteraction.regionFace(layout, camera.x, camera.z) != SeamLayout.SPACE) {
			return false;
		}
		return !layout.faceCanBeSeen(face, layout.cubeOffset(SeamLayout.SPACE, camera.x, camera.y, camera.z));
	}

	/**
	 * True when a chunk belongs in the seam store, not in the vanilla store: it is outside the square around the
	 * center, or in the face region of another face. A large view distance makes the square reach into a neighbor
	 * region, and vanilla would draw those chunks flat at their storage position.
	 */
	public static boolean isSeamChunk(int chunkX, int chunkZ, int centerX, int centerZ, int radius) {
		SeamLayout layout = SeamClient.layout;
		return layout != null && (Math.abs(chunkX - centerX) > radius || Math.abs(chunkZ - centerZ) > radius
			|| region(layout, chunkX, chunkZ) != region(layout, centerX, centerZ));
	}

	/** The region of the middle of a chunk; a column in no region counts with the face strip nearest to it. */
	private static int region(SeamLayout layout, int chunkX, int chunkZ) {
		int x = SectionPos.sectionToBlockCoord(chunkX, 8);
		int region = layout.regionAt(x, SectionPos.sectionToBlockCoord(chunkZ, 8));
		return region < 0 ? layout.faceIndexAt(x) : region;
	}
}
