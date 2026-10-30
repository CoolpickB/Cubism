package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Players see the crack of a block across a seam, so the distance check uses the player moved into the region of the block. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
	@WrapOperation(method = "destroyBlockProgress", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getX()D"))
	private double cubeworld$crackViewerX(ServerPlayer player, Operation<Double> original, @Local(argsOnly = true) BlockPos pos) {
		return this.cubeworld$viewer(player, pos).x;
	}

	@WrapOperation(method = "destroyBlockProgress", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getY()D"))
	private double cubeworld$crackViewerY(ServerPlayer player, Operation<Double> original, @Local(argsOnly = true) BlockPos pos) {
		return this.cubeworld$viewer(player, pos).y;
	}

	@WrapOperation(method = "destroyBlockProgress", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getZ()D"))
	private double cubeworld$crackViewerZ(ServerPlayer player, Operation<Double> original, @Local(argsOnly = true) BlockPos pos) {
		return this.cubeworld$viewer(player, pos).z;
	}

	private Vec3 cubeworld$viewer(ServerPlayer player, BlockPos pos) {
		SeamLayout layout = SeamLayout.of((ServerLevel) (Object) this);
		return layout == null ? player.position() : SeamInteraction.intoRegionOf(layout, player.position(), pos.getX() + 0.5, pos.getZ() + 0.5);
	}
}
