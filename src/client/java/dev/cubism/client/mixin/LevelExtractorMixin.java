package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.client.LoadTraceClient;
import dev.cubism.client.SeamClient;
import dev.cubism.client.render.SeamRenderer;
import dev.cubism.seam.SeamInteraction;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
	@Shadow @Final private LevelRenderer levelRenderer;
	@Shadow private ClientLevel level;

	/**
	 * True while a block change marks sections dirty. Seam sections rebuild those first, because a light update can
	 * wait but a changed block shows at once. A piston block that moves onto the next face is a block entity there,
	 * and it showed only after the rebuild, when its move was over.
	 */
	@Unique private boolean cubism$blockChanging;

	@Inject(method = {"blockChanged", "setBlocksDirty"}, at = @At("HEAD"))
	private void cubism$startBlockChange(CallbackInfo ci) {
		this.cubism$blockChanging = true;
	}

	@Inject(method = {"blockChanged", "setBlocksDirty"}, at = @At("RETURN"))
	private void cubism$endBlockChange(CallbackInfo ci) {
		this.cubism$blockChanging = false;
	}

	/** Block and light changes in seam sections reach this method too, but vanilla only knows its own view area. */
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void cubism$markSeamDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
		boolean urgent = playerChanged || this.cubism$blockChanging;
		LoadTraceClient.count("dirty.sections");
		SeamRenderer.markDirty(sectionX, sectionY, sectionZ, urgent);
		SeamRenderer.markAcrossSeamDirty(sectionX, sectionY, sectionZ, urgent);
	}

	@Inject(method = "extractVisibleBlockEntities", at = @At("TAIL"))
	private void cubism$extractSeamBlockEntities(Camera camera, float deltaPartialTick, LevelRenderState levelRenderState, CallbackInfo ci) {
		SeamRenderer.extractBlockEntities(
			this.levelRenderer.blockEntityRenderDispatcher(), camera.position(), deltaPartialTick, levelRenderState.blockEntityRenderStates
		);
	}

	@Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
	private void cubism$skipOtherRegion(
		Entity entity, Frustum frustum, double camX, double camY, double camZ, float partialTicks, long chunkFadeDuration, CallbackInfoReturnable<Boolean> cir
	) {
		if (SeamRenderer.inOtherRegion(entity, camX, camZ) || SeamRenderer.torn(entity)) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * Vanilla hides an entity until the section it stands in is compiled. After a crossing, the section of the player
	 * waits for a compile in the new region, and the player would vanish for those frames.
	 */
	@WrapOperation(method = "isEntityVisible", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;J)Z"))
	private boolean cubism$showPlayerWhileCompiling(
		LevelRenderer renderer, BlockPos pos, long chunkFadeDuration, Operation<Boolean> original, @Local(argsOnly = true) Entity entity
	) {
		return original.call(renderer, pos, chunkFadeDuration) || SeamClient.layout != null && entity == Minecraft.getInstance().player;
	}

	@Inject(method = "extractVisibleEntities", at = @At("TAIL"))
	private void cubism$extractSeamEntities(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState output, CallbackInfo ci) {
		SeamRenderer.extractEntities(
			this.level, this.levelRenderer.entityRenderDispatcher(), camera.position(), deltaTracker.getGameTimeDeltaPartialTick(true), output.entityRenderStates
		);
		output.lastEntityRenderStateCount = output.entityRenderStates.size();
	}

	// The crack of a block across a seam shows next to the camera, not where the block is stored.
	@WrapOperation(method = "extractBlockDestroyAnimation", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;distToCenterSqr(DDD)D"))
	private double cubism$crackDistanceAcrossSeam(BlockPos pos, double x, double y, double z, Operation<Double> original) {
		BlockPos shown = SeamClient.layout == null ? null : SeamInteraction.inRegionOf(SeamClient.layout, pos, (int) Math.floor(x));
		return original.call(shown == null ? pos : shown, x, y, z);
	}
}
