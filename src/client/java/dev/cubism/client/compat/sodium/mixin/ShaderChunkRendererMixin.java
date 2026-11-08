package dev.cubism.client.compat.sodium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class ShaderChunkRendererMixin {
	/** The second layout built there is the light group. The patched terrain shader blends four more lightmaps, see TerrainShaderPatch. */
	@WrapOperation(
		method = "<clinit>",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/pipeline/BindGroupLayout$Builder;build()Lcom/mojang/renderpearl/api/pipeline/BindGroupLayout;", ordinal = 1)
	)
	private static BindGroupLayout cubism$lightGroup(BindGroupLayout.Builder builder, Operation<BindGroupLayout> original) {
		builder.withUniform("u_LightTex1", UniformType.COMBINED_IMAGE_SAMPLER);
		builder.withUniform("u_LightTex2", UniformType.COMBINED_IMAGE_SAMPLER);
		builder.withUniform("u_LightTex3", UniformType.COMBINED_IMAGE_SAMPLER);
		builder.withUniform("u_LightTex4", UniformType.COMBINED_IMAGE_SAMPLER);
		builder.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER);
		return original.call(builder);
	}
}
