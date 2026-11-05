package dev.cubeworld.client.sodium;

import dev.cubeworld.client.ClientConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voxy has no level of detail yet for a chunk that loads inside the draw distance of Sodium, so it stores the chunk and
 * shows its level of detail, refining over some frames, until Sodium builds the chunk. The sections of such a chunk that
 * Sodium has not built yet hide the level of detail too, so the chunk shows only its blocks. Sodium does not visit these
 * sections when it lists what it draws, so they are found here. A chunk that loads past the draw distance already has a
 * level of detail when it comes into the distance, and Sodium builds it only then, so it keeps its level of detail.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class VoxyReadyColumnsMixin {
	@Shadow
	@Final
	private ClientLevel level;

	@Shadow
	@Final
	private SectionStorage renderSections;

	/** The chunks that loaded inside the draw distance and still have sections to build. */
	@Unique
	private final LongOpenHashSet cubeworld$fresh = new LongOpenHashSet();

	@Shadow
	protected abstract float getSearchDistance(FogParameters fogParameters);

	@Inject(method = "onChunkAdded", at = @At("HEAD"))
	private void cubeworld$added(int x, int z, CallbackInfo ci) {
		this.cubeworld$fresh.add(ChunkPos.pack(x, z));
	}

	@Inject(method = "onChunkRemoved", at = @At("HEAD"))
	private void cubeworld$removed(int x, int z, CallbackInfo ci) {
		this.cubeworld$fresh.remove(ChunkPos.pack(x, z));
	}

	@Inject(method = "renderOutOfGraph", at = @At("TAIL"))
	private void cubeworld$outOfGraph(Viewport viewport, FogParameters fogParameters, CallbackInfo ci) {
		this.cubeworld$hideUnbuilt(viewport, fogParameters);
	}

	@Inject(
		method = "readRenderListFromTree",
		at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/VisibleChunkCollector;createRenderLists(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;)Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/SortedRenderLists;")
	)
	private void cubeworld$fromTree(Viewport viewport, FogParameters fogParameters, CallbackInfo ci) {
		this.cubeworld$hideUnbuilt(viewport, fogParameters);
	}

	private void cubeworld$hideUnbuilt(Viewport viewport, FogParameters fogParameters) {
		VoxyRenderSystem system = IVoxyRenderSystemHolder.getNullable();
		if (system == null || system.visbleSectionStream == null || IrisUtil.irisShadowActive() || ClientConfig.voxyHide < 2) {
			return;
		}
		float distance = this.getSearchDistance(fogParameters);
		CameraTransform camera = viewport.getTransform();
		int minY = this.level.getMinSectionY();
		int maxY = this.level.getMaxSectionY();
		LongIterator columns = this.cubeworld$fresh.iterator();
		while (columns.hasNext()) {
			long column = columns.nextLong();
			int x = ChunkPos.getX(column);
			int z = ChunkPos.getZ(column);
			// The same cylinder test that Sodium does on each section before it draws it.
			float dx = nearestToZero((x << 4) - camera.intX - 1, (x << 4) - camera.intX + 17) - camera.fracX;
			float dz = nearestToZero((z << 4) - camera.intZ - 1, (z << 4) - camera.intZ + 17) - camera.fracZ;
			LevelChunk chunk = this.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
			if (dx * dx + dz * dz >= distance * distance || chunk == null) {
				columns.remove();
				continue;
			}
			boolean unbuilt = false;
			for (int y = minY; y <= maxY; y++) {
				if (chunk.getSection(y - minY).hasOnlyAir()) {
					continue;
				}
				RenderSection section = this.renderSections.getConsistent(SectionPos.asLong(x, y, z));
				if (section == null || section.isBuilt()) {
					continue;
				}
				unbuilt = true;
				system.visbleSectionStream.put(SectionPos.asLong(x, y, z));
			}
			if (!unbuilt) {
				columns.remove();
			}
		}
	}

	private static int nearestToZero(int min, int max) {
		return min > 0 ? min : max < 0 ? max : 0;
	}
}
