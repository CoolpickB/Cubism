package dev.cubeworld.client.mixin;

import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SectionRenderDispatcher.RenderSection.class)
public interface RenderSectionAccessor {
	@Invoker("setSectionMesh")
	SectionMesh cubeworld$setSectionMesh(SectionMesh mesh);

	@Accessor("uploadedTime")
	void cubeworld$setUploadedTime(long time);
}
