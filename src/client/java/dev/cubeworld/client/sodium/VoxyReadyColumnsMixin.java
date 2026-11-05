package dev.cubeworld.client.sodium;

import dev.cubeworld.client.ClientConfig;
import dev.cubeworld.client.sodiumseam.SeamFaces;
import dev.cubeworld.client.sodiumseam.VoxyFaces;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy marks only sections that Sodium has built, so a new chunk showed its level of detail, refining over some frames,
 * until Sodium built it. The sections that Sodium has but did not build yet, within its draw distance, hide the level of
 * detail too. Sodium does not visit these sections when it lists what it draws, so they are found here. Built sections
 * stay with Voxy: Sodium draws from a list that it builds in the background, so a guess of which built sections it draws
 * left chunks at the edge with no blocks and no level of detail for some frames.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class VoxyReadyColumnsMixin {
	@Shadow
	@Final
	private ClientLevel level;

	@Shadow
	@Final
	private SectionStorage renderSections;

	@Shadow
	protected abstract float getSearchDistance(FogParameters fogParameters);

	@Inject(method = "renderOutOfGraph", at = @At("TAIL"))
	private void cubeworld$outOfGraph(Viewport viewport, FogParameters fogParameters, CallbackInfo ci) {
		this.cubeworld$readyColumns(viewport, fogParameters);
	}

	@Inject(
		method = "readRenderListFromTree",
		at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/VisibleChunkCollector;createRenderLists(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;)Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/SortedRenderLists;")
	)
	private void cubeworld$fromTree(Viewport viewport, FogParameters fogParameters, CallbackInfo ci) {
		this.cubeworld$readyColumns(viewport, fogParameters);
	}

	private void cubeworld$readyColumns(Viewport viewport, FogParameters fogParameters) {
		VoxyRenderSystem system = IVoxyRenderSystemHolder.getNullable();
		if (system == null || system.visbleSectionStream == null || IrisUtil.irisShadowActive() || ClientConfig.voxyHide < 2) {
			return;
		}
		boolean faces = VoxyFaces.active();
		int face = faces ? SeamFaces.currentFace() : 0;
		float distance = this.getSearchDistance(fogParameters);
		CameraTransform camera = viewport.getTransform();
		int minY = this.level.getMinSectionY();
		int maxY = this.level.getMaxSectionY();
		// The same cylinder test that Sodium does on each section before it draws it.
		ChunkTracker.forEachChunk(ChunkTrackerHolder.get(this.level).getReadyChunks(), (x, z) -> {
			if (faces && SeamFaces.faceOfChunk(x) != face) {
				return;
			}
			float dx = nearestToZero((x << 4) - camera.intX - 1, (x << 4) - camera.intX + 17) - camera.fracX;
			float dz = nearestToZero((z << 4) - camera.intZ - 1, (z << 4) - camera.intZ + 17) - camera.fracZ;
			if (dx * dx + dz * dz >= distance * distance) {
				return;
			}
			LevelChunk chunk = this.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
			if (chunk == null) {
				return;
			}
			for (int y = minY; y <= maxY; y++) {
				if (chunk.getSection(y - minY).hasOnlyAir()) {
					continue;
				}
				RenderSection section = this.renderSections.getConsistent(SectionPos.asLong(x, y, z));
				if (section == null || section.isBuilt()) {
					continue;
				}
				float dy = nearestToZero((y << 4) - camera.intY - 1, (y << 4) - camera.intY + 17) - camera.fracY;
				if (Math.abs(dy) < distance) {
					system.visbleSectionStream.put(SectionPos.asLong(x, y, z));
				}
			}
		});
	}

	private static int nearestToZero(int min, int max) {
		return min > 0 ? min : max < 0 ? max : 0;
	}
}
