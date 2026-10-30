package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cubeworld.client.render.CubeClouds;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.DynamicGpuData;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
	@WrapOperation(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private GpuBufferSlice cubeworld$placeLayer(DynamicGpuData uniforms, Matrix4f modelView, Operation<GpuBufferSlice> original) {
		CubeClouds.Placement placement = CubeClouds.placement(this);
		if (placement == null) {
			return original.call(uniforms, modelView);
		}
		// A negative color modulator tells the cloud shader to cut the layer at the wedge of its face, and to fade it out
		// at the distance in Y.
		Vector4f cube = new Vector4f(-1.0F, CubeClouds.fadeEnd(), 0.0F, 0.0F);
		return uniforms.writeTransform(new Matrix4f(modelView).mul(placement.rotation()), cube, placement.cameraInFace(), new Matrix4f());
	}

	/** The OIT passes copy the main depth before the clouds. Only the first layer copies it, or it erases the others. */
	@WrapOperation(method = "renderOit", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/RenderPass;draw(IIII)V"))
	private void cubeworld$copyDepthOnce(RenderPass renderPass, int vertices, int instances, int firstVertex, int firstInstance, Operation<Void> original) {
		if (CubeClouds.copiesDepth(this)) {
			original.call(renderPass, vertices, instances, firstVertex, firstInstance);
		}
	}
}
