package dev.cubeworld.worldgen;

import dev.cubeworld.mixin.ChunkMapAccessor;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

/**
 * Generates the chunks of a new cube world before the first player joins, nearest to the spawn first. It also records
 * the generation status of every chunk column of the cube, which the loading screen of the same game draws.
 */
public final class CubePregen {
	/**
	 * Holds a chunk at block ticking level until it is ready, and its neighbors at full status. The ticket is removed
	 * then, and the chunks unload. Vanilla does the post-processing of generation, the shape updates of fluids and
	 * plants, only when a chunk starts to tick. At full level that work stays in the chunk and runs when a player first
	 * comes near, which makes the first visit slow.
	 */
	public static final TicketType TICKET = new TicketType(0L, TicketType.FLAG_LOADING);
	private static final int TICKET_RADIUS = 1;
	/** Chunks with a ticket at one time. More keeps the worker threads busy, but holds more chunks in memory. */
	private static final int IN_FLIGHT = 192;

	private static volatile @Nullable Progress progress;

	private final ServerLevel level;
	private final long[] order;
	private int next;
	private final Deque<ChunkPos> inFlight = new ArrayDeque<>();
	/** Ready chunks that have no ticket of this class any more, until they are unloaded. */
	private final Deque<ChunkPos> unloading = new ArrayDeque<>();
	private @Nullable CompletableFuture<Void> written;
	private int ready;
	private int done;

	/**
	 * True while pre-generation runs. Region files opened then do not sync each write to the disk. On Windows, vanilla
	 * syncs every chunk write, and the about 10000 writes of a pre-generation took more than a minute. The writes are
	 * flushed to the disk when pre-generation ends. The files stay open without the sync until the server closes them,
	 * as on Linux and macOS, where vanilla does not sync.
	 */
	public static volatile boolean unsyncedWrites;

	private CubePregen(ServerLevel level, long[] order) {
		this.level = level;
		this.order = order;
	}

	/** Chunks past each edge of the spawn face that {@link CubeSettings.Pregen#SPAWN_FACE} also generates. */
	public static final int SPAWN_FACE_MARGIN = 4;

	/** Starts recording the chunk status of a new seam level for the loading screen. */
	public static Progress track(ServerLevel level, SeamLayout layout, CubeSettings settings) {
		Progress tracked = new Progress(level, layout, settings);
		progress = tracked;
		return tracked;
	}

	public static void stopTracking() {
		progress = null;
	}

	public static @Nullable Progress progress() {
		return progress;
	}

	/** The pre-generation that the settings ask for, or null for none. */
	public static @Nullable CubePregen start(ServerLevel level, SeamLayout layout, CubeSettings settings, ChunkPos spawn) {
		if (settings.pregen() == CubeSettings.Pregen.OFF) {
			return null;
		}
		return new CubePregen(level, columns(layout, settings.pregen(), layout.faceIndexAt(spawn.getMiddleBlockX())));
	}

	/** Columns that a new world with these settings generates first. */
	public static int count(CubeSettings settings) {
		if (settings.pregen() == CubeSettings.Pregen.OFF) {
			return 0;
		}
		SeamLayout layout = new SeamLayout(settings.diameterChunks());
		int face = settings.spawnFaceIndex();
		return columns(layout, settings.pregen(), face).length;
	}

	/**
	 * Axes of the loading screen view of a cube with this spawn face: x to the right is {@code a}, y down the screen is
	 * {@code -b}, and the spawn face looks toward the viewer.
	 */
	public static Vec3i[] viewAxes(Direction spawnFace) {
		Vec3i n = spawnFace.getUnitVec3i();
		Vec3i a = Arrays.stream(Direction.values()).map(Direction::getUnitVec3i)
			.filter(axis -> axis.getX() * n.getX() + axis.getY() * n.getY() + axis.getZ() * n.getZ() == 0).findFirst().orElseThrow();
		return new Vec3i[] {a, a.cross(n)};
	}

