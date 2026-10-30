package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.seam.CubeTime;
import dev.cubeworld.seam.CubeTimeLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Level.class)
public abstract class LevelMixin implements CubeTimeLevel {
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
}
