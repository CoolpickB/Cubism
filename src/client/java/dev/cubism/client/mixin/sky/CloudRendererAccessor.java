package dev.cubism.client.mixin.sky;

import net.minecraft.client.renderer.CloudRenderer;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CloudRenderer.class)
public interface CloudRendererAccessor {
	@Accessor("texture")
	CloudRenderer.@Nullable TextureData cubism$texture();

	@Invoker("packCellData")
	static long cubism$packCell(int color, boolean north, boolean east, boolean south, boolean west) {
		throw new AssertionError();
	}

	@Accessor("texture")
	void cubism$setTexture(CloudRenderer.@Nullable TextureData texture);
}
