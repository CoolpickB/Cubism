package dev.cubeworld.client;

import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

public final class SeamClient {
	/** True when Sodium draws the terrain. Sodium replaces the vanilla section renderer that the seam renderer hooks. */
	public static final boolean SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
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
	 * and its motion would put its positions inside the cube, so it gets none either.
	 */
	public static int @Nullable [] motionToCamera(double x, double z) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null) {
			return null;
		}
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		int[] m = SeamInteraction.regionMotion(layout, x, z, camera.x, camera.z);
		return m == null || m[4] == -1 ? null : m;
	}

	/**
	 * True when a chunk belongs in the seam store, not in the vanilla store: it is outside the square around the
	 * center, or in the face region of another face. A large view distance makes the square reach into a neighbor
	 * region, and vanilla would draw those chunks flat at their storage position.
	 */
	public static boolean isSeamChunk(int chunkX, int chunkZ, int centerX, int centerZ, int radius) {
		SeamLayout layout = SeamClient.layout;
		return layout != null && (Math.abs(chunkX - centerX) > radius || Math.abs(chunkZ - centerZ) > radius
			|| layout.faceIndexAt(SectionPos.sectionToBlockCoord(chunkX, 8)) != layout.faceIndexAt(SectionPos.sectionToBlockCoord(centerX, 8)));
	}
}
