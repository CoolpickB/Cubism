package dev.cubeworld.mixin;

import dev.cubeworld.worldgen.CubePregen;
import dev.cubeworld.worldgen.CubeSettings;
import dev.cubeworld.worldgen.SeamLayout;
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

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Unique
	private static final Logger CUBEWORLD$LOGGER = LoggerFactory.getLogger("cubeworld");

	@Shadow
	@Final
	private static long PREPARE_LEVELS_DEFAULT_DELAY_NANOS;

	@Shadow
	private long nextTickTimeNanos;

	@Shadow
	@Final
	private LevelLoadListener levelLoadListener;

	@Unique
	private boolean cubeworld$newWorld;

	@Shadow
	public abstract ServerLevel overworld();

	@Shadow
	protected abstract void waitUntilNextTick();

	@Shadow
	protected abstract GlobalPos selectLevelLoadFocusPos();

	/** Spawn is chosen only when the world is new. Pre-generation runs only then. */
	@Inject(method = "createLevels", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;setInitialSpawn(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/storage/ServerLevelData;ZZLnet/minecraft/server/level/progress/LevelLoadListener;)V"))
	private void cubeworld$markNewWorld(CallbackInfo ci) {
		this.cubeworld$newWorld = true;
	}

	@Inject(method = "createLevels", at = @At("TAIL"))
	private void cubeworld$trackProgress(CallbackInfo ci) {
		SeamLayout layout = SeamLayout.of(this.overworld());
		if (layout != null) {
			CubePregen.track(layout, CubeSettings.of(this.overworld().getChunkSource().getGenerator()).spawnFace());
		}
	}

	/**
	 * Runs like the vanilla wait for the initial chunks that follows it: the server thread runs chunk tasks between
	 * steps, and the load listener shows the progress. A world that stops before this is done loads normally after.
	 */
	@Inject(method = "prepareLevels", at = @At("HEAD"))
	private void cubeworld$pregenerate(CallbackInfo ci) {
		ServerLevel level = this.overworld();
		SeamLayout layout = SeamLayout.of(level);
		if (!this.cubeworld$newWorld || layout == null) {
			return;
		}
		ChunkPos spawn = ChunkPos.containing(this.selectLevelLoadFocusPos().pos());
		CubePregen pregen = CubePregen.start(level, layout, CubeSettings.of(level.getChunkSource().getGenerator()), spawn);
		if (pregen == null) {
			return;
		}
		CUBEWORLD$LOGGER.info("Generating {} chunk columns of the cube before the first player joins", pregen.total());
		long start = Util.getMillis();
		long lastLog = start;
		this.levelLoadListener.start(LevelLoadListener.Stage.LOAD_INITIAL_CHUNKS, pregen.total());
		while (!pregen.tick()) {
			this.levelLoadListener.update(LevelLoadListener.Stage.LOAD_INITIAL_CHUNKS, pregen.done(), pregen.total());
			if (Util.getMillis() - lastLog > 5000L) {
				lastLog = Util.getMillis();
				CUBEWORLD$LOGGER.info("Generated {} of {} chunk columns", pregen.done(), pregen.total());
			}
			this.nextTickTimeNanos = Util.getNanos() + PREPARE_LEVELS_DEFAULT_DELAY_NANOS;
			this.waitUntilNextTick();
		}
		this.levelLoadListener.finish(LevelLoadListener.Stage.LOAD_INITIAL_CHUNKS);
		CUBEWORLD$LOGGER.info("Generated {} chunk columns in {} s", pregen.total(), (Util.getMillis() - start) / 1000L);
	}

	@Inject(method = "prepareLevels", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;waitUntilNextTick()V"))
	private void cubeworld$pollInitialChunks(CallbackInfo ci) {
		CubePregen.Progress progress = CubePregen.progress();
		if (progress != null) {
			progress.poll(this.overworld());
		}
	}

	@Inject(method = "stopServer", at = @At("TAIL"))
	private void cubeworld$stopTracking(CallbackInfo ci) {
		CubePregen.stopTracking();
	}
}
