package dev.cubism.client.mixin.render;

import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public interface RenderSectionAccessor {
	@Invoker("setSectionMesh")
	SectionMesh cubism$setSectionMesh(SectionMesh mesh);

	@Accessor("uploadedTime")
	void cubism$setUploadedTime(long time);
}
