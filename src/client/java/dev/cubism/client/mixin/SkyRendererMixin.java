package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cubism.client.SeamClient;
import dev.cubism.client.render.CubeSky;
import dev.cubism.client.compat.iris.CubeShaderPatch;
import dev.cubism.seam.CubeTime;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Places the sun, moon and stars where the one sun of the cube stands in the sky of the camera face. Vanilla turns them
 * about one fixed axis; on the cube the axis is tilted and differs per face. Sky colors come from the local time.
 */
@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
	/** The sky frame in the camera face, from the last extract. Null when the level is not the cube. Render thread only. */
	@Unique
	private static @Nullable Matrix3f cubism$celestial;

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void cubism$extractCelestial(ClientLevel level, float partialTicks, Camera camera, SkyRenderState state, CallbackInfo ci) {
		SeamLayout layout = SeamLayout.of(level);
		cubism$celestial = null;
		CubeSky.setCelestial(null);
		if (layout == null || state.skybox != DimensionType.Skybox.OVERWORLD) {
			return;
		}
		level.dimensionType().defaultClock().ifPresent(clock -> {
			ClockInstance instance = level.clockManager().getInstance(clock);
			double ticks = instance.totalTicks() + (instance.isPaused() ? 0.0F : partialTicks * instance.rate());
			cubism$celestial = CubeTime.celestial(layout, layout.regionOfX(Mth.floor(camera.position().x)), ticks);
			CubeSky.setCelestial(cubism$celestial);
			net.minecraft.world.entity.Entity body = camera.entity();
			CubeSky.extract(level, camera.position(), body == null ? camera.position() : dev.cubism.client.CubeFlight.timeFocus(body, partialTicks), ticks);
			// The air already covers the sky below the horizon.
			state.shouldRenderDarkDisc = false;
		});
	}

	@Shadow
	private void renderStars(RenderPass renderPass, float starBrightness, PoseStack poseStack) {}

	/** In place of the flat sky color of vanilla: space, then the stars, then the air over them. */
	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderSkyDisc(Lcom/mojang/renderpearl/api/commands/RenderPass;Lorg/joml/Vector3fc;)V"))
	private void cubism$renderAir(SkyRenderer renderer, RenderPass renderPass, Vector3fc skyColor, Operation<Void> original) {
		Matrix3f celestial = cubism$celestial;
		// A shader pack draws its own sky, and Iris has no program for the pipelines of the cube sky.
		if (celestial == null || !CubeSky.active() || SeamClient.IRIS && CubeShaderPatch.packInUse()) {
			original.call(renderer, renderPass, skyColor);
			return;
		}
		CubeSky.renderSpace(renderPass);
		PoseStack poseStack = new PoseStack();
		poseStack.rotate(new Quaternionf().setFromNormalized(celestial));
		this.renderStars(renderPass, CubeSky.starMax(), poseStack);
		CubeSky.renderAir(renderPass);
	}

	@WrapOperation(method = "renderSunMoonAndStars", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderStars(Lcom/mojang/renderpearl/api/commands/RenderPass;FLcom/mojang/blaze3d/vertex/PoseStack;)V"))
	private void cubism$skipLateStars(SkyRenderer renderer, RenderPass renderPass, float starBrightness, PoseStack poseStack, Operation<Void> original) {
		if (cubism$celestial == null || !CubeSky.active()) {
			original.call(renderer, renderPass, starBrightness, poseStack);
		}
	}

	/** Vanilla never shows the moon on a full day sky. On the cube it can stand high in the day, so it fades. */
	@ModifyArg(method = "renderSunMoonAndStars", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderMoon(Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/world/level/MoonPhase;FLcom/mojang/blaze3d/vertex/PoseStack;)V"), index = 2)
	private float cubism$fadeMoon(float rainBrightness) {
		return cubism$celestial == null || !CubeSky.active() ? rainBrightness : rainBrightness * CubeSky.moonBrightness();
	}

	@WrapOperation(method = "renderSunMoonAndStars", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotateDegrees(Lcom/mojang/math/Axis;F)V"))
	private void cubism$skipVanillaTurn(PoseStack poseStack, Axis axis, float degrees, Operation<Void> original) {
		if (cubism$celestial == null) {
			original.call(poseStack, axis, degrees);
		}
	}

	@WrapOperation(
		method = "renderSunMoonAndStars",
		at = {
			@At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotate(Lcom/mojang/math/Axis;F)V", ordinal = 0),
			@At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotate(Lcom/mojang/math/Axis;F)V", ordinal = 2)
		}
	)
	private void cubism$sunAndStars(PoseStack poseStack, Axis axis, float angle, Operation<Void> original) {
		if (cubism$celestial == null) {
			original.call(poseStack, axis, angle);
		} else {
			poseStack.rotate(new Quaternionf().setFromNormalized(cubism$celestial));
		}
	}

	@WrapOperation(
		method = "renderSunMoonAndStars",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;rotate(Lcom/mojang/math/Axis;F)V", ordinal = 1)
	)
	private void cubism$moon(PoseStack poseStack, Axis axis, float angle, Operation<Void> original) {
		if (cubism$celestial == null) {
			original.call(poseStack, axis, angle);
		} else {
			poseStack.rotate(new Quaternionf().setFromNormalized(cubism$celestial));
			poseStack.rotate(Axis.XP, Mth.PI);
		}
	}

	/**
	 * Vanilla draws the glow of sunrise and sunset as a fan on the horizon of the camera face, which jumps to the next
	 * face at a crossing. The air of {@link CubeSky} draws the glow over each part of the air.
	 */
	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderSunriseAndSunset(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/blaze3d/vertex/PoseStack;FLorg/joml/Vector4fc;)V"))
	private void cubism$skipGlowFan(SkyRenderer renderer, RenderPass renderPass, PoseStack poseStack, float sunAngle, Vector4fc color, Operation<Void> original) {
		if (cubism$celestial == null || !CubeSky.active()) {
			original.call(renderer, renderPass, poseStack, sunAngle, color);
		}
	}
}
