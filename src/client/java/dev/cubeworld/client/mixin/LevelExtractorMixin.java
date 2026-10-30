package dev.cubeworld.client.mixin;

import dev.cubeworld.client.render.SeamRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
	@Shadow @Final private LevelRenderer levelRenderer;
	@Shadow private ClientLevel level;

	/** Block and light changes in seam sections reach this method too, but vanilla only knows its own view area. */
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void cubeworld$markSeamDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
		SeamRenderer.markDirty(sectionX, sectionY, sectionZ);
		SeamRenderer.markAcrossSeamDirty(sectionX, sectionY, sectionZ);
	}

	@Inject(method = "extractVisibleBlockEntities", at = @At("TAIL"))
	private void cubeworld$extractSeamBlockEntities(Camera camera, float deltaPartialTick, LevelRenderState levelRenderState, CallbackInfo ci) {
		SeamRenderer.extractBlockEntities(
			this.levelRenderer.blockEntityRenderDispatcher(), camera.position(), deltaPartialTick, levelRenderState.blockEntityRenderStates
		);
	}

	@Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
	private void cubeworld$skipOtherRegion(
		Entity entity, Frustum frustum, double camX, double camY, double camZ, float partialTicks, long chunkFadeDuration, CallbackInfoReturnable<Boolean> cir
	) {
		if (SeamRenderer.inOtherRegion(entity, camX, camZ)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "extractVisibleEntities", at = @At("TAIL"))
	private void cubeworld$extractSeamEntities(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo ci) {
		SeamRenderer.extractEntities(
			this.level, this.levelRenderer.entityRenderDispatcher(), camera.position(), deltaTracker.getGameTimeDeltaPartialTick(true), output.entityRenderStates
		);
		output.lastEntityRenderStateCount = output.entityRenderStates.size();
	}
}
