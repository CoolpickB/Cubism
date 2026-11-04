package dev.cubeworld.client.sodium;

import dev.cubeworld.client.SeamClient;
import dev.cubeworld.client.render.CubeShade;
import dev.cubeworld.client.sodiumseam.ShadeSlice;
import dev.cubeworld.worldgen.SeamLayout;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The mesher of Sodium reads blocks and light through its slice of the level. A section next to a seam reads the
 * blocks of the neighbor face as the vanilla mesher does, see {@code RenderSectionRegionMixin}, and shades the faces of
 * a block over a rounded edge by their direction to the surface, see {@link CubeShade}.
 */
@Mixin(value = LevelSlice.class, remap = false)
public abstract class LevelSliceMixin implements ShadeSlice {
	@Shadow private ClientLevel level;

	@Unique private final BlockPos.MutableBlockPos cubeworld$shadePos = new BlockPos.MutableBlockPos();
	@Unique private boolean cubeworld$shadeSet;
	@Unique private @Nullable CardinalLighting cubeworld$shade;

	@Override
	public void cubeworld$setShadePos(int x, int y, int z) {
		this.cubeworld$shadePos.set(x, y, z);
		this.cubeworld$shadeSet = true;
		this.cubeworld$shade = null;
	}

	@Inject(method = "cardinalLighting", at = @At("HEAD"), cancellable = true)
	private void cubeworld$shadeOverEdge(CallbackInfoReturnable<CardinalLighting> cir) {
		if (this.cubeworld$shadeSet && SeamClient.layout != null) {
			if (this.cubeworld$shade == null) {
				this.cubeworld$shade = CubeShade.at(this.level.cardinalLighting(), this.cubeworld$shadePos);
			}
			cir.setReturnValue(this.cubeworld$shade);
		}
	}

	@Inject(method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
	private void cubeworld$readBlockAcrossSeam(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
		BlockPos owner = cubeworld$ownerPos(x, y, z);
		if (owner != null) {
			BlockState state = this.level.getBlockState(owner);
			// The block keeps the orientation of its own face, so a stair or slab would cull the faces next to it by
			// a shape that is turned the wrong way, and the block beside it would not draw a face that shows through the gap.
			cir.setReturnValue(state.isSolidRender() || !state.getFluidState().isEmpty() ? state : Blocks.AIR.defaultBlockState());
		}
	}

	@Inject(method = "getBrightness", at = @At("HEAD"), cancellable = true)
	private void cubeworld$readLightAcrossSeam(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		BlockPos owner = cubeworld$ownerPos(pos.getX(), pos.getY(), pos.getZ());
		if (owner != null) {
			cir.setReturnValue(this.level.getLightEngine().getLayerListener(layer).getLightValue(owner));
		}
	}

	@Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true)
	private void cubeworld$readRawLightAcrossSeam(BlockPos pos, int darkening, CallbackInfoReturnable<Integer> cir) {
		BlockPos owner = cubeworld$ownerPos(pos.getX(), pos.getY(), pos.getZ());
		if (owner != null) {
			cir.setReturnValue(this.level.getLightEngine().getRawBrightness(owner, darkening));
		}
	}

	/** The position of the real block, or null when the position is inside its wedge or in no region. */
	@Unique
	private static @Nullable BlockPos cubeworld$ownerPos(int x, int y, int z) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null || layout.insideWedge(x, y, z)) {
			return null;
		}
		int[] owner = new int[3];
		return layout.ownerStorage(x, y, z, owner) ? new BlockPos(owner[0], owner[1], owner[2]) : null;
	}
}
