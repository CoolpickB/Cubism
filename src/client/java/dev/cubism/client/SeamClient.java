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
	 * position is in the region of the camera or in no region. The opposite face shares no seam with the camera face,
	 * and gets none either, unless the camera is past the wedge of its region (see {@link #seesOpposite}).
	 */
	public static int @Nullable [] motionToCamera(double x, double z) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null) {
			return null;
		}
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		int[] m = SeamInteraction.regionMotion(layout, x, z, camera.x, camera.z);
		return m == null || m[4] == -1 && !seesOpposite(layout, camera) ? null : m;
	}

	/**
	 * True when the camera is past the wedge of its region, so the opposite face of the region is in view. Only a
	 * Steadfast lock keeps a camera there: without it the camera crosses to the face whose air it is in. From space
	 * every face can be in view.
	 */
	public static boolean seesOpposite(SeamLayout layout, Vec3 camera) {
		return layout.crossingFace(camera.x, camera.y, camera.z, 0.0) >= 0 || SeamInteraction.regionFace(layout, camera.x, camera.z) == SeamLayout.SPACE;
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
