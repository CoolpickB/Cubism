package dev.cubism.mixin.tracking;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Queue;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	@Accessor("visibleChunkMap")
	Long2ObjectLinkedOpenHashMap<ChunkHolder> cubism$visibleChunkMap();

	@Accessor("pendingUnloads")
	Long2ObjectLinkedOpenHashMap<ChunkHolder> cubism$pendingUnloads();

	@Accessor("unloadQueue")
	Queue<Runnable> cubism$unloadQueue();

	@Accessor("toDrop")
	LongSet cubism$toDrop();
}
