package dev.cubism.compat.voxyworldgenv3.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cubism.compat.voxyworldgenv3.CubeScan;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** A chunk outside the face regions of a cube world is out of range for Voxygen, so it skips it. */
@Mixin(targets = "com.ethan.voxyworldgenv3.engine.Target", remap = false)
public abstract class TargetMixin {
	@Shadow
	public abstract ServerLevel level();

	@ModifyReturnValue(method = "chunkDistSq", at = @At("RETURN"))
	private long cubism$facesOnly(long distanceSq, int chunkX, int chunkZ) {
		return CubeScan.outside(this.level(), chunkX, chunkZ) ? Long.MAX_VALUE : distanceSq;
	}
}
