package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cubeworld.seam.CubeTime;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/** Monsters spawn by the darkness of the local time of their chunk. */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
	@Shadow
	@Final
	ServerLevel level;

	@WrapMethod(method = "tickSpawningChunk")
	private void cubeworld$spawnAtLocalTime(LevelChunk chunk, List<MobCategory> categories, NaturalSpawner.SpawnState state, Operation<Void> original) {
		CubeTime.at(this.level, chunk.getPos().getMiddleBlockX(), chunk.getMaxY(), chunk.getPos().getMiddleBlockZ(), () -> original.call(chunk, categories, state));
	}
}
