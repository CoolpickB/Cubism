package dev.cubeworld.client.sodium;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.cubeworld.client.SeamClient;
import dev.cubeworld.client.sodiumseam.SeamFaces;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.services.PlatformRuntimeInformation;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.oit.OitStage;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Gives Sodium a section manager per face region, routes chunks and rebuilds to the manager of their face, and draws
 * the faces next to the camera face folded over the seams. Only applies in a world with seams.
 */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public abstract class SodiumWorldRendererMixin {
	@Shadow private Minecraft client;
	@Shadow private ClientLevel level;
	@Shadow private int renderDistance;
	@Shadow private Vector3d lastCameraPos;
	@Shadow private FogParameters lastFogParameters;
	@Shadow private boolean useTranslucencySorting;
	@Shadow private RenderSectionManager renderSectionManager;
	@Shadow private UniformBufferManager uniformBufferManager;

	@Shadow private void deleteRendererState() {}

	@Shadow public void reload() {}

	@Inject(method = "initRenderer", at = @At("HEAD"), cancellable = true)
	private void cubeworld$initFaces(CallbackInfo ci) {
		if (SeamClient.layout == null) {
			return;
		}
		this.deleteRendererState();
		this.useTranslucencySorting = !PlatformRuntimeInformation.getInstance().isDevelopmentEnvironment()
			|| SodiumClientMod.options().debug.terrainSortingEnabled;
		SortBehavior sort = this.useTranslucencySorting ? SortBehavior.DYNAMIC_DEFER_NEARBY_ZERO_FRAMES : SortBehavior.OFF;
		this.renderDistance = this.client.options.getEffectiveRenderDistance();
		int face = SeamFaces.faceOfCamera(this.client.gameRenderer.mainCamera());
		SeamFaces.start(this.level, this.renderDistance, sort, face);
		this.renderSectionManager = SeamFaces.manager(face);
		this.uniformBufferManager = SeamFaces.uniforms(face);
		ChunkTracker tracker = ChunkTrackerHolder.get(this.level);
		ChunkTracker.forEachChunk(tracker.getReadyChunks(), (x, z) -> SeamFaces.manager(SeamFaces.faceOfChunk(x)).onChunkAdded(x, z));
		ci.cancel();
	}

	@Inject(method = "deleteRendererState", at = @At("HEAD"))
	private void cubeworld$deleteFaces(CallbackInfo ci) {
		SeamFaces.destroyAllBut(this.renderSectionManager);
	}

	@Inject(method = "processChunkEvents", at = @At("HEAD"), cancellable = true)
	private void cubeworld$routeChunkEvents(CallbackInfo ci) {
		if (!SeamFaces.active()) {
			return;
		}
		SeamFaces.forEachManager(RenderSectionManager::beforeSectionUpdates);
		ChunkTrackerHolder.get(this.level).forEachEvent(
			(x, z) -> SeamFaces.manager(SeamFaces.faceOfChunk(x)).onChunkAdded(x, z),
			(x, z) -> {
				RenderSectionManager manager = SeamFaces.existing(SeamFaces.faceOfChunk(x));
				if (manager != null) {
					manager.onChunkRemoved(x, z);
				}
			});
		ci.cancel();
	}

	@Inject(method = "setupTerrain", at = @At("HEAD"))
	private void cubeworld$swapPrimary(Camera camera, Viewport viewport, FogParameters fog, boolean occlusion, boolean immediately,
		Matrix4f cullMatrix, CallbackInfo ci) {
		if (SeamClient.layout == null) {
			return;
		}
		if (!SeamFaces.active()) {
			this.reload();
		}
		int face = SeamFaces.faceOfCamera(camera);
		if (face != SeamFaces.primary()) {
			SeamFaces.setPrimary(face);
			this.renderSectionManager = SeamFaces.manager(face);
			this.uniformBufferManager = SeamFaces.uniforms(face);
			this.renderSectionManager.notifyChangedCamera();
			this.lastCameraPos = null;
		}
	}

	@Inject(method = "setupTerrain", at = @At("TAIL"))
	private void cubeworld$driveFaces(Camera camera, Viewport viewport, FogParameters fog, boolean occlusion, boolean immediately,
		Matrix4f cullMatrix, CallbackInfo ci) {
		if (SeamFaces.active()) {
			SeamFaces.drive(camera, viewport, fog, occlusion, immediately, cullMatrix, immediately ? this.renderDistance : 1);
		}
	}

	@Inject(method = "prepareChunkRendering", at = @At("TAIL"))
	private void cubeworld$prepareFaces(ChunkRenderMatrices matrices, double x, double y, double z, CallbackInfo ci) {
		if (SeamFaces.active()) {
			SeamFaces.prepareRendering(matrices, this.useTranslucencySorting);
		}
	}

	@Inject(method = "drawChunkLayer", at = @At("TAIL"))
	private void cubeworld$drawFaces(RenderPass pass, ChunkSectionLayerGroup group, ChunkRenderMatrices matrices, double x, double y, double z,
		GpuSampler sampler, @Nullable OitStage stage, CallbackInfo ci) {
		if (SeamFaces.active()) {
			SeamFaces.draw(pass, group, matrices, sampler, stage, this.lastFogParameters, this.useTranslucencySorting);
		}
	}

	@Inject(method = "endFrame", at = @At("TAIL"))
	private void cubeworld$endFaces(CallbackInfo ci) {
		if (SeamFaces.active()) {
			SeamFaces.endFrame();
		}
	}

	@Inject(method = "scheduleTerrainUpdate", at = @At("TAIL"))
	private void cubeworld$dirtyFaces(CallbackInfo ci) {
		if (SeamFaces.active()) {
			SeamFaces.forEachManager(RenderSectionManager::markGraphDirty);
		}
	}

	@Inject(method = "scheduleRebuildForChunk", at = @At("HEAD"), cancellable = true)
	private void cubeworld$routeRebuild(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
		if (SeamFaces.active()) {
			SeamFaces.manager(SeamFaces.faceOfChunk(x)).scheduleRebuild(x, y, z, playerChanged);
			ci.cancel();
		}
	}

	@Inject(method = "isSectionReady", at = @At("HEAD"), cancellable = true)
	private void cubeworld$routeReady(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
		if (SeamFaces.active()) {
			RenderSectionManager manager = SeamFaces.existing(SeamFaces.faceOfChunk(x));
			cir.setReturnValue(manager != null && manager.isSectionBuilt(x, y, z));
		}
	}
}
