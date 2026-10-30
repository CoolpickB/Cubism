package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.worldgen.OrientedWorldGenLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.feature.MultifaceGrowthFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// The chunk reads the post-processing position as a world position, so a face level must convert it.
@Mixin(MultifaceGrowthFeature.class)
public abstract class MultifaceGrowthFeatureMixin {
	@WrapOperation(
		method = "placeGrowthIfPossible",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;markPosForPostProcessing(Lnet/minecraft/core/BlockPos;)V")
	)
	private void cubeworld$markInFrame(ChunkAccess chunk, BlockPos pos, Operation<Void> original, @Local(argsOnly = true) WorldGenLevel level) {
		if (level instanceof OrientedWorldGenLevel oriented) {
			oriented.markPosForPostProcessing(pos);
		} else {
			original.call(chunk, pos);
		}
	}
}
