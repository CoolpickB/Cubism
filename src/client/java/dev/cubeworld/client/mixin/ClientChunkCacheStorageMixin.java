package dev.cubeworld.client.mixin;

import dev.cubeworld.client.SeamChunkStorageAccess;
import dev.cubeworld.client.SeamClient;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.concurrent.atomic.AtomicReferenceArray;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
public abstract class ClientChunkCacheStorageMixin implements SeamChunkStorageAccess {
	@Shadow @Final private AtomicReferenceArray<@Nullable LevelChunk> chunks;
	@Shadow private int chunkCount;

	@Shadow
	private int getIndex(int chunkX, int chunkZ) {
		throw new AssertionError();
	}

	@Shadow
	private void replace(int index, @Nullable LevelChunk newChunk) {
		throw new AssertionError();
	}

	@Shadow
	private void onChunkRemoved(LevelChunk chunk) {
		throw new AssertionError();
	}

	@Override
	public @Nullable LevelChunk cubeworld$detachIfSeam(int index, int centerX, int centerZ, int radius) {
		LevelChunk chunk = this.chunks.get(index);
		if (chunk == null || !SeamClient.isSeamChunk(chunk.getPos().x(), chunk.getPos().z(), centerX, centerZ, radius)) {
			return null;
		}
		// Vanilla drop also unloads the chunk from the level, which clears its block entities and light.
		if (this.chunks.compareAndSet(index, chunk, null)) {
			this.chunkCount--;
			this.onChunkRemoved(chunk);
			return chunk;
		}
		return null;
	}

	@Override
	public void cubeworld$attach(LevelChunk chunk) {
		this.replace(this.getIndex(chunk.getPos().x(), chunk.getPos().z()), chunk);
	}

	@Override
	public int cubeworld$slotCount() {
		return this.chunks.length();
	}
}
