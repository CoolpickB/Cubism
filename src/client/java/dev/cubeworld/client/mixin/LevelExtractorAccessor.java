package dev.cubeworld.client.mixin;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelExtractor.class)
public interface LevelExtractorAccessor {
	@Invoker("setSectionDirty")
	void cubeworld$setSectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged);
}
