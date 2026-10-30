package dev.cubeworld.mixin;

import dev.cubeworld.worldgen.OrientedWorldGenLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.blockscan.BlockScanUtils;
import net.minecraft.world.level.blockscan.BlockStateConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

// The vanilla scans read chunk sections in world coordinates. A face level must read each block through its frame.
@Mixin(BlockScanUtils.class)
public abstract class BlockScanUtilsMixin {
	@Inject(method = "sectionBasedScan", at = @At("HEAD"), cancellable = true)
	private static void cubeworld$scanInFrame(
		LevelReader level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
		Predicate<BlockState> predicate, BlockStateConsumer consumer, CallbackInfoReturnable<Boolean> cir
	) {
		if (level instanceof OrientedWorldGenLevel) {
			cir.setReturnValue(cubeworld$scan(level, BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ), predicate, consumer));
		}
	}

	@Inject(method = "findBlocksWithCache", at = @At("HEAD"), cancellable = true)
	private static void cubeworld$findInFrame(
		LevelReader level, Iterable<BlockPos> positions, Predicate<BlockState> predicate, BlockStateConsumer consumer,
		CallbackInfoReturnable<Boolean> cir
	) {
		if (level instanceof OrientedWorldGenLevel) {
			cir.setReturnValue(cubeworld$scan(level, positions, predicate, consumer));
		}
	}

	private static boolean cubeworld$scan(LevelReader level, Iterable<BlockPos> positions, Predicate<BlockState> predicate, BlockStateConsumer consumer) {
		for (BlockPos pos : positions) {
			BlockState state = level.getBlockState(pos);
			if (predicate.test(state) && consumer.apply(pos, state).shouldAbort()) {
				return true;
			}
		}
		return false;
	}
}
