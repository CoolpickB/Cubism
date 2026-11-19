package dev.cubism.client.mixin.world;

import dev.cubism.client.debug.LoadTraceClient;
import dev.cubism.client.SeamChunkStorageAccess;
import dev.cubism.client.SeamClient;
import dev.cubism.client.render.SeamRenderer;
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

import java.lang.invoke.MethodHandles;
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
	private void cubism$initRange(ClientLevel level, int serverChunkRadius, CallbackInfo ci) {
		this.cubism$updateRadius(serverChunkRadius, ci);
	}

	@Inject(method = "updateViewRadius", at = @At("TAIL"))
	private void cubism$updateRadius(int viewRange, CallbackInfo ci) {
		this.storageRadius = Math.max(2, viewRange) + 3;
		SeamClient.serverViewRadius = viewRange;
	}

	/**
	 * Moves chunks between the two stores when the center moves, as on a teleport or a walk across a region. The
	 * server sends each chunk once, so a chunk that stayed in the wrong store would be lost until it is sent again.
	 */
	@Inject(method = "updateViewCenter", at = @At("HEAD"))
	private void cubism$moveChunksOnRecenter(int x, int z, CallbackInfo ci) {
		this.centerX = x;
		this.centerZ = z;
		SeamClient.viewCenterX = x;
		SeamClient.viewCenterZ = z;
		if (SeamClient.layout == null) {
			return;
		}
		SeamChunkStorageAccess access = this.cubism$storage();
		for (int index = 0; index < access.cubism$slotCount(); index++) {
			LevelChunk chunk = access.cubism$detachIfSeam(index, x, z, this.storageRadius);
			if (chunk != null) {
				LoadTraceClient.count("chunk.toSeam");
				this.seamChunks.put(chunk.getPos().pack(), chunk);
				SeamRenderer.onChunkMovedToSeam(chunk.getPos());
				SeamRenderer.onChunkLoaded(chunk.getPos());
			}
		}
		var iterator = this.seamChunks.values().iterator();
		while (iterator.hasNext()) {
			LevelChunk chunk = iterator.next();
			ChunkPos pos = chunk.getPos();
			if (!this.cubism$isSeamChunk(pos.x(), pos.z())) {
				iterator.remove();
				LoadTraceClient.count("chunk.toVanilla");
				SeamRenderer.onChunkMovedToVanilla(pos);
				access.cubism$attach(chunk);
				this.level.onChunkLoaded(pos);
			}
		}
	}

	/**
	 * The storage class is private, so neither a shadow nor a MixinExtras handler can name its type. This code runs
	 * in ClientChunkCache after the merge, so its lookup has private access.
	 */
	@Unique
	private SeamChunkStorageAccess cubism$storage() {
		try {
			Class<?> type = Class.forName("net.minecraft.client.multiplayer.ClientChunkCache$Storage");
			return (SeamChunkStorageAccess) MethodHandles.lookup().findGetter(ClientChunkCache.class, "storage", type).invoke((ClientChunkCache) (Object) this);
		} catch (Throwable e) {
			throw new IllegalStateException("Could not read the client chunk storage", e);
		}
	}

	@Unique
	private boolean cubism$isSeamChunk(int chunkX, int chunkZ) {
		return SeamClient.isSeamChunk(chunkX, chunkZ, this.centerX, this.centerZ, this.storageRadius);
	}

	@Inject(method = "replaceWithPacketData", at = @At("HEAD"), cancellable = true)
	private void cubism$storeSeamChunk(int chunkX, int chunkZ, ClientboundLevelChunkPacketData data, CallbackInfoReturnable<LevelChunk> cir) {
		if (!this.cubism$isSeamChunk(chunkX, chunkZ)) {
			return;
		}
		ChunkPos pos = new ChunkPos(chunkX, chunkZ);
		LoadTraceClient.count("packet.seamStore");
		LevelChunk chunk = this.seamChunks.get(pos.pack());
		if (chunk == null) {
			chunk = new LevelChunk(this.level, pos);
			this.seamChunks.put(pos.pack(), chunk);
		}
		chunk.replaceWithPacketData(chunkX, chunkZ, data);
		this.level.onChunkLoaded(pos);
		if (SeamClient.SODIUM) {
			dev.cubism.client.compat.sodium.SodiumLink.blockData(this.level, chunkX, chunkZ);
		}
		SeamRenderer.onChunkLoaded(pos);
		SeamRenderer.markAcrossSeamsDirty(pos);
		cir.setReturnValue(chunk);
	}

	@Inject(method = "replaceWithPacketData", at = @At("RETURN"))
	private void cubism$remeshAcrossSeams(int chunkX, int chunkZ, ClientboundLevelChunkPacketData data, CallbackInfoReturnable<LevelChunk> cir) {
		SeamRenderer.markAcrossSeamsDirty(new ChunkPos(chunkX, chunkZ));
	}

	@Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("HEAD"), cancellable = true)
	private void cubism$getSeamChunk(int chunkX, int chunkZ, ChunkStatus status, boolean loadOrGenerate, CallbackInfoReturnable<LevelChunk> cir) {
		if (!this.seamChunks.isEmpty() && this.cubism$isSeamChunk(chunkX, chunkZ)) {
			LevelChunk chunk = this.seamChunks.get(ChunkPos.pack(chunkX, chunkZ));
			if (chunk != null) {
				cir.setReturnValue(chunk);
			}
		}
	}

	@Inject(method = "drop", at = @At("HEAD"))
	private void cubism$dropSeamChunk(ChunkPos pos, CallbackInfo ci) {
		LevelChunk chunk = this.seamChunks.remove(pos.pack());
		LoadTraceClient.count(chunk != null ? "drop.seamStore" : "drop.vanillaStore");
		if (chunk != null) {
			this.level.unload(chunk);
			SeamRenderer.onChunkUnloaded(pos);
		}
	}
}
