package dev.cubism.client.compat.xaerominimap.mixin;

import dev.cubism.client.compat.xaerominimap.XaeroWaypoints;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.waypoint.render.world.WaypointWorldRenderContext;
import xaero.hud.minimap.waypoint.render.world.WaypointWorldRenderReader;

/** Puts the in-world marker of a waypoint on another face where that face shows, see {@link XaeroWaypoints#shown}. */
@Mixin(value = WaypointWorldRenderReader.class, remap = false)
public abstract class WaypointWorldRenderReaderMixin {
	private static final String WAYPOINT_CONTEXT = "(Lxaero/common/minimap/waypoints/Waypoint;Lxaero/hud/minimap/waypoint/render/world/WaypointWorldRenderContext;F)D";

	@Inject(method = "getRenderX" + WAYPOINT_CONTEXT, at = @At("HEAD"), cancellable = true)
	private void cubism$shownX(Waypoint waypoint, WaypointWorldRenderContext context, float partialTicks, CallbackInfoReturnable<Double> cir) {
		BlockPos shown = XaeroWaypoints.shown(waypoint);
		if (shown != null) {
			cir.setReturnValue(shown.getX() + 0.5);
		}
	}

	@Inject(method = "getRenderY" + WAYPOINT_CONTEXT, at = @At("HEAD"), cancellable = true)
	private void cubism$shownY(Waypoint waypoint, WaypointWorldRenderContext context, float partialTicks, CallbackInfoReturnable<Double> cir) {
		BlockPos shown = XaeroWaypoints.shown(waypoint);
		if (shown != null) {
			cir.setReturnValue(shown.getY() + 1.0);
		}
	}

	@Inject(method = "getRenderZ" + WAYPOINT_CONTEXT, at = @At("HEAD"), cancellable = true)
	private void cubism$shownZ(Waypoint waypoint, WaypointWorldRenderContext context, float partialTicks, CallbackInfoReturnable<Double> cir) {
		BlockPos shown = XaeroWaypoints.shown(waypoint);
		if (shown != null) {
			cir.setReturnValue(shown.getZ() + 0.5);
		}
	}
}
