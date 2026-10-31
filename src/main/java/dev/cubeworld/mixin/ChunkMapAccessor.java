package dev.cubeworld.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Queue;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
	@Accessor("pendingUnloads")
	Long2ObjectLinkedOpenHashMap<ChunkHolder> cubeworld$pendingUnloads();

	@Accessor("unloadQueue")
	Queue<Runnable> cubeworld$unloadQueue();

	@Accessor("toDrop")
	LongSet cubeworld$toDrop();
}
