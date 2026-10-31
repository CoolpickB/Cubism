package dev.cubeworld.debug;

import dev.cubeworld.mixin.ChunkMapAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A timeline of one world load, from the click on Create World until some time after the loading screen closes. It
 * goes to its own file, {@code logs/cubeworld-load-<time>.log}, with the time since the start on each line. The client
 * and the server both write to it: events when they happen, and samples once a second.
 */
public final class LoadTrace {
	private static final Logger LOGGER = LoggerFactory.getLogger("cubeworld");
	private static final long SERVER_SAMPLE_MS = 1000L;
	/** A server tick this long or longer is logged as an event of its own. */
	private static final long SLOW_TICK_MS = 100L;

	public static final AtomicInteger LOADS = new AtomicInteger();
	public static final AtomicInteger TERRAIN = new AtomicInteger();
	public static final AtomicInteger FEATURES = new AtomicInteger();
	public static final AtomicInteger LIGHT = new AtomicInteger();
	public static final AtomicInteger FULL = new AtomicInteger();
	public static final AtomicInteger SAVES = new AtomicInteger();

	private static volatile @Nullable PrintWriter out;
	private static long startNanos;
	/** When the trace closes, by {@link Util#getMillis}, or 0 while it runs on. */
	private static volatile long endAt;

	/** A server tick that runs this long gets the stack of the server thread in the trace, and again every interval after. */
	private static final long STACK_AFTER_MS = 1000L;
	private static final long STACK_INTERVAL_MS = 2000L;
	private static final int STACK_DEPTH = 40;

	private static long lastServerSample;
	/** Start of the server tick that runs, by {@link Util#getNanos}, or 0 between ticks. */
	private static volatile long tickStart;
	private static volatile @Nullable Thread serverThread;
	private static @Nullable Thread watchdog;
	private static int ticks;
	private static long tickNanos;
	private static long maxTickNanos;

	private LoadTrace() {
	}

	public static boolean active() {
		return out != null;
	}

