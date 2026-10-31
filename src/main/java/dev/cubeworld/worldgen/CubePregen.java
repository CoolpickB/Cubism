package dev.cubeworld.worldgen;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;

/**
 * Generates the chunks of a new cube world before the first player joins, nearest to the spawn first. It also records
 * the generation status of every chunk column of the cube, which the loading screen of the same game draws.
 */
public final class CubePregen {
	/** Holds a chunk at full status until it is generated. The ticket is removed then, and the chunk unloads. */
	public static final TicketType TICKET = new TicketType(0L, TicketType.FLAG_LOADING);
	/** Chunks with a ticket at one time. More keeps the worker threads busy, but holds more chunks in memory. */
	private static final int IN_FLIGHT = 192;

	private static volatile @Nullable Progress progress;

	private final ServerLevel level;
	private final long[] order;
	private int next;
	private final Deque<ChunkPos> inFlight = new ArrayDeque<>();
	private int done;

	private CubePregen(ServerLevel level, long[] order) {
		this.level = level;
		this.order = order;
	}

	/** Starts recording the chunk status of a seam level for the loading screen. */
	public static void track(SeamLayout layout, Direction spawnFace) {
		progress = new Progress(layout, spawnFace);
	}

	public static void stopTracking() {
		progress = null;
	}

	public static @Nullable Progress progress() {
		return progress;
	}

	/** The pre-generation that the settings ask for, or null for none. */
	public static @Nullable CubePregen start(ServerLevel level, SeamLayout layout, CubeSettings settings, ChunkPos spawn) {
		if (settings.pregenRadius() == 0) {
			return null;
		}
		return new CubePregen(level, columns(layout, settings.pregenRadius(), layout.faceIndexAt(spawn.getMiddleBlockX()), spawn.getMiddleBlockX(), spawn.getMiddleBlockZ()));
	}

	/** Columns that a new world with these settings generates first, if the spawn is at the center of its face. */
	public static int count(CubeSettings settings) {
		if (settings.pregenRadius() == 0) {
			return 0;
		}
		SeamLayout layout = new SeamLayout(settings.diameterChunks());
		int face = settings.spawnFaceIndex();
		return columns(layout, settings.pregenRadius(), face, layout.offsetX(face), 0).length;
	}

	/** Columns within the radius of a spawn position, nearest first. Distance is measured between points of the cube surface. */
	private static long[] columns(SeamLayout layout, int radius, int spawnFace, int spawnX, int spawnZ) {
		int side = layout.spacing / 16;
		int[] out = new int[3];
		double[] origin = surfacePoint(layout, spawnFace, spawnX, spawnZ, out);
		double reach = radius == CubeSettings.PREGEN_WHOLE_CUBE ? Double.MAX_VALUE : radius * 16.0;
		LongArrayList columns = new LongArrayList();
		LongArrayList distances = new LongArrayList();
		for (int face = 0; face < SeamLayout.FACES.length; face++) {
			int minX = Math.floorDiv(layout.offsetX(face) - layout.spacing / 2, 16);
			int minZ = Math.floorDiv(-layout.spacing / 2, 16);
			for (int i = 0; i < side; i++) {
				for (int j = 0; j < side; j++) {
					int chunkX = minX + i;
					int chunkZ = minZ + j;
					double[] point = surfacePoint(layout, face, chunkX * 16 + 8, chunkZ * 16 + 8, out);
					double distance = Math.sqrt(square(point[0] - origin[0]) + square(point[1] - origin[1]) + square(point[2] - origin[2]));
					if (distance <= reach) {
						columns.add(ChunkPos.pack(chunkX, chunkZ));
						distances.add(Math.round(distance));
					}
				}
			}
		}
		Integer[] sorted = new Integer[columns.size()];
		Arrays.setAll(sorted, i -> i);
		Arrays.sort(sorted, Comparator.comparingLong(distances::getLong));
		long[] order = new long[sorted.length];
		for (int i = 0; i < sorted.length; i++) {
			order[i] = columns.getLong(sorted[i]);
		}
		return order;
	}

