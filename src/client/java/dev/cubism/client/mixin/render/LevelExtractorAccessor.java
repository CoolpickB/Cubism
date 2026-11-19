package dev.cubism.client.mixin.render;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelExtractor.class)
public interface LevelExtractorAccessor {
	@Invoker("setSectionDirty")
	void cubism$setSectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged);
}
