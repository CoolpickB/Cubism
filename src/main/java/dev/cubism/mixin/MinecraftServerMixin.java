package dev.cubism.mixin;

import dev.cubism.debug.LoadTrace;
import dev.cubism.worldgen.CubePregen;
import dev.cubism.worldgen.CubeSettings;
import dev.cubism.worldgen.PlanetMap;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Unique
	private static final Logger cubism$LOGGER = LoggerFactory.getLogger("cubism");

	@Shadow
	@Final
	private static long PREPARE_LEVELS_DEFAULT_DELAY_NANOS;

	@Shadow
	private long nextTickTimeNanos;

	@Shadow
	@Final
	private LevelLoadListener levelLoadListener;

	@Unique
	private boolean cubism$newWorld;

	@Unique
	private long cubism$tickStart;

	@Shadow
	public abstract ServerLevel overworld();

	@Shadow
	protected abstract void waitUntilNextTick();

	@Shadow
	public abstract boolean isRunning();

	@Shadow
	protected abstract GlobalPos selectLevelLoadFocusPos();

	@Inject(method = "loadLevel", at = @At("HEAD"))
	private void cubism$traceLoadLevel(CallbackInfo ci) {
		// A dedicated server has no client to start the trace.
		LoadTrace.beginIfIdle("server loads the level");
		LoadTrace.event("server: loadLevel start");
	}

	@Inject(method = "createLevels", at = @At("HEAD"))
	private void cubism$traceCreateLevels(CallbackInfo ci) {
		LoadTrace.event("server: createLevels start");
	}

	/** Spawn is chosen only when the world is new. Pre-generation runs only then. */
	@Inject(method = "createLevels", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;setInitialSpawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/storage/ServerLevelData;ZZLnet/minecraft/server/level/progress/LevelLoadListener;)V"))
	private void cubism$markNewWorld(CallbackInfo ci) {
		this.cubism$newWorld = true;
		LoadTrace.event("server: new world, choosing the spawn");
		// The loading screen shows the cube already while the spawn search generates its first chunks.
		SeamLayout layout = SeamLayout.of(this.overworld());
		if (layout != null) {
			CubeSettings settings = CubeSettings.of(this.overworld().getChunkSource().getGenerator());
			CubePregen.track(this.overworld(), layout, settings).pregenTotal = CubePregen.count(settings);
		}
	}

	@Inject(method = "createLevels", at = @At("TAIL"))
	private void cubism$trackProgress(CallbackInfo ci) {
		SeamLayout layout = SeamLayout.of(this.overworld());
		if (layout != null) {
			CubeSettings settings = CubeSettings.of(this.overworld().getChunkSource().getGenerator());
			LoadTrace.event("server: createLevels done, cube " + settings + ", spawn " + this.selectLevelLoadFocusPos().pos());
		} else {
			LoadTrace.event("server: createLevels done, not a cube world");
		}
	}

	/**
	 * Runs like the vanilla wait for the initial chunks that follows it: the server thread runs chunk tasks between
	 * steps, and the load listener shows the progress. A world that stops before this is done loads normally after.
	 */
	@Inject(method = "prepareLevels", at = @At("HEAD"))
	private void cubism$pregenerate(CallbackInfo ci) {
		ServerLevel level = this.overworld();
		SeamLayout layout = SeamLayout.of(level);
		if (layout == null) {
			return;
		}
		// The map of the planet for the view from space is made on its own thread while the chunks load. A new world
		// waits for it, so the planet shows from the first join.
		CompletableFuture<byte[]> planet = PlanetMap.of(level);
		if (!this.cubism$newWorld) {
			return;
		}
		ChunkPos spawn = ChunkPos.containing(this.selectLevelLoadFocusPos().pos());
		CubePregen pregen = CubePregen.start(level, layout, CubeSettings.of(level.getChunkSource().getGenerator()), spawn);
		if (pregen == null) {
			return;
		}
		cubism$LOGGER.info("Generating {} chunk columns of the cube before the first player joins", pregen.total());
		long start = Util.getMillis();
		long lastLog = start;
		// The vanilla tracker does not count this stage. It only makes the loading screen show the progress bar, and the
		// screen fills the bar from the counts in Progress. LOAD_INITIAL_CHUNKS here pushed the bar past its end.
		this.levelLoadListener.start(LevelLoadListener.Stage.PREPARE_GLOBAL_SPAWN, pregen.total());
		CubePregen.Progress progress = CubePregen.progress();
		if (progress != null) {
			progress.pregenTotal = pregen.total();
		}
		LoadTrace.event("server: pre-generation start, " + pregen.total() + " columns");
		CubePregen.unsyncedWrites = true;
		try {
			while (!pregen.tick() || !planet.isDone()) {
				if (!this.isRunning()) {
					return;
				}
				LoadTrace.serverSample(level, "pre-generation", pregen.describe());
				if (progress != null) {
					progress.pregenDone = pregen.ready();
				}
				if (Util.getMillis() - lastLog > 5000L) {
					lastLog = Util.getMillis();
					cubism$LOGGER.info("Generated {} of {} chunk columns", pregen.done(), pregen.total());
				}
				this.nextTickTimeNanos = Util.getNanos() + PREPARE_LEVELS_DEFAULT_DELAY_NANOS;
				this.waitUntilNextTick();
			}
		} finally {
			CubePregen.unsyncedWrites = false;
		}
		if (progress != null) {
			progress.pregenDone = pregen.total();
			progress.pregenFinished = true;
		}
		this.levelLoadListener.finish(LevelLoadListener.Stage.PREPARE_GLOBAL_SPAWN);
		LoadTrace.event("server: pre-generation done in " + (Util.getMillis() - start) + " ms");
		cubism$LOGGER.info("Generated {} chunk columns in {} s", pregen.total(), (Util.getMillis() - start) / 1000L);
	}

	@Inject(method = "prepareLevels", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;waitUntilNextTick()V"))
	private void cubism$pollInitialChunks(CallbackInfo ci) {
		LoadTrace.serverSample(this.overworld(), "initial chunks", null);
	}

	@Inject(method = "prepareLevels", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/progress/LevelLoadListener;start(Lnet/minecraft/server/level/progress/LevelLoadListener$Stage;I)V"))
	private void cubism$traceInitialChunks(CallbackInfo ci) {
		LoadTrace.event("server: vanilla initial chunks start");
	}

	@Inject(method = "prepareLevels", at = @At("TAIL"))
	private void cubism$traceReady(CallbackInfo ci) {
		LoadTrace.event("server: initial chunks done, server ready");
	}

	@Inject(method = "tickServer", at = @At("HEAD"))
	private void cubism$traceTickStart(BooleanSupplier haveTime, CallbackInfo ci) {
		this.cubism$tickStart = Util.getNanos();
		LoadTrace.serverTickStart();
	}

	@Inject(method = "tickServer", at = @At("TAIL"))
	private void cubism$traceTickEnd(BooleanSupplier haveTime, CallbackInfo ci) {
		LoadTrace.serverTick(this.overworld(), Util.getNanos() - this.cubism$tickStart);
	}

	@Inject(method = "stopServer", at = @At("TAIL"))
	private void cubism$stopTracking(CallbackInfo ci) {
		CubePregen.stopTracking();
		SeamLayout.clearServerLevels();
		LoadTrace.end("server stopped");
	}
}
