package dev.cubism.compat.voxyworldgenv3.mixin;

import dev.cubism.compat.voxyworldgenv3.CubeScan;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Voxygen scans around the face under a player in space, not around the empty space where the player is stored. */
@Mixin(targets = "com.ethan.voxyworldgenv3.engine.EngineManager", remap = false)
public abstract class EngineManagerMixin {
	@Redirect(method = "collectTargets", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;chunkPosition()Lnet/minecraft/world/level/ChunkPos;"))
	private ChunkPos cubism$scanCenter(ServerPlayer player) {
		return CubeScan.center(player);
	}
}
