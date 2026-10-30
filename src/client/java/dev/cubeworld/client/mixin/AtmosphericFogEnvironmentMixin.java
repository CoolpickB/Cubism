package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.client.render.CubeSky;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla tints the fog with the sunrise color when the camera looks east or west, where its sun rises and sets. On the
 * cube the tint follows the real sun, so it does not come and go as the camera turns away from the sun.
 */
@Mixin(AtmosphericFogEnvironment.class)
public abstract class AtmosphericFogEnvironmentMixin {
	@WrapOperation(method = "getBaseColor", at = @At(value = "INVOKE", target = "Lorg/joml/Vector3fc;dot(FFF)F"))
	private float cubeworld$lookAtCubeSun(Vector3fc forward, float x, float y, float z, Operation<Float> original) {
		if (!CubeSky.active()) {
			return original.call(forward, x, y, z);
		}
		Vector3fc sun = CubeSky.sunInFace();
		float horizontal = (float) Math.sqrt(sun.x() * sun.x() + sun.z() * sun.z());
		return horizontal < 1.0E-4F ? 0.0F : forward.dot(sun.x() / horizontal, 0.0F, sun.z() / horizontal);
	}
}
