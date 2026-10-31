package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.debug.LoadTrace;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sounds, block events and level events reach the players in range. A player sees across a seam, so the range check
 * uses the player moved into the region of the event. The packet keeps the stored position, which the client moves
 * to where it shows.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
	@Inject(method = "placeNewPlayer", at = @At("TAIL"))
	private void cubeworld$traceJoin(CallbackInfo ci, @Local(argsOnly = true) ServerPlayer player) {
		LoadTrace.event("server: player joined at " + player.blockPosition());
	}

	@WrapOperation(method = "broadcast", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getX()D"))
	private double cubeworld$listenerX(ServerPlayer player, Operation<Double> original, @Local(argsOnly = true, ordinal = 0) double x, @Local(argsOnly = true, ordinal = 2) double z) {
		return cubeworld$listener(player, x, z).x;
	}

	@WrapOperation(method = "broadcast", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getY()D"))
	private double cubeworld$listenerY(ServerPlayer player, Operation<Double> original, @Local(argsOnly = true, ordinal = 0) double x, @Local(argsOnly = true, ordinal = 2) double z) {
		return cubeworld$listener(player, x, z).y;
	}

	@WrapOperation(method = "broadcast", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getZ()D"))
	private double cubeworld$listenerZ(ServerPlayer player, Operation<Double> original, @Local(argsOnly = true, ordinal = 0) double x, @Local(argsOnly = true, ordinal = 2) double z) {
		return cubeworld$listener(player, x, z).z;
	}

	@Unique
	private static Vec3 cubeworld$listener(ServerPlayer player, double x, double z) {
		SeamLayout layout = SeamLayout.of(player.level());
		return layout == null ? player.position() : SeamInteraction.intoRegionOf(layout, player.position(), x, z);
	}
}
