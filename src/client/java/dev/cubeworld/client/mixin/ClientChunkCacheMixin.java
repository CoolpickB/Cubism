package dev.cubeworld.client.mixin;

import dev.cubeworld.client.SeamClient;
import dev.cubeworld.client.render.SeamRenderer;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the chunks of neighbor faces that the server sends across seams. They lie outside the square around the
 * player that vanilla stores, so vanilla would ignore them.
 */
@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
	@Shadow @Final ClientLevel level;

	// Section compile workers read chunks while the client thread adds and drops them.
	@Unique private final Map<Long, LevelChunk> seamChunks = new ConcurrentHashMap<>();
	// A copy of the range of the private vanilla storage: its center, and its radius from calculateStorageRange.
	@Unique private volatile int centerX;
	@Unique private volatile int centerZ;
	@Unique private volatile int storageRadius;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void cubeworld$initRange(ClientLevel level, int serverChunkRadius, CallbackInfo ci) {
		this.cubeworld$updateRadius(serverChunkRadius, ci);
	}

	@Inject(method = "updateViewRadius", at = @At("TAIL"))
	private void cubeworld$updateRadius(int viewRange, CallbackInfo ci) {
		this.storageRadius = Math.max(2, viewRange) + 3;
	}

	@Inject(method = "updateViewCenter", at = @At("TAIL"))
	private void cubeworld$updateCenter(int x, int z, CallbackInfo ci) {
		this.centerX = x;
		this.centerZ = z;
	}

	@Unique
	private boolean cubeworld$isSeamChunk(int chunkX, int chunkZ) {
		return SeamClient.layout != null
			&& (Math.abs(chunkX - this.centerX) > this.storageRadius || Math.abs(chunkZ - this.centerZ) > this.storageRadius);
	}

	@Inject(method = "replaceWithPacketData", at = @At("HEAD"), cancellable = true)
	private void cubeworld$storeSeamChunk(int chunkX, int chunkZ, ClientboundLevelChunkPacketData data, CallbackInfoReturnable<LevelChunk> cir) {
		if (!this.cubeworld$isSeamChunk(chunkX, chunkZ)) {
			return;
		}
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		LevelChunk chunk = this.seamChunks.get(pos.pack());
		if (chunk == null) {
			chunk = new LevelChunk(this.level, pos);
			this.seamChunks.put(pos.pack(), chunk);
		}
		chunk.replaceWithPacketData(chunkX, chunkZ, data);
		this.level.onChunkLoaded(pos);
		SeamRenderer.onChunkLoaded(pos);
		cir.setReturnValue(chunk);
	}

	@Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("HEAD"), cancellable = true)
	private void cubeworld$getSeamChunk(int chunkX, int chunkZ, ChunkStatus status, boolean loadOrGenerate, CallbackInfoReturnable<LevelChunk> cir) {
		if (!this.seamChunks.isEmpty() && this.cubeworld$isSeamChunk(chunkX, chunkZ)) {
			LevelChunk chunk = this.seamChunks.get(ChunkPos.pack(chunkX, chunkZ));
			if (chunk != null) {
				cir.setReturnValue(chunk);
			}
		}
	}

	@Inject(method = "drop", at = @At("HEAD"))
	private void cubeworld$dropSeamChunk(ChunkPos pos, CallbackInfo ci) {
		LevelChunk chunk = this.seamChunks.remove(pos.pack());
		if (chunk != null) {
			this.level.unload(chunk);
			SeamRenderer.onChunkUnloaded(pos);
		}
	}
}
