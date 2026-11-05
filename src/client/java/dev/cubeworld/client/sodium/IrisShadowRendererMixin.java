package dev.cubeworld.client.sodium;

import dev.cubeworld.client.sodiumseam.SeamFaces;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris renders the shadows after the main setup of the terrain and before the main draw. It ends the shadow state of the
 * manager that Sodium holds, so the managers of the other faces must leave it at the same point.
 */
@Mixin(value = ShadowRenderer.class, remap = false)
public abstract class IrisShadowRendererMixin {
	@Inject(
		method = "renderShadows",
		at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/mixinterface/ShadowRenderListAccess;iris$endShadowRenderListScope()V")
	)
	private void cubeworld$endFaceShadowScopes(CallbackInfo ci) {
		if (SeamFaces.active()) {
			SeamFaces.endShadowScope();
		}
	}
}