	/**
	 * Point on the cube surface under a storage column, in cube coordinates. A column past the edge of its face, in
	 * the atmosphere of the wedge, takes the nearest point of the face, so that it generates with the edge.
	 */
	private static double[] surfacePoint(SeamLayout layout, int face, int sx, int sz, int[] out) {
		int r = layout.radius;
		int x = Math.clamp(sx - layout.offsetX(face), -r, r);
		int z = Math.clamp(sz, -r, r);
		layout.toCube(face, layout.offsetX(face) + x, layout.centerY + r, z, out);
		return new double[] {out[0], out[1], out[2]};
	}

	private static double square(double value) {
		return value * value;
	}

	public int total() {
		return this.order.length;
	}

	public int done() {
		return this.done;
	}

	/** Adds tickets up to {@link #IN_FLIGHT}, removes the tickets of generated chunks, and unloads them. True when all are generated. */
	public boolean tick() {
		ServerChunkCache chunks = this.level.getChunkSource();
		this.inFlight.removeIf(pos -> {
			ChunkHolder holder = chunks.chunkMap.getUpdatingChunkIfPresent(pos.pack());
			if (holder != null && holder.getLatestStatus() == ChunkStatus.FULL) {
				chunks.removeTicketWithRadius(TICKET, pos, 0);
				this.done++;
				return true;
			}
			return false;
		});
		while (this.inFlight.size() < IN_FLIGHT && this.next < this.order.length) {
			ChunkPos pos = ChunkPos.unpack(this.order[this.next++]);
			chunks.addTicketWithRadius(TICKET, pos, 0);
			this.inFlight.add(pos);
		}
		chunks.tick(() -> true, false);
		Progress current = progress;
		if (current != null) {
			current.poll(this.level);
		}
		return this.inFlight.isEmpty() && this.next >= this.order.length;
	}

	/** Highest generation status seen for each chunk column of the cube, by face. Written on the server thread. */
	public static final class Progress {
		public final SeamLayout layout;
		public final Direction spawnFace;
		public final int side;
		/** {@link ChunkStatus#getIndex} plus one, 0 for a column that has not started. */
		private final byte[][] status;
		/** Set by a loading screen in the same game while it shows this progress. The server then polls every tick. */
		public volatile boolean watched;

		private Progress(SeamLayout layout, Direction spawnFace) {
			this.layout = layout;
			this.spawnFace = spawnFace;
			this.side = layout.spacing / 16;
			this.status = new byte[SeamLayout.FACES.length][this.side * this.side];
		}

		/** Reads the status of every column that has a chunk holder. */
		public void poll(ServerLevel level) {
			ServerChunkCache chunks = level.getChunkSource();
			int minZ = Math.floorDiv(-this.layout.spacing / 2, 16);
			for (int face = 0; face < this.status.length; face++) {
				int minX = Math.floorDiv(this.layout.offsetX(face) - this.layout.spacing / 2, 16);
				byte[] faceStatus = this.status[face];
				for (int i = 0; i < this.side; i++) {
					for (int j = 0; j < this.side; j++) {
						ChunkHolder holder = chunks.chunkMap.getUpdatingChunkIfPresent(ChunkPos.pack(minX + i, minZ + j));
						ChunkStatus latest = holder == null ? null : holder.getLatestStatus();
						if (latest != null) {
							int index = i * this.side + j;
							faceStatus[index] = (byte) Math.max(faceStatus[index], latest.getIndex() + 1);
						}
					}
				}
			}
		}

		/** Status of the column at offset ({@code i}, {@code j}) chunks from the low corner of a face region, or null. */
		public @Nullable ChunkStatus get(int face, int i, int j) {
			if (i < 0 || j < 0 || i >= this.side || j >= this.side) {
				return null;
			}
			int value = this.status[face][i * this.side + j];
			return value == 0 ? null : ChunkStatus.getStatusList().get(value - 1);
		}
	}
}
