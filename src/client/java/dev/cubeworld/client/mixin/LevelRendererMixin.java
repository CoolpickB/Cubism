package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.cubeworld.client.SeamClient;
import dev.cubeworld.client.render.SeamRenderer;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Builds the seam terrain draws next to the vanilla terrain draw, and runs them wherever vanilla runs its own. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow @Final private LevelRenderState levelRenderState;

	@WrapOperation(
		method = "render",
		at = {
			@At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRenders(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"),
			@At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRendersIndirect(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;")
		}
	)
	private ChunkSectionsToRender cubeworld$prepareSeams(
		LevelRenderer self, Matrix4fc modelView, boolean respectTranslucentOrder, Operation<ChunkSectionsToRender> original
	) {
		ChunkSectionsToRender vanilla = original.call(self, modelView, respectTranslucentOrder);
		Vec3 camera = this.levelRenderState.cameraRenderState.pos;
		SeamRenderer.prepareFrame(self, modelView, BlockPos.containing(camera), camera, this.levelRenderState.cameraRenderState.cullFrustum, matrix -> original.call(self, matrix, respectTranslucentOrder));
		return vanilla;
	}

	@WrapOperation(
		method = {"executeSolid", "executeClassicTransparency"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V")
	)
	private void cubeworld$renderSeamGroup(
		ChunkSectionsToRender draws, ChunkSectionLayerGroup group, RenderPass pass, GpuSampler sampler, GpuTextureView atlas, boolean wireframe,
		Operation<Void> original
	) {
		original.call(draws, group, pass, sampler, atlas, wireframe);
		for (ChunkSectionsToRender seam : SeamRenderer.frameDraws) {
			original.call(seam, group, pass, sampler, atlas, wireframe);
		}
	}

	@WrapOperation(
		method = "executeOit",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderOit(Lcom/mojang/renderpearl/api/textures/GpuSampler;Lnet/minecraft/client/renderer/oit/OitStage;Lnet/minecraft/client/renderer/oit/OitRenderPassProvider$Parameters;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V")
	)
	private void cubeworld$renderSeamOit(
		ChunkSectionsToRender draws, GpuSampler sampler, OitStage stage, OitRenderPassProvider.Parameters params, GpuTextureView atlas,
		GpuTextureView lightmap, Operation<Void> original
	) {
		original.call(draws, sampler, stage, params, atlas, lightmap);
		for (ChunkSectionsToRender seam : SeamRenderer.frameDraws) {
			original.call(seam, sampler, stage, params, atlas, lightmap);
		}
	}

	@Inject(method = "resetLevelRenderData", at = @At("HEAD"))
	private void cubeworld$resetSeams(CallbackInfo ci) {
		SeamRenderer.reset();
	}

	/** A targeted block across a seam is stored in another face region. Its outline goes where the block shows. */
	@WrapOperation(method = "submitBlockOutline", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V"))
	private void cubeworld$outlineAcrossSeam(
		PoseStack poseStack, double x, double y, double z, Operation<Void> original, @Local(argsOnly = true) LevelRenderState levelRenderState
	) {
		SeamLayout layout = SeamClient.layout;
		Vec3 camera = levelRenderState.cameraRenderState.pos;
		BlockPos pos = levelRenderState.blockOutlineRenderState.pos();
		int cameraFace = layout == null ? -1 : SeamInteraction.regionFace(layout, camera.x, camera.z);
		int blockFace = layout == null ? -1 : SeamInteraction.regionFace(layout, pos.getX(), pos.getZ());
		if (cameraFace < 0 || blockFace < 0 || cameraFace == blockFace) {
			original.call(poseStack, x, y, z);
		} else {
			SeamRenderer.applyMotion(poseStack, layout.motion(blockFace, cameraFace), camera, false);
			original.call(poseStack, (double) pos.getX(), (double) pos.getY(), (double) pos.getZ());
		}
	}

	@WrapOperation(method = "submitBlockDestroyAnimation", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V"))
	private void cubeworld$crackAcrossSeam(
		PoseStack poseStack, double x, double y, double z, Operation<Void> original,
		@Local(argsOnly = true) LevelRenderState levelRenderState, @Local BlockBreakingRenderState state
	) {
		SeamLayout layout = SeamClient.layout;
		Vec3 camera = levelRenderState.cameraRenderState.pos;
		BlockPos pos = state.blockPos();
		int cameraFace = layout == null ? -1 : SeamInteraction.regionFace(layout, camera.x, camera.z);
		int blockFace = layout == null ? -1 : SeamInteraction.regionFace(layout, pos.getX(), pos.getZ());
		if (cameraFace < 0 || blockFace < 0 || cameraFace == blockFace) {
			original.call(poseStack, x, y, z);
		} else {
			SeamRenderer.applyMotion(poseStack, layout.motion(blockFace, cameraFace), camera, false);
			original.call(poseStack, (double) pos.getX(), (double) pos.getY(), (double) pos.getZ());
		}
	}

	@WrapOperation(method = "submitBlockEntities", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V"))
	private void cubeworld$blockEntityAcrossSeam(
		PoseStack poseStack, double x, double y, double z, Operation<Void> original,
		@Local(argsOnly = true) LevelRenderState levelRenderState, @Local BlockEntityRenderState renderState
	) {
		int[] motion = SeamRenderer.blockEntityMotion(renderState);
		if (motion == null) {
			original.call(poseStack, x, y, z);
		} else {
			BlockPos pos = renderState.blockPos;
			SeamRenderer.applyMotion(poseStack, motion, levelRenderState.cameraRenderState.pos, true);
			original.call(poseStack, (double) pos.getX(), (double) pos.getY(), (double) pos.getZ());
		}
	}

	@WrapOperation(method = "submitEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;DDDLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
	private void cubeworld$entityAcrossSeam(
		EntityRenderDispatcher dispatcher, EntityRenderState state, CameraRenderState camera, double x, double y, double z, PoseStack poseStack,
		SubmitNodeCollector output, Operation<Void> original
	) {
		int[] motion = SeamRenderer.entityMotion(state);
		if (motion == null) {
			original.call(dispatcher, state, camera, x, y, z, poseStack, output);
			return;
		}
		poseStack.pushPose();
		SeamRenderer.applyMotion(poseStack, motion, camera.pos, true);
		// Name tags and sprites turn to face the camera inside the moved pose, so they get the camera turned back.
		Quaternionf orientation = new Quaternionf(camera.orientation);
		camera.orientation.set(SeamRenderer.motionRotation(motion).conjugate().mul(orientation));
		original.call(dispatcher, state, camera, state.x, state.y, state.z, poseStack, output);
		camera.orientation.set(orientation);
		poseStack.popPose();
	}
}
