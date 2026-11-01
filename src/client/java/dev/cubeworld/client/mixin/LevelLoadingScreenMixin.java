package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.client.LoadTraceClient;
import dev.cubeworld.client.gui.CubeLoadingView;
import dev.cubeworld.worldgen.CubeSettings;
import dev.cubeworld.debug.LoadTrace;
import dev.cubeworld.worldgen.CubePregen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a new cube world of this game pre-generates, the loading screen draws the cube in place of the square map
 * around the spawn. The progress comes from the integrated server, so a world on another server keeps the vanilla map.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {
	/** How far up the title and the progress bar move, because the cube is taller than the vanilla map. */
	@Unique
	private int cubeworld$textShift;

	@Unique
	private static final float PREGEN_SHARE = 0.9F;

	@Shadow
	private LevelLoadingScreen.Reason reason;

	@Shadow
	private LevelLoadTracker loadTracker;

	@Unique
	private boolean cubeworld$levelReady;

	@Unique
	private boolean cubeworld$mayClose;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void cubeworld$traceOpen(CallbackInfo ci) {
		LoadTrace.event("client: loading screen opened, reason " + this.reason);
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void cubeworld$traceUpdate(CallbackInfo ci) {
		LoadTrace.event("client: loading screen reused, reason " + this.reason);
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;extractChunksForRendering(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIILnet/minecraft/server/level/progress/ChunkLoadStatusView;)V"))
	private void cubeworld$drawCube(
		GuiGraphicsExtractor graphics, int xCenter, int yCenter, int size, int margin, ChunkLoadStatusView statusView, Operation<Void> original
	) {
		CubePregen.Progress progress = this.cubeworld$pregen();
		if (progress == null) {
			this.cubeworld$textShift = 0;
			original.call(graphics, xCenter, yCenter, size, margin, statusView);
			cubeworld$traceView("vanilla map", null, statusView);
			return;
		}
		cubeworld$traceView("cube", progress, null);
		int halfHeight = CubeLoadingView.draw(graphics, progress, xCenter, yCenter, graphics.guiHeight() / 2 - 45);
		this.cubeworld$textShift = halfHeight - statusView.radius() * 2;
	}

	@Unique
	private static void cubeworld$traceView(String view, CubePregen.@Nullable Progress progress, @Nullable ChunkLoadStatusView status) {
		if (!view.equals(LoadTraceClient.view)) {
			LoadTrace.event("client: visualizer shows " + view);
		}
		LoadTraceClient.view = view;
		LoadTraceClient.viewProgress = progress;
		LoadTraceClient.viewStatus = status;
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void cubeworld$raiseTitle(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color, Operation<Void> original) {
		original.call(graphics, font, text, x, y - this.cubeworld$textShift, color);
	}

	@ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;drawProgressBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIF)V"), index = 2)
	private int cubeworld$raiseProgressBar(int top) {
		return top - this.cubeworld$textShift;
	}

	/**
	 * The vanilla tracker counts the chunks of the spawn area from the start of their stage. Pre-generation comes
	 * before that stage, so it has its own share of the bar.
	 */
	@ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;drawProgressBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIF)V"), index = 5)
	private float cubeworld$addPregenProgress(float vanilla) {
		CubePregen.Progress progress = this.cubeworld$pregen();
		if (progress == null) {
			LoadTraceClient.bar = vanilla;
			return vanilla;
		}
		int total = progress.pregenTotal;
		int done = progress.pregenDone;
		float pregen = progress.settings.pregen() == CubeSettings.Pregen.SPAWN_FACE ? CubeLoadingView.fullShare(progress) : (float) done / total;
		float bar = done < total ? PREGEN_SHARE * pregen : PREGEN_SHARE + (1.0F - PREGEN_SHARE) * vanilla;
		LoadTraceClient.bar = bar;
		return bar;
	}

	/**
	 * The pre-generation of a new cube world in this game, or null. Portal travel loads another dimension, or the
	 * overworld around a portal, and a world without pre-generation loads only the spawn area: those keep the vanilla map.
	 */
	@Unique
	private CubePregen.@Nullable Progress cubeworld$pregen() {
		CubePregen.Progress progress = this.reason == LevelLoadingScreen.Reason.OTHER ? CubePregen.progress() : null;
		return progress != null && progress.pregenTotal > 0 ? progress : null;
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/LevelLoadTracker;isLevelReady()Z"))
	private boolean cubeworld$waitForFold(LevelLoadTracker tracker, Operation<Boolean> original) {
		CubePregen.Progress progress = this.cubeworld$pregen();
		boolean ready = original.call(tracker);
		boolean mayClose = ready && (progress == null || !CubeLoadingView.isFolding(progress));
		if (ready && !this.cubeworld$levelReady) {
			LoadTrace.event("client: vanilla tracker says the level is ready");
		}
		if (mayClose && !this.cubeworld$mayClose) {
			LoadTrace.event("client: loading screen may close");
		}
		this.cubeworld$levelReady = ready;
		this.cubeworld$mayClose = mayClose;
		return mayClose;
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void cubeworld$poll(CallbackInfo ci) {
		LoadTraceClient.serverProgress = this.loadTracker.serverProgress();
		CubePregen.Progress progress = CubePregen.progress();
		if (progress != null) {
			progress.poll();
		}
	}

	@Inject(method = "onClose", at = @At("HEAD"))
	private void cubeworld$traceClose(CallbackInfo ci) {
		LoadTraceClient.loadingScreenClosed();
	}

}