	/**
	 * Columns of the face regions to generate, in a square spiral out from the middle like the vanilla loading map. For
	 * the spawn face, a column is in when its surface point is at most the margin below the plane of the spawn face:
	 * that is the spawn face itself and a strip of each neighbor face along the shared edge. These columns come first,
	 * in a spiral over the loading screen view with the strips unfolded. The rest of a whole cube follows face by face.
	 */
	private static long[] columns(SeamLayout layout, CubeSettings.Pregen pregen, int spawnFace) {
		int side = layout.spacing / 16;
		int[] out = new int[3];
		Vec3i normal = SeamLayout.FACES[spawnFace].getUnitVec3i();
		Vec3i[] axes = viewAxes(SeamLayout.FACES[spawnFace]);
		int r = layout.radius;
		int minHeight = r - SPAWN_FACE_MARGIN * 16;
		LongArrayList columns = new LongArrayList();
		LongArrayList keys = new LongArrayList();
		for (int face = 0; face < SeamLayout.FACES.length; face++) {
			int minX = Math.floorDiv(layout.offsetX(face) - layout.spacing / 2, 16);
			int minZ = Math.floorDiv(-layout.spacing / 2, 16);
			for (int i = 0; i < side; i++) {
				for (int j = 0; j < side; j++) {
					int chunkX = minX + i;
					int chunkZ = minZ + j;
					double[] point = surfacePoint(layout, face, chunkX * 16 + 8, chunkZ * 16 + 8, out);
					double height = dot(point, normal);
					if (pregen == CubeSettings.Pregen.SPAWN_FACE && height < minHeight) {
						continue;
					}
					long key;
					if (height >= minHeight) {
						// The view unfolds a neighbor face into the plane of the spawn face, past the shared edge. A column
						// past the edge of its face keeps its own place here, so that it comes with the cells around it.
						layout.toCube(face, chunkX * 16 + 8, layout.centerY + r, chunkZ * 16 + 8, out);
						double[] place = {out[0], out[1] - layout.centerY, out[2]};
						double placeHeight = dot(place, normal);
						double u = dot(place, axes[0]);
						double v = dot(place, axes[1]);
						u += Math.abs(u) >= r ? Math.signum(u) * (r - placeHeight) : 0.0;
						v += Math.abs(v) >= r ? Math.signum(v) * (r - placeHeight) : 0.0;
						key = spiral(0, Math.floorDiv((int) Math.round(u), 16), Math.floorDiv((int) Math.round(-v), 16));
					} else {
						int rank = face == spawnFace ? 0 : SeamLayout.FACES[face] == SeamLayout.FACES[spawnFace].getOpposite() ? 2 : 1;
						key = spiral(1 + rank * SeamLayout.FACES.length + face, i - side / 2, j - side / 2);
					}
					columns.add(ChunkPos.pack(chunkX, chunkZ));
					keys.add(key);
				}
			}
		}
		Integer[] sorted = new Integer[columns.size()];
		Arrays.setAll(sorted, i -> i);
		Arrays.sort(sorted, Comparator.comparingLong(keys::getLong));
		long[] order = new long[sorted.length];
		for (int i = 0; i < sorted.length; i++) {
			order[i] = columns.getLong(sorted[i]);
		}
		return order;
	}

	/**
	 * Point on the cube surface under a storage column, in cube coordinates relative to the cube center. A column past the edge of its face, in
	 * the atmosphere of the wedge, takes the nearest point of the face, so that it generates with the edge.
	 */
	private static double[] surfacePoint(SeamLayout layout, int face, int sx, int sz, int[] out) {
		int r = layout.radius;
		int x = Math.clamp(sx - layout.offsetX(face), -r, r);
		int z = Math.clamp(sz, -r, r);
		layout.toCube(face, layout.offsetX(face) + x, layout.centerY + r, z, out);
		return new double[] {out[0], out[1] - layout.centerY, out[2]};
	}

	private static double dot(double[] point, Vec3i axis) {
		return point[0] * axis.getX() + point[1] * axis.getY() + point[2] * axis.getZ();
	}

	/**
	 * Sort key of cell ({@code x}, {@code y}) in a group, with the middle of the group between cells -1 and 0. Cells go
	 * by square ring out from the middle, and around each ring clockwise on the screen from the top.
	 */
	private static long spiral(int group, int x, int y) {
		int dx = 2 * x + 1;
		int dy = 2 * y + 1;
		long ring = Math.max(Math.abs(dx), Math.abs(dy));
		double angle = Math.atan2(dx, -dy);
		long turn = Math.round((angle < 0.0 ? angle + 2.0 * Math.PI : angle) / (2.0 * Math.PI) * ((1 << 20) - 1));
		return ((long) group << 40) | (ring << 20) | turn;
	}

	public int total() {
		return this.order.length;
	}

	/** Columns that are generated and ready, before their unload and save. */
	public int ready() {
		return this.ready;
	}

