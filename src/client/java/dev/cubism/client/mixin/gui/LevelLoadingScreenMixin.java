package dev.cubism.client.mixin.gui;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.client.debug.LoadTraceClient;
import dev.cubism.client.gui.CubeLoadingView;
import dev.cubism.worldgen.CubeSettings;
import dev.cubism.debug.LoadTrace;
import dev.cubism.worldgen.CubePregen;
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
	private int cubism$textShift;

	@Unique
	private static final float PREGEN_SHARE = 0.9F;

	@Shadow
	private LevelLoadingScreen.Reason reason;

	@Shadow
	private LevelLoadTracker loadTracker;

	@Unique
	private boolean cubism$levelReady;

	@Unique
	private boolean cubism$mayClose;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void cubism$traceOpen(CallbackInfo ci) {
		LoadTrace.event("client: loading screen opened, reason " + this.reason);
	}

	@Inject(method = "update", at = @At("TAIL"))
	private void cubism$traceUpdate(CallbackInfo ci) {
		LoadTrace.event("client: loading screen reused, reason " + this.reason);
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;extractChunksForRendering(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIILnet/minecraft/server/level/progress/ChunkLoadStatusView;)V"))
	private void cubism$drawCube(
		GuiGraphicsExtractor graphics, int xCenter, int yCenter, int size, int margin, ChunkLoadStatusView statusView, Operation<Void> original
	) {
		CubePregen.Progress progress = this.cubism$pregen();
		if (progress == null) {
			this.cubism$textShift = 0;
			original.call(graphics, xCenter, yCenter, size, margin, statusView);
			cubism$traceView("vanilla map", null, statusView);
			return;
		}
		cubism$traceView("cube", progress, null);
		int halfHeight = CubeLoadingView.draw(graphics, progress, xCenter, yCenter, graphics.guiHeight() / 2 - 45);
		this.cubism$textShift = halfHeight - statusView.radius() * 2;
	}

	@Unique
	private static void cubism$traceView(String view, CubePregen.@Nullable Progress progress, @Nullable ChunkLoadStatusView status) {
		if (!view.equals(LoadTraceClient.view)) {
			LoadTrace.event("client: visualizer shows " + view);
		}
		LoadTraceClient.view = view;
		LoadTraceClient.viewProgress = progress;
		LoadTraceClient.viewStatus = status;
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void cubism$raiseTitle(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color, Operation<Void> original) {
		original.call(graphics, font, text, x, y - this.cubism$textShift, color);
	}

	@ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;drawProgressBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIF)V"), index = 2)
	private int cubism$raiseProgressBar(int top) {
		return top - this.cubism$textShift;
	}

	/**
	 * The vanilla tracker counts the chunks of the spawn area from the start of their stage. Pre-generation comes
	 * before that stage, so it has its own share of the bar.
	 */
	@ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;drawProgressBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIF)V"), index = 5)
	private float cubism$addPregenProgress(float vanilla) {
		CubePregen.Progress progress = this.cubism$pregen();
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
	private CubePregen.@Nullable Progress cubism$pregen() {
		CubePregen.Progress progress = this.reason == LevelLoadingScreen.Reason.OTHER ? CubePregen.progress() : null;
		return progress != null && progress.pregenTotal > 0 ? progress : null;
	}

	@WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/LevelLoadTracker;isLevelReady()Z"))
	private boolean cubism$waitForFold(LevelLoadTracker tracker, Operation<Boolean> original) {
		CubePregen.Progress progress = this.cubism$pregen();
		boolean ready = original.call(tracker);
		boolean mayClose = ready && (progress == null || !CubeLoadingView.isFolding(progress));
		if (ready && !this.cubism$levelReady) {
			LoadTrace.event("client: vanilla tracker says the level is ready");
		}
		if (mayClose && !this.cubism$mayClose) {
			LoadTrace.event("client: loading screen may close");
		}
		this.cubism$levelReady = ready;
		this.cubism$mayClose = mayClose;
		return mayClose;
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void cubism$poll(CallbackInfo ci) {
		LoadTraceClient.serverProgress = this.loadTracker.serverProgress();
		CubePregen.Progress progress = CubePregen.progress();
		if (progress != null) {
			progress.poll();
		}
	}

	@Inject(method = "onClose", at = @At("HEAD"))
	private void cubism$traceClose(CallbackInfo ci) {
		LoadTraceClient.loadingScreenClosed();
	}

}
