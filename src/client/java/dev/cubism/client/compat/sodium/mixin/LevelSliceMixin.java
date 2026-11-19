package dev.cubism.client.compat.sodium.mixin;

import dev.cubism.client.SeamClient;
import dev.cubism.client.render.CubeShade;
import dev.cubism.client.compat.sodium.ShadeSlice;
import dev.cubism.seam.SeamStates;
import dev.cubism.cube.SeamLayout;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
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

	@Unique private final BlockPos.MutableBlockPos cubism$shadePos = new BlockPos.MutableBlockPos();
	@Unique private boolean cubism$shadeSet;
	@Unique private @Nullable CardinalLighting cubism$shade;

	@Override
	public void cubism$setShadePos(int x, int y, int z) {
		this.cubism$shadePos.set(x, y, z);
		this.cubism$shadeSet = true;
		this.cubism$shade = null;
	}

	@Inject(method = "cardinalLighting", at = @At("HEAD"), cancellable = true)
	private void cubism$shadeOverEdge(CallbackInfoReturnable<CardinalLighting> cir) {
		if (this.cubism$shadeSet && SeamClient.layout != null) {
			if (this.cubism$shade == null) {
				this.cubism$shade = CubeShade.at(this.level.cardinalLighting(), this.cubism$shadePos);
			}
			cir.setReturnValue(this.cubism$shade);
		}
	}

	@Inject(method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
	private void cubism$readBlockAcrossSeam(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
		BlockPos owner = cubism$ownerPos(x, y, z);
		if (owner != null) {
			SeamLayout layout = SeamClient.layout;
			cir.setReturnValue(SeamStates.turnForMesh(layout, this.level.getBlockState(owner), layout.faceIndexAt(owner.getX()), layout.faceIndexAt(x)));
		}
	}

	@Inject(method = "getBrightness", at = @At("HEAD"), cancellable = true)
	private void cubism$readLightAcrossSeam(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		BlockPos owner = cubism$ownerPos(pos.getX(), pos.getY(), pos.getZ());
		if (owner != null) {
			cir.setReturnValue(this.level.getLightEngine().getLayerListener(layer).getLightValue(owner));
		}
	}

	@Inject(method = "getRawBrightness", at = @At("HEAD"), cancellable = true)
	private void cubism$readRawLightAcrossSeam(BlockPos pos, int darkening, CallbackInfoReturnable<Integer> cir) {
		BlockPos owner = cubism$ownerPos(pos.getX(), pos.getY(), pos.getZ());
		if (owner != null) {
			cir.setReturnValue(this.level.getLightEngine().getRawBrightness(owner, darkening));
		}
	}

	/** The position of the real block, or null when the position is inside its wedge or in no region. */
	@Unique
	private static @Nullable BlockPos cubism$ownerPos(int x, int y, int z) {
		SeamLayout layout = SeamClient.layout;
		if (layout == null || layout.insideWedge(x, y, z)) {
			return null;
		}
		int[] owner = new int[3];
		return layout.ownerStorage(x, y, z, owner) ? new BlockPos(owner[0], owner[1], owner[2]) : null;
	}
}
