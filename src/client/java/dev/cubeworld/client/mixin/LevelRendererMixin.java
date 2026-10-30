package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cubeworld.client.render.SeamRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.oit.OitRenderPassProvider;
import net.minecraft.client.renderer.oit.OitStage;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
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
		SeamRenderer.prepareFrame(self, modelView, BlockPos.containing(camera), camera, matrix -> original.call(self, matrix, respectTranslucentOrder));
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
}
