package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.worldgen.OrientedWorldGenLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.FallenTreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// The chunk reads the post-processing position as a world position, so a face level must convert it.
@Mixin(FallenTreeFeature.class)
public abstract class FallenTreeFeatureMixin {
	@WrapOperation(
		method = "placeLogBlock",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/FallenTreeFeature;markAboveForPostProcessing(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/core/BlockPos;)V")
	)
	private void cubeworld$markAboveInFrame(FallenTreeFeature feature, WorldGenLevel level, BlockPos pos, Operation<Void> original) {
		if (level instanceof OrientedWorldGenLevel oriented) {
			oriented.markAboveForPostProcessing(pos);
		} else {
			original.call(feature, level, pos);
		}
	}
}
