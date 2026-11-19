package dev.cubism.client.mixin.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.client.render.CubeShade;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** The smooth shade of block faces over a rounded edge in the Indigo renderer of Fabric. See {@link CubeShade}. */
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.aocalc.AoCalculator", remap = false)
public abstract class IndigoAoCalculatorMixin {
	@Shadow private BlockPos pos;

	@WrapOperation(
		method = "computeFace(Lnet/fabricmc/fabric/impl/client/indigo/renderer/aocalc/AoFaceData;Lnet/minecraft/core/Direction;ZLnet/minecraft/core/Direction;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;cardinalLighting()Lnet/minecraft/world/level/CardinalLighting;")
	)
	private CardinalLighting cubism$shadeOverEdge(BlockAndTintGetter level, Operation<CardinalLighting> original) {
		return CubeShade.at(original.call(level), this.pos);
	}
}
