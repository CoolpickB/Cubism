package dev.cubism.mixin.time;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.time.CubeTime;
import dev.cubism.cube.SeamLayout;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Phantoms come for each player by the night on the face of that player, not by one night for the level. */
@Mixin(PhantomSpawner.class)
public abstract class PhantomSpawnerMixin {
	@Unique
	private static final int PHANTOM_DARKEN = 5;

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getSkyDarken()I"))
	private int cubism$checkEachPlayer(ServerLevel level, Operation<Integer> original) {
		return SeamLayout.of(level) == null ? original.call(level) : PHANTOM_DARKEN;
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isSpectator()Z"))
	private boolean cubism$skipPlayerInDaylight(ServerPlayer player, Operation<Boolean> original, @Local(argsOnly = true) ServerLevel level) {
		return original.call(player) || CubeTime.darkenAt(level, player.getX(), player.getY(), player.getZ()) < PHANTOM_DARKEN;
	}
}
