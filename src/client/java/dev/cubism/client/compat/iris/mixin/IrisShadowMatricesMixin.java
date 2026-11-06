package dev.cubism.client.compat.iris.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.cubism.client.render.CubeSky;
import dev.cubism.client.compat.iris.CubeShaderPatch;
import net.irisshaders.iris.shadows.ShadowMatrices;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Looks the shadow camera along the real direction of the sun or moon, with the axis of the sun as the up of the shadow map. */
@Mixin(value = ShadowMatrices.class, remap = false)
public abstract class IrisShadowMatricesMixin {
	@Inject(method = "createBaselineModelViewMatrix", at = @At("HEAD"), cancellable = true)
	private static void cubism$lookAlongSun(PoseStack target, float shadowAngle, float sunPathRotation, float nearPlane, float farPlane, CallbackInfo ci) {
		// A patched pack casts from the real sun or moon after the time at the camera. Others place the sun on the tilted
		// frame, and the shadows follow their light.
		Matrix3f celestial = CubeShaderPatch.active() ? CubeSky.celestial() : CubeSky.tiltedCelestial();
		if (celestial == null) {
			return;
		}
		// At night packs light from the moon, opposite the sun. A half turn about the axis keeps the frame right-handed.
		float light = CubeShaderPatch.active() ? CubeSky.shadowLightSign() : CelestialUniforms.isDay() ? 1.0F : -1.0F;
		Vector3f side = celestial.getColumn(0, new Vector3f()).mul(light);
		Vector3f sun = celestial.getColumn(1, new Vector3f()).mul(light);
		Vector3f axis = celestial.getColumn(2, new Vector3f());
		// The rows turn the world so that the sun is the +Z of the shadow camera. Axis, side and sun are a right-handed frame.
		Matrix3f turn = new Matrix3f(axis.x, side.x, sun.x, axis.y, side.y, sun.y, axis.z, side.z, sun.z);
		target.last().pose().identity().set(turn);
		target.last().normal().set(turn);
		ci.cancel();
	}
}
