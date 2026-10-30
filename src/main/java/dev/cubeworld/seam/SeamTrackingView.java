package dev.cubeworld.seam;

import dev.cubeworld.CubeWorldMod;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.function.Consumer;

/** The vanilla square of chunks around a player, plus the chunks of neighbor faces that the player sees across seams. */
public record SeamTrackingView(ChunkTrackingView.Positioned base, LongSet seamChunks) implements ChunkTrackingView {
	public static final int REFRESH_TICKS = 20;

	/**
	 * Radius 1 makes the chunk block-ticking. ChunkMap sends a chunk to players only when it reaches that level.
	 */
	public void addTickets(ServerLevel level) {
		for (long chunk : this.seamChunks) {
			level.getChunkSource().addTicketWithRadius(CubeWorldMod.SEAM_TICKET, ChunkPos.unpack(chunk), 1);
		}
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
		return this.base.contains(chunkX, chunkZ, includeNeighbors) || this.seamChunks.contains(ChunkPos.pack(chunkX, chunkZ));
	}

	@Override
	public void forEach(Consumer<ChunkPos> consumer) {
		this.base.forEach(consumer);
		for (long chunk : this.seamChunks) {
			ChunkPos pos = ChunkPos.unpack(chunk);
			if (!this.base.contains(pos.x(), pos.z())) {
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
