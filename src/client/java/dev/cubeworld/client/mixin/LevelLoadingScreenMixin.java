package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.client.gui.CubeLoadingView;
import dev.cubeworld.worldgen.CubePregen;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In a cube world of this game, the loading screen draws a corner of the cube in place of the square map around the
 * spawn. The progress comes from the integrated server, so a world on another server keeps the vanilla map.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class LevelLoadingScreenMixin {
	/** How far up the title and the progress bar move, because the cube is taller than the vanilla map. */
	@Unique
	private int cubeworld$textShift;

	@Shadow
	private LevelLoadingScreen.Reason reason;

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;extractChunksForRendering(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIILnet/minecraft/server/level/progress/ChunkLoadStatusView;)V"))
	private void cubeworld$drawCube(
		GuiGraphicsExtractor graphics, int xCenter, int yCenter, int size, int margin, ChunkLoadStatusView statusView, Operation<Void> original
	) {
		// Portal travel loads another dimension, or the overworld around a portal, so it keeps the vanilla map.
		CubePregen.Progress progress = this.reason == LevelLoadingScreen.Reason.OTHER ? CubePregen.progress() : null;
		if (progress == null) {
			this.cubeworld$textShift = 0;
			original.call(graphics, xCenter, yCenter, size, margin, statusView);
			return;
		}
		int vanillaHalfHeight = statusView.radius() * 2 + 1;
		int halfHeight = Math.max(vanillaHalfHeight, Math.min(CubeLoadingView.MAX_HALF_HEIGHT, graphics.guiHeight() / 2 - 45));
		this.cubeworld$textShift = halfHeight - vanillaHalfHeight;
		CubeLoadingView.draw(graphics, progress, xCenter, yCenter, halfHeight);
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V"))
	private void cubeworld$raiseTitle(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int color, Operation<Void> original) {
		original.call(graphics, font, text, x, y - this.cubeworld$textShift, color);
	}

	@ModifyArg(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/LevelLoadingScreen;drawProgressBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIIF)V"), index = 2)
	private int cubeworld$raiseProgressBar(int top) {
		return top - this.cubeworld$textShift;
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void cubeworld$watch(CallbackInfo ci) {
		CubePregen.Progress progress = CubePregen.progress();
		if (progress != null) {
			progress.watched = true;
		}
	}

	@Inject(method = "onClose", at = @At("HEAD"))
	private void cubeworld$unwatch(CallbackInfo ci) {
		CubePregen.Progress progress = CubePregen.progress();
		if (progress != null) {
			progress.watched = false;
		}
	}
}
