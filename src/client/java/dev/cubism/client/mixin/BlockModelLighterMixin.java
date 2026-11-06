package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.client.render.CubeShade;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The shade of block faces over a rounded edge in the vanilla renderer. See {@link CubeShade}. */
@Mixin(BlockModelLighter.class)
public abstract class BlockModelLighterMixin {
	@WrapOperation(
		method = {"prepareQuadAmbientOcclusion", "prepareQuadFlat"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;cardinalLighting()Lnet/minecraft/world/level/CardinalLighting;")
	)
	private CardinalLighting cubism$shadeOverEdge(BlockAndTintGetter level, Operation<CardinalLighting> original, @Local(argsOnly = true) BlockPos pos) {
		return CubeShade.at(original.call(level), pos);
	}
}
