package dev.cubeworld.client.mixin;

import net.minecraft.client.renderer.CloudRenderer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CloudRenderer.class)
public interface CloudRendererAccessor {
	@Accessor("texture")
	CloudRenderer.@Nullable TextureData cubeworld$texture();

	@Invoker("packCellData")
	static long cubeworld$packCell(int color, boolean north, boolean east, boolean south, boolean west) {
		throw new AssertionError();
	}

	@Accessor("texture")
	void cubeworld$setTexture(CloudRenderer.@Nullable TextureData texture);
}
