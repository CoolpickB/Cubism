package dev.cubism.client.compat.voxy.mixin;

import dev.cubism.client.compat.voxy.VoxyFaces;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.bounding.IBoundStore;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Each face that Voxy draws masks out the sections that Sodium draws on that face, and the loaded area stays put. */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class VoxyRenderSystemMixin {
	@ModifyArg(
		method = "renderOpaque",
		at = @At(
			value = "INVOKE",
			target = "Lme/cortex/voxy/client/core/rendering/bounding/BoundRenderer;render(Lme/cortex/voxy/client/core/rendering/Viewport;Lme/cortex/voxy/client/core/rendering/bounding/IBoundStore;)V"
		),
		index = 1
	)
	private IBoundStore cubism$faceStore(IBoundStore store) {
		return VoxyFaces.active() && store instanceof StreamedBoundStore ? VoxyFaces.store((VoxyRenderSystem) (Object) this) : store;
	}

	@ModifyArg(
		method = "renderOpaque",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;setCenterAndProcess(DD)Z"),
		index = 0
	)
	private double cubism$centerX(double x) {
		return VoxyFaces.centerX(x);
	}

	@ModifyArg(
		method = "renderOpaque",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;setCenterAndProcess(DD)Z"),
		index = 1
	)
	private double cubism$centerZ(double z) {
		return VoxyFaces.centerZ(z);
	}

	@Inject(method = "renderOpaque", at = @At("HEAD"), cancellable = true)
	private void cubism$deferFace(Viewport<?> viewport, int depth, int color, CallbackInfo ci) {
		if (VoxyFaces.defer((VoxyRenderSystem) (Object) this, viewport, depth, color)) {
			ci.cancel();
		}
	}

	@Inject(method = "shutdown", at = @At("TAIL"))
	private void cubism$freeStores(CallbackInfo ci) {
		VoxyFaces.free();
	}
}
