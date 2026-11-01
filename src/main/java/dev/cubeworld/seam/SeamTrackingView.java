package dev.cubeworld.seam;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.mixin.ChunkMapAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.function.Consumer;

/**
 * The vanilla square of chunks around a player, plus the chunks of neighbor faces that the player sees across seams.
 * {@code simulatedSeamChunks} are the seam chunks within simulation distance, where entities and blocks tick.
 * The square counts only the chunks of the face region of the player, given in chunk coordinates with exclusive
 * maximums. A large view distance makes the square reach into the regions next to it in storage, which can be the
 * opposite face, and the chunks there are not across a seam from the player.
 */
public record SeamTrackingView(
	ChunkTrackingView.Positioned base, int regionMinX, int regionMaxX, int regionMinZ, int regionMaxZ, LongSet seamChunks, LongSet simulatedSeamChunks
) implements ChunkTrackingView {
	public static final int REFRESH_TICKS = 20;

	/**
	 * Radius 1 gives the chunk the block-ticking level. ChunkMap sends a chunk to players only when it reaches that
	 * level. Entities tick only at the entity-ticking level, which needs radius 2.
	 */
	public void addTickets(ServerLevel level) {
		if (!this.baseLoaded(level)) {
			return;
		}
		for (long chunk : this.seamChunks) {
			level.getChunkSource().addTicketWithRadius(CubeWorldMod.SEAM_TICKET, ChunkPos.unpack(chunk), 1);
		}
		for (long chunk : this.simulatedSeamChunks) {
			level.getChunkSource().addTicketWithRadius(CubeWorldMod.SEAM_SIMULATION_TICKET, ChunkPos.unpack(chunk), 2);
		}
	}

	/**
	 * The chunk tasks of the server run in the order of the ticket levels, and a seam ticket outranks the vanilla ticket
	 * of the square. After a join, the seam chunks that were not generated yet held the tasks of the square back for
	 * 15 s, and the player saw nothing of the ground around them. The seam tickets wait until the square is loaded.
	 * A tenth of the square may stay behind, so that a chunk that does not load does not hold the seam chunks back.
	 */
	private boolean baseLoaded(ServerLevel level) {
		Long2ObjectLinkedOpenHashMap<ChunkHolder> visible = ((ChunkMapAccessor) level.getChunkSource().chunkMap).cubeworld$visibleChunkMap();
		int[] count = new int[2];
		this.base.forEach(pos -> {
			if (this.inBase(pos.x(), pos.z(), true)) {
				count[0]++;
				ChunkHolder holder = visible.get(pos.pack());
				if (holder != null && holder.getLatestStatus() == ChunkStatus.FULL) {
					count[1]++;
				}
			}
		});
		return count[1] * 10 >= count[0] * 9;
	}

	public static void refreshTickets(ServerLevel level) {
		for (ServerPlayer player : level.players()) {
			if (player.getChunkTrackingView() instanceof SeamTrackingView view) {
				view.addTickets(level);
			}
		}
	}

	@Override
	public boolean contains(int chunkX, int chunkZ, boolean includeNeighbors) {
		return this.inBase(chunkX, chunkZ, includeNeighbors) || this.seamChunks.contains(ChunkPos.pack(chunkX, chunkZ));
	}

	private boolean inBase(int chunkX, int chunkZ, boolean includeNeighbors) {
		return chunkX >= this.regionMinX && chunkX < this.regionMaxX && chunkZ >= this.regionMinZ && chunkZ < this.regionMaxZ
			&& this.base.contains(chunkX, chunkZ, includeNeighbors);
	}

	@Override
	public void forEach(Consumer<ChunkPos> consumer) {
		this.base.forEach(pos -> {
			if (this.inBase(pos.x(), pos.z(), true)) {
				consumer.accept(pos);
			}
		});
		for (long chunk : this.seamChunks) {
			ChunkPos pos = ChunkPos.unpack(chunk);
			if (!this.inBase(pos.x(), pos.z(), true)) {
				consumer.accept(pos);
			}
		}
	}

	/** Calls {@code onEnter} and {@code onLeave} for the chunks that are in only one of the two views. */
	public static void difference(ChunkTrackingView from, ChunkTrackingView to, Consumer<ChunkPos> onEnter, Consumer<ChunkPos> onLeave) {
		from.forEach(pos -> {
			if (!to.contains(pos)) {
				onLeave.accept(pos);
			}
		});
		to.forEach(pos -> {
			if (!from.contains(pos)) {
				onEnter.accept(pos);
			}
		});
	}
}
