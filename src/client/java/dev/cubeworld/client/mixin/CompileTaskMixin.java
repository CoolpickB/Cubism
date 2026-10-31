package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.client.LoadTraceClient;
import dev.cubeworld.client.render.SeamRenderer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Records the section position that a mesh was compiled for. See {@link SeamRenderer#COMPILED_FOR}. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class CompileTaskMixin {
	@ModifyExpressionValue(method = "doTask", at = @At(value = "NEW", target = "Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;"))
	private CompiledSectionMesh cubeworld$recordNode(CompiledSectionMesh mesh, @Local(ordinal = 0) long sectionNode) {
		SeamRenderer.COMPILED_FOR.put(mesh, sectionNode);
		LoadTraceClient.COMPILED.incrementAndGet();
		return mesh;
	}
}