	public int done() {
		return this.done;
	}

	/** State of the queues, for the load trace. */
	public String describe() {
		return "pregen ready=" + this.ready + " done=" + this.done + "/" + this.order.length + " ticketed=" + this.next + " inFlight=" + this.inFlight.size()
			+ " unloading=" + this.unloading.size() + (this.written == null ? "" : " waitingForDiskWrites");
	}

	/**
	 * Adds tickets up to {@link #IN_FLIGHT}, and removes the tickets of ready chunks. A chunk counts as done when it
	 * is unloaded, which saves it. True when all chunks are done and written to disk.
	 */
	public boolean tick() {
		ServerChunkCache chunks = this.level.getChunkSource();
		ChunkMapAccessor chunkMap = (ChunkMapAccessor) chunks.chunkMap;
		this.inFlight.removeIf(pos -> {
			ChunkHolder holder = chunks.chunkMap.getUpdatingChunkIfPresent(pos.pack());
			if (holder != null && holder.getTickingChunk() != null) {
				chunks.removeTicketWithRadius(TICKET, pos, TICKET_RADIUS);
				this.unloading.add(pos);
				this.ready++;
				return true;
			}
			return false;
		});
		this.unloading.removeIf(pos -> {
			if (chunks.chunkMap.getUpdatingChunkIfPresent(pos.pack()) == null && !chunkMap.cubeworld$pendingUnloads().containsKey(pos.pack())) {
				this.done++;
				return true;
			}
			return false;
		});
		while (this.inFlight.size() < IN_FLIGHT && this.next < this.order.length) {
			ChunkPos pos = ChunkPos.unpack(this.order[this.next++]);
			chunks.addTicketWithRadius(TICKET, pos, TICKET_RADIUS);
			this.inFlight.add(pos);
		}
		chunks.tick(() -> true, false);
		if (!this.inFlight.isEmpty() || !this.unloading.isEmpty() || this.next < this.order.length
			|| !chunkMap.cubeworld$pendingUnloads().isEmpty() || !chunkMap.cubeworld$unloadQueue().isEmpty() || !chunkMap.cubeworld$toDrop().isEmpty()) {
			return false;
		}
		// Chunks still waiting for their save held the server after this, and the chunk loads of the first player
		// waited behind them.
		if (this.written == null) {
			this.written = chunks.chunkMap.synchronize(true);
		}
		return this.written.isDone();
	}

	/** Highest generation status seen for each chunk column of the cube, by face. */
	public static final class Progress {
		private final ServerLevel level;
		public final SeamLayout layout;
		public final CubeSettings settings;
		public final int side;
		/** {@link ChunkStatus#getIndex} plus one, 0 for a column that has not started. */
		private final byte[][] status;
		/** Columns of the pre-generation that are generated, for the progress bar. Total is 0 when the world has none. */
		public volatile int pregenDone;
		public volatile int pregenTotal;
		/** Set when the pre-generated chunks are also saved. The cube on the loading screen folds then. */
		public volatile boolean pregenFinished;
		private long lastPoll;
		private static final long POLL_INTERVAL_MS = 100L;

		private Progress(ServerLevel level, SeamLayout layout, CubeSettings settings) {
			this.level = level;
			this.layout = layout;
			this.settings = settings;
			this.side = layout.spacing / 16;
			this.status = new byte[SeamLayout.FACES.length][this.side * this.side];
		}

		/**
		 * Reads the status of every column that has a chunk holder. The loading screen calls it on the render thread.
		 * It reads the chunk holders that the server shows to other threads, as the vanilla loading map does, so it
		 * also runs while the server thread waits for chunks, as in the spawn search.
		 */
		public void poll() {
			long now = Util.getMillis();
			if (now - this.lastPoll < POLL_INTERVAL_MS) {
				return;
			}
			this.lastPoll = now;
			ServerChunkCache chunks = this.level.getChunkSource();
			int minZ = Math.floorDiv(-this.layout.spacing / 2, 16);
			for (int face = 0; face < this.status.length; face++) {
				int minX = Math.floorDiv(this.layout.offsetX(face) - this.layout.spacing / 2, 16);
				byte[] faceStatus = this.status[face];
				for (int i = 0; i < this.side; i++) {
					for (int j = 0; j < this.side; j++) {
						ChunkStatus latest = chunks.chunkMap.getLatestStatus(ChunkPos.pack(minX + i, minZ + j));
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
