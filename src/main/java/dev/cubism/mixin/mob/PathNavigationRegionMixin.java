package dev.cubism.mixin.mob;

import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Outside its wedge, a face region holds only cleared air, so a path toward a target across a seam would end at the
 * diagonal. The pathfinder reads those blocks from the face that owns them. The blocks keep the orientation of their
 * own face, which is exact for full blocks.
 */
@Mixin(PathNavigationRegion.class)
public abstract class PathNavigationRegionMixin {
	@Shadow @Final protected Level level;

	@Shadow
	public abstract BlockState getBlockState(BlockPos pos);

	@Shadow
	public abstract FluidState getFluidState(BlockPos pos);

	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void cubism$blockAcrossSeam(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		BlockPos owner = this.cubism$owner(pos);
		if (owner != null) {
			cir.setReturnValue(this.getBlockState(owner));
		}
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void cubism$fluidAcrossSeam(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		BlockPos owner = this.cubism$owner(pos);
		if (owner != null) {
			cir.setReturnValue(this.getFluidState(owner));
		}
	}

	// The region copies only the chunks around the path, and owner positions lie in another face region.
	@Inject(method = "getChunk(II)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("RETURN"), cancellable = true)
	private void cubism$loadedChunkOutsideRegion(int chunkX, int chunkZ, CallbackInfoReturnable<ChunkAccess> cir) {
		if (cir.getReturnValue() instanceof EmptyLevelChunk && SeamLayout.of(this.level) != null) {
			LevelChunk chunk = this.level.getChunkSource().getChunkNow(chunkX, chunkZ);
			if (chunk != null) {
				cir.setReturnValue(chunk);
			}
		}
	}

	/** The position of the real block, or null when the position is inside its wedge or in no region. */
	@Unique
	private @Nullable BlockPos cubism$owner(BlockPos pos) {
		SeamLayout layout = SeamLayout.of(this.level);
		BlockPos owner = layout == null ? pos : SeamInteraction.owner(layout, pos);
		return owner == pos ? null : owner;
	}
}
