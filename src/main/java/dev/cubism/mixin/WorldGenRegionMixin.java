package dev.cubism.mixin;

import dev.cubism.CubismMod;
import dev.cubism.worldgen.FeatureTransaction;
import dev.cubism.worldgen.SeamLayout;
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
	private @Nullable SeamLayout cubism$layout() {
		if (!this.seamChecked) {
			this.seamLayout = SeamLayout.of(this.getLevel());
			this.seamChecked = true;
		}
		return this.seamLayout;
	}

	@Unique
	private @Nullable BlockState cubism$outsideState(BlockPos pos) {
		SeamLayout layout = this.cubism$layout();
		if (layout == null || layout.insideWedge(pos.getX(), pos.getY(), pos.getZ())) {
			return null;
		}
		return layout.belowCubeSurface(pos.getX(), pos.getY(), pos.getZ()) ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
	}

	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void cubism$outsideBlock(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		BlockState state = this.cubism$outsideState(pos);
		if (state != null) {
			// A feature that reads stone here skips the cell without a write, as an iceberg does for the fake stone
			// under the sea. For the cut test that counts as a refused write.
			FeatureTransaction transaction = state.is(Blocks.STONE) ? FeatureTransaction.open() : null;
			if (transaction != null) {
				transaction.refused(pos);
			}
			cir.setReturnValue(state);
		}
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void cubism$outsideFluid(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		if (this.cubism$outsideState(pos) != null) {
			cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
		}
	}

	@Inject(method = "setBlock", at = @At("HEAD"), cancellable = true)
	private void cubism$noWriteOutside(BlockPos pos, BlockState state, int flags, int updateLimit, CallbackInfoReturnable<Boolean> cir) {
		FeatureTransaction transaction = this.cubism$layout() == null ? null : FeatureTransaction.open();
		if (this.cubism$outsideState(pos) != null) {
			if (transaction != null) {
				transaction.refused(pos);
			}
			cir.setReturnValue(false);
		} else if (transaction != null) {
			transaction.accepted(pos, ((WorldGenRegion) (Object) this).getBlockState(pos));
		}
	}
}
