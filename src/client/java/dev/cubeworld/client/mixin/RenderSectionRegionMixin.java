package dev.cubeworld.client.mixin;

import dev.cubeworld.client.SeamClient;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
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
 * A section next to a seam meshes against the blocks of the neighbor face. Outside its wedge, a face region holds
 * only cleared air and leftover feature blocks, so blocks and light there are read from the owner face instead.
 * Without this, the wedge staircase shows stone side faces lit by the dark cleared air.
 *
 * <p>The blocks keep the orientation of their own face. That is exact for full blocks and only an approximation
 * for stairs, slabs and other shapes.
 */
@Mixin(RenderSectionRegion.class)
public abstract class RenderSectionRegionMixin implements BlockAndTintGetter {
	@Shadow @Final private ClientLevel level;
	@Shadow @Final private LevelLightEngine lightEngine;

	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void cubeworld$readBlockAcrossSeam(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		BlockPos owner = cubeworld$ownerPos(pos);
		if (owner != null) {
			BlockState state = this.level.getBlockState(owner);
			// A partial shape would cull its neighbors by a turned shape. See LevelSliceMixin.
			cir.setReturnValue(state.isSolidRender() || !state.getFluidState().isEmpty() ? state : Blocks.AIR.defaultBlockState());
		}
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void cubeworld$readFluidAcrossSeam(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		BlockPos owner = cubeworld$ownerPos(pos);
		if (owner != null) {
			cir.setReturnValue(this.level.getFluidState(owner));
		}
	}

	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		BlockPos owner = cubeworld$ownerPos(pos);
		return this.lightEngine.getLayerListener(layer).getLightValue(owner != null ? owner : pos);
	}

	@Override
	public int getRawBrightness(BlockPos pos, int darkening) {
		BlockPos owner = cubeworld$ownerPos(pos);
		return this.lightEngine.getRawBrightness(owner != null ? owner : pos, darkening);
	}

	/** The position of the real block, or null when the position is inside its wedge or in no region. */
	@Unique
	private static @Nullable BlockPos cubeworld$ownerPos(BlockPos pos) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null || layout.insideWedge(pos.getX(), pos.getY(), pos.getZ())) {
			return null;
		}
		int[] owner = new int[3];
		return layout.ownerStorage(pos.getX(), pos.getY(), pos.getZ(), owner) ? new BlockPos(owner[0], owner[1], owner[2]) : null;
	}
}
