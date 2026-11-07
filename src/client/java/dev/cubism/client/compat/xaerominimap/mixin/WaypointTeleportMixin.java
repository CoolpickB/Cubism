package dev.cubism.client.compat.xaerominimap.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.client.compat.xaerominimap.XaeroWaypoints;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.waypoint.WaypointTeleport;

/**
 * The teleport command reads absolute coordinates as cube coordinates, see {@code WorldCoordinatesMixin}, and Xaero
 * fills it with the storage position. This gives it the cube position instead.
 */
@Mixin(value = WaypointTeleport.class, remap = false)
public abstract class WaypointTeleportMixin {
	private static final String TELEPORT = "teleportToWaypoint(Lxaero/common/minimap/waypoints/Waypoint;Lxaero/hud/minimap/world/MinimapWorld;Lnet/minecraft/client/gui/screens/Screen;Z)V";

	@WrapOperation(method = TELEPORT, at = @At(value = "INVOKE", target = "Lxaero/common/minimap/waypoints/Waypoint;getX()I"))
	private int cubism$cubeX(Waypoint waypoint, Operation<Integer> original) {
		BlockPos cube = XaeroWaypoints.cube(waypoint);
		return cube != null ? cube.getX() : original.call(waypoint);
	}

	@WrapOperation(method = TELEPORT, at = @At(value = "INVOKE", target = "Lxaero/common/minimap/waypoints/Waypoint;getY()I"))
	private int cubism$cubeY(Waypoint waypoint, Operation<Integer> original) {
		BlockPos cube = XaeroWaypoints.cube(waypoint);
		return cube != null ? cube.getY() : original.call(waypoint);
	}

	@WrapOperation(method = TELEPORT, at = @At(value = "INVOKE", target = "Lxaero/common/minimap/waypoints/Waypoint;getZ()I"))
	private int cubism$cubeZ(Waypoint waypoint, Operation<Integer> original) {
		BlockPos cube = XaeroWaypoints.cube(waypoint);
		return cube != null ? cube.getZ() : original.call(waypoint);
	}

	/**
	 * Without a height, Xaero writes {@code ~}, which a mixed command takes from the cube position of the player: a
	 * sideways axis on another face. The cube position then uses the height of the player above the waypoint face.
	 * The first check of the method, the warning about an unknown height, stays as it is.
	 */
	@WrapOperation(method = TELEPORT, at = @At(value = "INVOKE", target = "Lxaero/common/minimap/waypoints/Waypoint;isYIncluded()Z", ordinal = 1))
	private boolean cubism$cubeHasHeight(Waypoint waypoint, Operation<Boolean> original) {
		return XaeroWaypoints.cube(waypoint) != null || original.call(waypoint);
	}
}
