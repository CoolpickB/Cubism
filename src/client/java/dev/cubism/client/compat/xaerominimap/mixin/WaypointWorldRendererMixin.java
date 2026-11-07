package dev.cubism.client.compat.xaerominimap.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.client.compat.xaerominimap.XaeroWaypoints;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.waypoint.render.world.WaypointWorldRenderer;

/**
 * The distance label and the distance limits of the in-world marker read the waypoint position again. A waypoint on
 * another face gets the shown position here too, so the label gives the straight distance to where the marker is.
 */
@Mixin(value = WaypointWorldRenderer.class, remap = false)
public abstract class WaypointWorldRendererMixin {
	private static final String RENDER_ELEMENT = "renderElement(Lxaero/common/minimap/waypoints/Waypoint;ZZDFDDLxaero/hud/minimap/element/render/MinimapElementRenderInfo;Lxaero/hud/minimap/element/render/MinimapElementGraphics;Lxaero/lib/client/graphics/XaeroBufferProvider;)Z";

	@WrapOperation(method = RENDER_ELEMENT, at = @At(value = "INVOKE", target = "Lxaero/common/minimap/waypoints/Waypoint;getX(D)I"))
	private int cubism$shownX(Waypoint waypoint, double scale, Operation<Integer> original) {
		BlockPos shown = XaeroWaypoints.shown(waypoint);
		return shown != null ? shown.getX() : original.call(waypoint, scale);
	}

	@WrapOperation(method = RENDER_ELEMENT, at = @At(value = "INVOKE", target = "Lxaero/common/minimap/waypoints/Waypoint;getZ(D)I"))
	private int cubism$shownZ(Waypoint waypoint, double scale, Operation<Integer> original) {
		BlockPos shown = XaeroWaypoints.shown(waypoint);
		return shown != null ? shown.getZ() : original.call(waypoint, scale);
	}

	@WrapOperation(method = RENDER_ELEMENT, at = @At(value = "INVOKE", target = "Lxaero/common/minimap/waypoints/Waypoint;getY()I"))
	private int cubism$shownY(Waypoint waypoint, Operation<Integer> original) {
		BlockPos shown = XaeroWaypoints.shown(waypoint);
		return shown != null ? shown.getY() : original.call(waypoint);
	}

	/** Without a height, Xaero measures at the height of the viewer, which is a sideways axis on another face. */
	@WrapOperation(method = RENDER_ELEMENT, at = @At(value = "INVOKE", target = "Lxaero/common/minimap/waypoints/Waypoint;isYIncluded()Z"))
	private boolean cubism$shownHasHeight(Waypoint waypoint, Operation<Boolean> original) {
		return XaeroWaypoints.shown(waypoint) != null || original.call(waypoint);
	}
}
