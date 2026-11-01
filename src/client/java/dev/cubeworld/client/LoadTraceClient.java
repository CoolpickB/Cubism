package dev.cubeworld.client;

import dev.cubeworld.client.gui.CubeLoadingView;
import dev.cubeworld.debug.LoadTrace;
import dev.cubeworld.worldgen.CubePregen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The client part of the {@link LoadTrace}: frame times, what the loading screen shows, and chunk rendering after it. */
public final class LoadTraceClient {
	/** How long the trace runs on after the loading screen closes. */
	private static final long AFTER_LOADING_MS = 120_000L;
	private static final long LOADING_SAMPLE_MS = 500L;
	private static final long SAMPLE_MS = 1000L;
	private static final long SLOW_FRAME_NANOS = 50_000_000L;

	public static final AtomicInteger CHUNK_PACKETS = new AtomicInteger();
	public static final AtomicInteger COMPILED = new AtomicInteger();
	private static final Map<String, AtomicInteger> COUNTS = new ConcurrentHashMap<>();

	/** What the loading screen drew last, set by the screen. */
	public static String view = "none";
	public static CubePregen.@Nullable Progress viewProgress;
	public static @Nullable ChunkLoadStatusView viewStatus;
	public static float bar;
	public static float serverProgress;
	private static boolean playerSectionLogged;

	private static long lastFrame;
	private static long lastSample;
	private static int frames;
	private static long frameNanos;
	private static long maxFrameNanos;
	private static int slowFrames;

	private LoadTraceClient() {
	}

	/** Counts one event under a name. The sample line of each second lists the counts and starts them again. */
	public static void count(String name) {
		if (LoadTrace.active()) {
			COUNTS.computeIfAbsent(name, key -> new AtomicInteger()).incrementAndGet();
		}
	}

	public static void begin(String why) {
		LoadTrace.begin(why);
		reset();
	}

	public static void beginIfIdle(String why) {
		if (!LoadTrace.active()) {
			begin(why);
		}
	}

	private static void reset() {
		view = "none";
		viewProgress = null;
		viewStatus = null;
		bar = 0.0F;
		serverProgress = 0.0F;
		playerSectionLogged = false;
		lastFrame = 0L;
		lastSample = Util.getMillis();
		frames = 0;
		frameNanos = 0L;
		maxFrameNanos = 0L;
		slowFrames = 0;
		CHUNK_PACKETS.set(0);
		COMPILED.set(0);
	}

	public static void loadingScreenClosed() {
		LoadTrace.event("client: loading screen closed");
		LoadTrace.endAfter(AFTER_LOADING_MS);
	}

	/** The vanilla tracker lets the screen close once the section of the player has a mesh. Logged once per trace. */
	public static void playerSectionReady() {
		if (!playerSectionLogged) {
			playerSectionLogged = true;
			LoadTrace.event("client: section of the player compiled");
		}
	}

	/** Called at the start of every frame. */
	public static void frame(Minecraft minecraft) {
		if (!LoadTrace.active()) {
			return;
		}
		LoadTrace.checkEnd();
		long now = Util.getNanos();
		if (lastFrame != 0L) {
			long nanos = now - lastFrame;
			frames++;
			frameNanos += nanos;
			maxFrameNanos = Math.max(maxFrameNanos, nanos);
			if (nanos >= SLOW_FRAME_NANOS) {
				slowFrames++;
			}
		}
		lastFrame = now;
		Screen screen = minecraft.gui.screen();
		long millis = Util.getMillis();
		if (millis - lastSample < (screen instanceof LevelLoadingScreen ? LOADING_SAMPLE_MS : SAMPLE_MS) || frames == 0) {
			return;
		}
		float seconds = (millis - lastSample) / 1000.0F;
		lastSample = millis;
		StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "client fps=%.0f frame avg=%.1f max=%.1f ms slow=%d screen=%s",
			frames / seconds, frameNanos / 1e6 / frames, maxFrameNanos / 1e6, slowFrames, screen == null ? "none" : screen.getClass().getSimpleName()));
		frames = 0;
		frameNanos = 0L;
		maxFrameNanos = 0L;
		slowFrames = 0;
		if (screen instanceof LevelLoadingScreen) {
			line.append(String.format(Locale.ROOT, " | view=%s bar=%.3f serverProgress=%.3f", view, bar, serverProgress));
			if (viewProgress != null) {
				line.append(String.format(Locale.ROOT, " pregen=%d/%d fold=%.2f shown=%s",
					viewProgress.pregenDone, viewProgress.pregenTotal, CubeLoadingView.lastFold, LoadTrace.histogram(CubeLoadingView.shownCounts(viewProgress))));
			} else if (viewStatus != null) {
				line.append(" shown=").append(LoadTrace.histogram(counts(viewStatus)));
			}
		}
		if (minecraft.level != null) {
			SectionRenderDispatcher dispatcher = minecraft.levelRenderer.sectionRenderDispatcher();
			line.append(" | chunks=").append(minecraft.level.getChunkSource().getLoadedChunksCount())
				.append(" chunkPackets=").append(CHUNK_PACKETS.getAndSet(0))
				.append(" compiled=").append(COMPILED.getAndSet(0))
				.append(" compileQueue=").append(dispatcher == null ? -1 : dispatcher.getCompileQueueSize())
				.append(" visibleSections=").append(minecraft.levelRenderer.visibleSections().size())
				.append(" allRendered=").append(minecraft.levelRenderer.hasRenderedAllSections());
			if (minecraft.player != null) {
				line.append(" player=").append(minecraft.player.blockPosition().toShortString());
			}
		}
		line.append(' ').append(LoadTrace.memory());
		LoadTrace.event(line.toString());
	}

	private static int[] counts(ChunkLoadStatusView status) {
		int[] counts = new int[ChunkStatus.getStatusList().size() + 1];
		int diameter = status.radius() * 2 + 1;
		for (int x = 0; x < diameter; x++) {
			for (int z = 0; z < diameter; z++) {
				ChunkStatus value = status.get(x, z);
				counts[value == null ? 0 : value.getIndex() + 1]++;
			}
		}
		return counts;
	}
}
