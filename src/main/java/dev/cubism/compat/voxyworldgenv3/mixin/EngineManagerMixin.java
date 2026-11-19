package dev.cubism.compat.voxyworldgenv3.mixin;

import com.ethan.voxyworldgenv3.config.EngineMode;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.cubism.compat.voxyworldgenv3.CubeScan;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * In a cube world Voxygen uses its ticket engine, which loads chunks through the server: a pre-generated cube is read
 * from the save, where the default pipeline engine generates every chunk again. In space it scans around the face
 * under a player, not around the empty space where the player is stored.
 */
@Mixin(targets = "com.ethan.voxyworldgenv3.engine.EngineManager", remap = false)
public abstract class EngineManagerMixin {
	@Redirect(method = "collectTargets", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;chunkPosition()Lnet/minecraft/world/level/ChunkPos;"))
	private ChunkPos cubism$scanCenter(ServerPlayer player) {
		return CubeScan.center(player);
	}

	@ModifyExpressionValue(method = {"needsRebuild", "buildEngine"}, at = @At(value = "FIELD", target = "Lcom/ethan/voxyworldgenv3/config/ConfigData;engine:Lcom/ethan/voxyworldgenv3/config/EngineMode;"))
	private EngineMode cubism$ticketsInCube(EngineMode mode) {
		return CubeScan.cubeServer() ? EngineMode.TICKET : mode;
	}
}
