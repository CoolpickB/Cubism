package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.seam.CubeTime;
import dev.cubeworld.seam.CubeTimeLevel;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Level.class)
public abstract class LevelMixin implements CubeTimeLevel, BlockAndLightGetter {
	@Shadow
	private int skyDarken;

	@Unique
	private CubeTime.@Nullable State cubeworld$cubeTime;

	@Override
	public CubeTime.@Nullable State cubeworld$cubeTime() {
		return this.cubeworld$cubeTime;
	}

	@Override
	public void cubeworld$setCubeTime(CubeTime.State state) {
		this.cubeworld$cubeTime = state;
	}

	@Override
	public int cubeworld$skyDarken() {
		return this.skyDarken;
	}

	@Override
	public void cubeworld$setSkyDarken(int skyDarken) {
		this.skyDarken = skyDarken;
	}

	/** A daylight detector reads the sky of its own face. */
	@WrapOperation(method = "tickBlockEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V"))
	private void cubeworld$tickAtLocalTime(TickingBlockEntity ticker, Operation<Void> original) {
		BlockPos pos = ticker.getPos();
		CubeTime.at((Level) (Object) this, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, () -> original.call(ticker));
	}

	/**
	 * Outside its wedge, a face region holds cleared air with no real light. Particles, mobs and entity rendering
	 * near a seam read the light there, so a read outside the wedge goes to the face that owns the position.
	 */
	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		return this.getLightEngine().getLayerListener(layer).getLightValue(this.cubeworld$lightOwner(pos));
	}

	@Override
	public int getRawBrightness(BlockPos pos, int darkening) {
		return this.getLightEngine().getRawBrightness(this.cubeworld$lightOwner(pos), darkening);
	}

	@Unique
	private BlockPos cubeworld$lightOwner(BlockPos pos) {
		SeamLayout layout = SeamLayout.of((Level) (Object) this);
		if (layout == null || !layout.inRegion(pos.getX(), pos.getZ())) {
			return pos;
		}
		int up = pos.getY() - layout.centerY;
		// Above both diagonals of its face, a position is surely in its wedge. This skips the full test on most reads.
		if (up > Math.abs(pos.getX() - layout.offsetX(layout.faceIndexAt(pos.getX()))) && up > Math.abs(pos.getZ())) {
			return pos;
		}
		int[] owner = new int[3];
		layout.ownerStorage(pos.getX(), pos.getY(), pos.getZ(), owner);
		return owner[0] == pos.getX() && owner[1] == pos.getY() && owner[2] == pos.getZ() ? pos : new BlockPos(owner[0], owner[1], owner[2]);
	}
}
