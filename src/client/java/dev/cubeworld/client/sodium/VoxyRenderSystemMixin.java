package dev.cubeworld.client.sodium;

import dev.cubeworld.client.sodiumseam.VoxyFaces;
import me.cortex.voxy.client.core.VoxyRenderSystem;
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
	private IBoundStore cubeworld$faceStore(IBoundStore store) {
		return VoxyFaces.active() && store instanceof StreamedBoundStore ? VoxyFaces.store((VoxyRenderSystem) (Object) this) : store;
	}

	@ModifyArg(
		method = "renderOpaque",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;setCenterAndProcess(DD)Z"),
		index = 0
	)
	private double cubeworld$centerX(double x) {
		return VoxyFaces.centerX(x);
	}

	@ModifyArg(
		method = "renderOpaque",
		at = @At(value = "INVOKE", target = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;setCenterAndProcess(DD)Z"),
		index = 1
	)
	private double cubeworld$centerZ(double z) {
		return VoxyFaces.centerZ(z);
	}

	@Inject(method = "shutdown", at = @At("TAIL"))
	private void cubeworld$freeStores(CallbackInfo ci) {
		VoxyFaces.free();
	}
}
