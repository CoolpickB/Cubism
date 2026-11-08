package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.cubism.client.SeamClient;
import dev.cubism.client.render.CubeClouds;
import dev.cubism.client.render.CubeSky;
import dev.cubism.seam.CubeTime;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.DynamicGpuData;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(CloudRenderer.class)
public abstract class CloudRendererMixin {
	@WrapOperation(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/DynamicGpuData;writeTransform(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private GpuBufferSlice cubism$placeLayer(DynamicGpuData uniforms, Matrix4f modelView, Operation<GpuBufferSlice> original) {
		CubeClouds.Placement placement = CubeClouds.placement(this);
		if (placement == null) {
			return original.call(uniforms, modelView);
		}
		// A negative color modulator tells the cloud shader to cut the layer at the wedge of its face, and to fade it out
		// at the distance in Y. From orbit (z) the shader lights each cloud by the sun at its place on the cube, as the
		// planet is lit, with the rounded core of half size w. The texture matrix turns the face frame into the cube
		// frame, and its column 3 is the direction toward the sun.
		SeamLayout layout = SeamClient.layout;
		Matrix4f toCube = new Matrix4f();
		float inner = 0.0F;
		if (layout != null) {
			int[] m = layout.rotation(placement.face());
			for (int row = 0; row < 3; row++) {
				for (int column = 0; column < 3; column++) {
					toCube.setRowColumn(row, column, m[row * 3 + column]);
				}
			}
			toCube.setColumn(3, new Vector4f(CubeSky.sun(), 1.0F));
			inner = layout.radius - CubeTime.EDGE_BLEND;
		}
		Vector4f cube = new Vector4f(-1.0F, CubeClouds.fadeEnd(), CubeSky.orbit(), inner);
		return uniforms.writeTransform(new Matrix4f(modelView).mul(placement.rotation()), cube, placement.cameraInFace(), toCube);
	}

	/** The OIT passes copy the main depth before the clouds. Only the first layer copies it, or it erases the others. */
	@WrapOperation(method = "renderOit", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/RenderPass;draw(IIII)V"))
	private void cubism$copyDepthOnce(RenderPass renderPass, int vertices, int instances, int firstVertex, int firstInstance, Operation<Void> original) {
		if (CubeClouds.copiesDepth(this)) {
			original.call(renderPass, vertices, instances, firstVertex, firstInstance);
		}
	}
}
