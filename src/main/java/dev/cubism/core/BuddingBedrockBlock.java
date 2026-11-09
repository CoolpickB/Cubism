package dev.cubism.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Bedrock that grows heavy cores into the air next to it, as budding amethyst grows buds. */
public class BuddingBedrockBlock extends Block {
	/** A random tick tries to grow with this chance, in one of the six directions. */
	public static final int GROWTH_CHANCE = 10;

	public BuddingBedrockBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
		if (random.nextInt(GROWTH_CHANCE) != 0) {
			return;
		}
		BlockPos target = pos.relative(Direction.values()[random.nextInt(6)]);
		if (level.getBlockState(target).isAir()) {
			level.setBlockAndUpdate(target, Blocks.HEAVY_CORE.defaultBlockState());
		}
	}
}
