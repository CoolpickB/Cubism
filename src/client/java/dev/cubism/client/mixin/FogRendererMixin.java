package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla fades the fog color to black as the camera goes below the bottom of the world. A locked fall (Steadfast)
 * keeps the storage frame of its face, so it passes that depth while it is still beside another face of the cube.
 * The cube has no void under it, so the fade is off.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@WrapOperation(method = "computeFogColor", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinY()I"))
	private int cubism$noVoidDarkness(ClientLevel level, Operation<Integer> original) {
		return SeamLayout.of(level) == null ? original.call(level) : Integer.MIN_VALUE / 2;
	}
}
