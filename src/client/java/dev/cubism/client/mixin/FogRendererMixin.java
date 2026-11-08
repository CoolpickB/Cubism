package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.worldgen.SeamLayout;
import dev.cubism.client.render.CubeSky;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.fog.FogData;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla fades the fog color to black as the camera goes below the bottom of the world. A locked fall (Steadfast)
 * keeps the storage frame of its face, so it passes that depth while it is still beside another face of the cube.
 * The cube has no void under it, so the fade is off.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@WrapOperation(method = "computeFogColor", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinY()I"))
	private int cubism$noVoidDarkness(ClientLevel level, Operation<Integer> original) {
		return SeamLayout.of(level) == null ? original.call(level) : Integer.MIN_VALUE / 2;
	}

	/**
	 * The fog of the air at the camera has no place in orbit: the air of the planet is the air shader of {@link CubeSky}
	 * and there is none between the camera and the terrain. The fog goes out with the same turn as the sky.
	 */
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void cubism$noFogInOrbit(Camera camera, int renderDistance, DeltaTracker tracker, float darkness, ClientLevel level, CallbackInfoReturnable<FogData> cir) {
		float orbit = CubeSky.orbit();
		if (orbit > 0.0F && CubeSky.active()) {
			FogData fog = cir.getReturnValue();
			// The distances grow without bound as the orbit weight goes to 1, so the fog thins out evenly.
			float reach = 1.0F / Math.max(1.0F - orbit, 1.0E-5F);
			fog.environmentalStart = Math.min(fog.environmentalStart * reach, FAR_FOG);
			fog.environmentalEnd = Math.min(fog.environmentalEnd * reach, FAR_FOG * 2.0F);
		}
	}

	private static final float FAR_FOG = 1.0E6F;
}
