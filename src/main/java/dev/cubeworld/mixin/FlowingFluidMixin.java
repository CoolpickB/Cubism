package dev.cubeworld.mixin;

import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
	@Inject(method = "spread", at = @At("HEAD"), cancellable = true)
	private void cubeworld$containSideAndBottomFaceFluids(
		ServerLevel level,
		BlockPos pos,
		BlockState blockState,
		FluidState fluidState,
		CallbackInfo ci
	) {
		if (level.getChunkSource().getGenerator() instanceof CubeChunkGenerator cubeGen) {
			Direction outward = CubePlanetMath.getPyramidOutwardFace(pos.getX(), pos.getY(), pos.getZ(), cubeGen.centerY());
			int secondMax = CubePlanetMath.getSecondMaxCoord(pos.getX(), pos.getY(), pos.getZ(), cubeGen.centerY());
			if (outward != Direction.UP || secondMax >= cubeGen.radiusBlocks() - 2) {
				ci.cancel();
			}
		}
	}
}
