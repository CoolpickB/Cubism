package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.cubeworld.client.LoadTraceClient;
import dev.cubeworld.debug.LoadTrace;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.server.level.progress.LevelLoadListener;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs the stages of a world load to the {@link LoadTrace}. */
@Mixin(LevelLoadTracker.class)
public abstract class LevelLoadTrackerMixin {
	@Inject(method = "start", at = @At("HEAD"))
	private void cubeworld$traceStart(LevelLoadListener.Stage stage, int totalChunks, CallbackInfo ci) {
		LoadTrace.event("load tracker: stage " + stage + " starts with " + totalChunks + " chunks");
	}

	@Inject(method = "finish", at = @At("HEAD"))
	private void cubeworld$traceFinish(LevelLoadListener.Stage stage, CallbackInfo ci) {
		LoadTrace.event("load tracker: stage " + stage + " done");
	}

	@Inject(method = "startClientLoad", at = @At("HEAD"))
	private void cubeworld$traceClientLoad(CallbackInfo ci) {
		LoadTrace.event("load tracker: client level created, waiting for the server");
	}

	@Inject(method = "loadingPacketsReceived", at = @At("HEAD"))
	private void cubeworld$tracePackets(CallbackInfo ci) {
		LoadTrace.event("load tracker: loading packets received, waiting for the section of the player");
	}

	@ModifyReturnValue(method = "getPlayerCompiledSectionCallback", at = @At("RETURN"))
	private @Nullable Runnable cubeworld$tracePlayerSection(@Nullable Runnable callback) {
		return callback == null ? null : () -> {
			LoadTraceClient.playerSectionReady();
			callback.run();
		};
	}
}
