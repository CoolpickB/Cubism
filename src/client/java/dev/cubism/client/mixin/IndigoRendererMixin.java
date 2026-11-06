package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.client.render.CubeShade;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** The flat shade of block faces over a rounded edge in the Indigo renderer of Fabric, which meshes the terrain. See {@link CubeShade}. */
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.AltModelBlockRendererImpl", remap = false)
public abstract class IndigoRendererMixin {
	@Shadow private BlockPos pos;

	@WrapOperation(method = "shadeQuad", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;cardinalLighting()Lnet/minecraft/world/level/CardinalLighting;"))
	private CardinalLighting cubism$shadeOverEdge(BlockAndTintGetter level, Operation<CardinalLighting> original) {
		return CubeShade.at(original.call(level), this.pos);
	}
}
