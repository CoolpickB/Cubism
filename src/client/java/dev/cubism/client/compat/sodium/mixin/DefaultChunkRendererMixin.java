package dev.cubism.client.compat.sodium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cubism.client.SeamClient;
import dev.cubism.client.compat.iris.CubeShaderPatch;
import dev.cubism.client.compat.sodium.FaceLightBlend;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class DefaultChunkRendererMixin {
	/** Right after the lightmap of the face, binds what the patched terrain shader blends it with. */
	@WrapOperation(
		method = "render",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/RenderPass;setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V")
	)
	private void cubism$blendLight(
		RenderPass pass, String name, GpuTextureView view, GpuSampler sampler, Operation<Void> original, @Local(argsOnly = true) CameraTransform camera
	) {
		original.call(pass, name, view, sampler);
		if ("u_LightTex".equals(name) && !(SeamClient.IRIS && CubeShaderPatch.packInUse())) {
			FaceLightBlend.bind(pass, view, sampler, camera.x, camera.y, camera.z);
		}
	}
}
