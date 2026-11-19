package dev.cubism.client.mixin.sky;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.client.render.CubeSky;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla tints the fog with the sunrise color when the camera looks toward the sun. On the cube the air shader of
 * {@link CubeSky} draws that glow over each part of the air, so the fog of vanilla gets none.
 */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class AtmosphericFogEnvironmentMixin {
	@WrapOperation(method = "getBaseColor", at = @At(value = "INVOKE", target = "Lorg/joml/Vector3fc;dot(FFF)F"))
	private float cubism$noSunriseTint(Vector3fc forward, float x, float y, float z, Operation<Float> original) {
		return CubeSky.active() ? 0.0F : original.call(forward, x, y, z);
	}
}
