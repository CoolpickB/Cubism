package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.worldgen.FeatureTransaction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FeaturePlacer.class)
public abstract class FeaturePlacerMixin {
	@WrapOperation(
		method = "place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z")
	)
	private boolean cubism$wholeOrNothing(
		Feature feature, WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin, Operation<Boolean> original
	) {
		SeamLayout layout = SeamLayout.of(level.getLevel());
		// Snow and ice cover a whole chunk, one block per column: nothing of it can float.
		if (layout == null || feature instanceof SnowAndFreezeFeature) {
			return original.call(feature, level, generator, random, origin);
		}
		return FeatureTransaction.place(layout, origin, () -> original.call(feature, level, generator, random, origin), level);
	}
}
