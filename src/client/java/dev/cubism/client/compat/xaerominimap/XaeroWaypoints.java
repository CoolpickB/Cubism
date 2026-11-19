package dev.cubism.client.compat.xaerominimap;

import dev.cubism.client.SeamClient;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import xaero.common.minimap.waypoints.Waypoint;

/**
 * Xaero's Minimap keeps the storage position of a waypoint. For a waypoint on another face, that position lies far out
 * beside the face of the camera, so the marker showed in the sky and a teleport went to the wrong place.
 */
public final class XaeroWaypoints {
	private XaeroWaypoints() {}

	/**
	 * The block where the waypoint shows for the camera: its block moved into the frame of the camera face, as the
	 * renderer folds that face. Null for a waypoint on the camera face or off the cube.
	 */
	public static @Nullable BlockPos shown(Waypoint waypoint) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null) {
			return null;
		}
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		int cameraFace = SeamInteraction.regionFace(layout, camera.x, camera.z);
		int face = SeamInteraction.regionFace(layout, waypoint.getX(), waypoint.getZ());
		if (cameraFace < 0 || face < 0 || face == cameraFace) {
			return null;
		}
		return BlockPos.containing(SeamInteraction.move(layout.motion(face, cameraFace), center(waypoint, camera.y)));
	}

	/** The block of the waypoint in cube coordinates, which commands read. Null off the cube. */
	public static @Nullable BlockPos cube(Waypoint waypoint) {
		SeamLayout layout = SeamClient.layout;
		Minecraft minecraft = Minecraft.getInstance();
		if (layout == null || minecraft.player == null) {
			return null;
		}
		Vec3 cube = layout.storageToCube(center(waypoint, minecraft.player.getY()));
		return cube == null ? null : BlockPos.containing(cube);
	}

	/** A waypoint without a height is at the height of the viewer above its own face, as Xaero draws it. */
	private static Vec3 center(Waypoint waypoint, double heightIfUnknown) {
		double y = waypoint.isYIncluded() ? waypoint.getY() + 0.5 : Math.floor(heightIfUnknown) + 0.5;
		return new Vec3(waypoint.getX() + 0.5, y, waypoint.getZ() + 0.5);
	}
}
