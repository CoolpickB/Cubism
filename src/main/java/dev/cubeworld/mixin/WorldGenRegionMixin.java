package dev.cubeworld.mixin;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Space outside the face wedges is empty in storage, but in the cube it is the terrain of other faces. Features
 * see a simple version of that terrain: stone below the cube surface and air above it. Features cannot write
 * there, so nothing grows on the wedge wall or spills into the space of another face.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
	@Shadow public abstract ServerLevel getLevel();

	@Unique private @Nullable SeamLayout seamLayout;
	@Unique private boolean seamChecked;

	@Unique
	private @Nullable SeamLayout cubeworld$layout() {
		if (!this.seamChecked) {
			this.seamLayout = this.getLevel().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator
				&& generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS) ? SeamLayout.active() : null;
			this.seamChecked = true;
		}
		return this.seamLayout;
	}

	@Unique
	private @Nullable BlockState cubeworld$outsideState(BlockPos pos) {
		SeamLayout layout = this.cubeworld$layout();
		if (layout == null || layout.insideWedge(pos.getX(), pos.getY(), pos.getZ())) {
			return null;
		}
		return layout.belowCubeSurface(pos.getX(), pos.getY(), pos.getZ()) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
	}

	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void cubeworld$outsideBlock(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		BlockState state = this.cubeworld$outsideState(pos);
		if (state != null) {
			cir.setReturnValue(state);
		}
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void cubeworld$outsideFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		if (this.cubeworld$outsideState(pos) != null) {
			cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
		}
	}

	@Inject(method = "setBlock", at = @At("HEAD"), cancellable = true)
	private void cubeworld$noWriteOutside(BlockPos pos, BlockState state, int flags, int updateLimit, CallbackInfoReturnable<Boolean> cir) {
		if (this.cubeworld$outsideState(pos) != null) {
			cir.setReturnValue(false);
		}
	}
}