	/** Starts a new trace file. A trace that still runs ends first. */
	public static synchronized void begin(String why) {
		end("a new trace started");
		String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
		Path file = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("cubeworld-load-" + time + ".log");
		try {
			Files.createDirectories(file.getParent());
			out = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8), true);
		} catch (IOException e) {
			LOGGER.warn("Could not open the load trace {}", file, e);
			return;
		}
		startNanos = Util.getNanos();
		endAt = 0L;
		lastServerSample = 0L;
		ticks = 0;
		tickNanos = 0L;
		maxTickNanos = 0L;
		for (AtomicInteger counter : List.of(LOADS, TERRAIN, FEATURES, LIGHT, FULL, SAVES)) {
			counter.set(0);
		}
		LOGGER.info("Writing the load trace to {}", file);
		event("trace start: " + why);
	}

	/** Starts a trace unless one runs. */
	public static synchronized void beginIfIdle(String why) {
		if (out == null) {
			begin(why);
		}
	}

	public static synchronized void end(String why) {
		if (out != null) {
			event("trace end: " + why);
			out.close();
			out = null;
		}
	}

	/** Lets the trace run this much longer, then ends it. */
	public static void endAfter(long ms) {
		endAt = Util.getMillis() + ms;
	}

	/** Ends the trace when its time from {@link #endAfter} is over. */
	public static void checkEnd() {
		if (endAt != 0L && Util.getMillis() >= endAt) {
			end("time is over");
		}
	}

	public static synchronized void event(String message) {
		if (out != null) {
			long micros = (Util.getNanos() - startNanos) / 1000L;
			out.printf(Locale.ROOT, "%4d.%03d [%s] %s%n", micros / 1_000_000L, micros / 1000L % 1000L, Thread.currentThread().getName(), message);
		}
	}

	/** Called when a server tick starts. A watchdog thread logs where a long tick spends its time. */
	public static synchronized void serverTickStart() {
		if (out == null) {
			return;
		}
		serverThread = Thread.currentThread();
		tickStart = Util.getNanos();
		if (watchdog == null) {
			watchdog = new Thread(LoadTrace::watch, "Cube World load trace watchdog");
			watchdog.setDaemon(true);
			watchdog.start();
		}
	}

	private static void watch() {
		long watched = 0L;
		long nextDumpMs = STACK_AFTER_MS;
		while (out != null) {
			try {
				Thread.sleep(100L);
			} catch (InterruptedException e) {
				break;
			}
			long start = tickStart;
			Thread thread = serverThread;
			if (start == 0L || thread == null) {
				continue;
			}
			if (start != watched) {
				watched = start;
				nextDumpMs = STACK_AFTER_MS;
			}
			long ms = (Util.getNanos() - start) / 1_000_000L;
			if (ms >= nextDumpMs) {
				nextDumpMs += STACK_INTERVAL_MS;
				StringBuilder stack = new StringBuilder("server tick runs for " + ms + " ms, server thread at:");
				StackTraceElement[] frames = thread.getStackTrace();
				for (int i = 0; i < Math.min(frames.length, STACK_DEPTH); i++) {
					stack.append("\n        at ").append(frames[i]);
				}
				event(stack.toString());
			}
		}
		synchronized (LoadTrace.class) {
			watchdog = null;
		}
	}

	/** Records one server tick. Ticks before the first player joins come only from the pre-generation loops. */
	public static void serverTick(ServerLevel level, long nanos) {
		tickStart = 0L;
		if (out == null) {
			return;
		}
		ticks++;
		tickNanos += nanos;
		maxTickNanos = Math.max(maxTickNanos, nanos);
		if (nanos >= SLOW_TICK_MS * 1_000_000L) {
			event(String.format(Locale.ROOT, "slow server tick %.1f ms", nanos / 1e6));
		}
		serverSample(level, "running", null);
	}

	/** Writes a server sample when the last one is a second old. */
	public static void serverSample(ServerLevel level, String phase, @Nullable String extra) {
		if (out == null) {
			return;
		}
		checkEnd();
		long now = Util.getMillis();
		if (now - lastServerSample < SERVER_SAMPLE_MS) {
			return;
		}
		lastServerSample = now;
		ChunkMapAccessor chunkMap = (ChunkMapAccessor) level.getChunkSource().chunkMap;
		List<ChunkStatus> statuses = ChunkStatus.getStatusList();
		int[] counts = new int[statuses.size() + 1];
		int holders = 0;
		for (ChunkHolder holder : chunkMap.cubeworld$visibleChunkMap().values()) {
			ChunkStatus latest = holder.getLatestStatus();
			counts[latest == null ? 0 : latest.getIndex() + 1]++;
			holders++;
		}
		StringBuilder line = new StringBuilder("server ").append(phase);
		if (extra != null) {
			line.append(" | ").append(extra);
		}
		line.append(" | holders=").append(holders).append(' ').append(histogram(counts));
		line.append(" pendingUnloads=").append(chunkMap.cubeworld$pendingUnloads().size()).append(" toDrop=").append(chunkMap.cubeworld$toDrop().size());
		line.append(" | per s: loadsScheduled=").append(LOADS.getAndSet(0)).append(" terrain=").append(TERRAIN.getAndSet(0))
			.append(" features=").append(FEATURES.getAndSet(0)).append(" light=").append(LIGHT.getAndSet(0))
			.append(" full=").append(FULL.getAndSet(0)).append(" saved=").append(SAVES.getAndSet(0));
		if (ticks > 0) {
			line.append(String.format(Locale.ROOT, " | ticks=%d mspt avg=%.1f max=%.1f", ticks, tickNanos / 1e6 / ticks, maxTickNanos / 1e6));
		}
		line.append(" players=").append(level.getServer().getPlayerCount()).append(' ').append(memory());
		ticks = 0;
		tickNanos = 0L;
		maxTickNanos = 0L;
		event(line.toString());
	}

	/** Counts by status, from {@link ChunkStatus#getIndex} plus one, with 0 for none. Statuses with no count are left out. */
	public static String histogram(int[] counts) {
		List<ChunkStatus> statuses = ChunkStatus.getStatusList();
		StringBuilder line = new StringBuilder("[");
		for (int i = 0; i < counts.length; i++) {
			if (counts[i] > 0) {
				if (line.length() > 1) {
					line.append(' ');
				}
				line.append(i == 0 ? "none" : statuses.get(i - 1).getName().replace("minecraft:", "")).append('=').append(counts[i]);
			}
		}
		return line.append(']').toString();
	}

	public static String memory() {
		Runtime runtime = Runtime.getRuntime();
		return "mem=" + (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024) + "/" + runtime.maxMemory() / (1024 * 1024) + "MB";
	}
}
