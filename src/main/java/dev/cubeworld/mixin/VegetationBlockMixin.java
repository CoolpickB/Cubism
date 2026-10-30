package dev.cubeworld.mixin;

import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import dev.cubeworld.worldgen.OrientedWorldGenLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class VegetationBlockMixin {
	@Shadow public abstract Block getBlock();
	@Shadow protected abstract BlockState asState();

	@Inject(method = "canSurvive", at = @At("RETURN"), cancellable = true)
	private void cubeworld$canSurviveOnAllSixCubeFaces(LevelReader level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValue() || level instanceof OrientedWorldGenLevel) {
			return;
		}
		boolean isCube = CubePlanetMath.isClientCubeActive();
		int centerY = CubePlanetMath.getClientCenterY();

		if (level instanceof ServerLevel serverLevel && serverLevel.getChunkSource().getGenerator() instanceof CubeChunkGenerator cubeGen) {
			isCube = true;
			centerY = cubeGen.centerY();
		} else if (level instanceof WorldGenRegion region && region.getLevel().getChunkSource().getGenerator() instanceof CubeChunkGenerator cubeGen) {
			isCube = true;
			centerY = cubeGen.centerY();
		}

		if (!isCube) {
			return;
		}

		Direction outward = CubePlanetMath.getPyramidOutwardFace(pos.getX(), pos.getY(), pos.getZ(), centerY);
		if (outward == Direction.UP) {
			return;
		}

		Block block = this.getBlock();
		BlockPos supportPos = pos.relative(outward.getOpposite());
		BlockState supportState = level.getBlockState(supportPos);

		if (block instanceof DoublePlantBlock) {
			BlockState selfState = this.asState();
			if (selfState.hasProperty(DoublePlantBlock.HALF) && selfState.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
				cir.setReturnValue(supportState.is(block) && supportState.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER);
			} else {
				cir.setReturnValue(supportState.is(BlockTags.DIRT) || supportState.is(Blocks.FARMLAND));
			}
		} else if (block == Blocks.LILY_PAD) {
			cir.setReturnValue(supportState.is(Blocks.WATER) || supportState.is(Blocks.ICE));
		} else if (block instanceof VegetationBlock) {
			cir.setReturnValue(
				supportState.is(BlockTags.DIRT)
					|| supportState.is(Blocks.FARMLAND)
					|| supportState.is(BlockTags.SAND)
					|| supportState.is(BlockTags.TERRACOTTA)
					|| supportState.is(Blocks.STONE)
					|| supportState.is(Blocks.DEEPSLATE)
			);
		} else if (block instanceof SnowLayerBlock) {
			cir.setReturnValue(supportState.isSolidRender() || supportState.is(Blocks.SNOW));
		} else if (block instanceof CactusBlock) {
			cir.setReturnValue(supportState.is(Blocks.CACTUS) || supportState.is(BlockTags.SAND));
		} else if (block instanceof SugarCaneBlock || block instanceof BambooStalkBlock) {
			cir.setReturnValue(supportState.is(block) || supportState.is(BlockTags.DIRT) || supportState.is(BlockTags.SAND));
		}
	}
}
