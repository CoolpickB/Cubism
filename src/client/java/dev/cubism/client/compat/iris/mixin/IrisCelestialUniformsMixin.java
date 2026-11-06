package dev.cubism.client.compat.iris.mixin;

import dev.cubism.client.render.CubeSky;
import dev.cubism.client.compat.iris.CubeShaderPatch;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.CelestialUniforms;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris puts the sun on a vanilla arc through the face frame of the camera, which jumps by a quarter turn when the camera
 * crosses to the next face. The positions come from the tilted sky frame of {@link CubeSky#tiltedCelestial()}, so that
 * the elevation of the sun over the face frame agrees with the local time. The angle of the sun, and so the choice of
 * sun or moon, keep that local time too. The world space position feeds the shadow culling of Iris, so it follows the
 * shadow camera. Also adds the uniforms of {@link CubeShaderPatch}.
 */
@Mixin(value = CelestialUniforms.class, remap = false)
public abstract class IrisCelestialUniformsMixin {
	@Inject(method = "addCelestialUniforms", at = @At("TAIL"))
	private void cubism$cubeUniforms(UniformHolder uniforms, CallbackInfo ci) {
		CubeShaderPatch.addUniforms(uniforms);
	}

	@Inject(method = "getCelestialPosition", at = @At("HEAD"), cancellable = true)
	private void cubism$viewPosition(boolean sun, float y, CallbackInfoReturnable<Vector4f> cir) {
		Matrix3f celestial = CubeSky.tiltedCelestial();
		if (celestial != null) {
			Vector3f direction = celestial.getColumn(1, new Vector3f()).mul(sun ? 100.0F : -100.0F);
			cir.setReturnValue(new Matrix4f(CapturedRenderingState.INSTANCE.getGbufferModelView()).transform(new Vector4f(direction, 1.0F)));
		}
	}

	@Inject(method = "getCelestialPositionInWorldSpace", at = @At("HEAD"), cancellable = true)
	private void cubism$worldPosition(boolean sun, float y, CallbackInfoReturnable<Vector4f> cir) {
		Matrix3f celestial = CubeShaderPatch.active() ? CubeSky.celestial() : CubeSky.tiltedCelestial();
		if (celestial != null) {
			cir.setReturnValue(new Vector4f(celestial.getColumn(1, new Vector3f()).mul(sun ? 100.0F : -100.0F), 0.0F));
		}
	}
}
