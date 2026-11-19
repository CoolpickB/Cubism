package dev.cubism.mixin.tracking;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.debug.LoadTrace;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.seam.SeamTrackingView;
import dev.cubism.cube.SeamLayout;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.TriState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Sends a player the neighbor-face chunks that it can see across seams, together with the vanilla square. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
	private static final int SEAM_HOLD_CHUNKS = 4;
	private static final int SEAM_NEAR_CHUNKS = 8;

	@Shadow @Final ServerLevel level;
	@Shadow private volatile Long2ObjectLinkedOpenHashMap<ChunkHolder> visibleChunkMap;

	@Shadow
	protected abstract boolean anyPlayerCloseEnoughForSpawningInternal(ChunkPos pos);

	@Shadow
	protected abstract int getPlayerViewDistance(ServerPlayer player);

	@Shadow
	protected abstract void applyChunkTrackingView(ServerPlayer player, ChunkTrackingView next);

	@Shadow
	protected abstract void markChunkPendingToSend(ServerPlayer player, ChunkPos pos);

	@Shadow
	private static void dropChunk(ServerPlayer player, ChunkPos pos) {
		throw new AssertionError();
	}

	@Inject(method = "scheduleChunkLoad", at = @At("HEAD"))
	private void cubism$countLoad(CallbackInfoReturnable<?> cir) {
		LoadTrace.LOADS.incrementAndGet();
	}

	@Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;copyOf(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;"))
	private void cubism$countSave(CallbackInfoReturnable<Boolean> cir) {
		LoadTrace.SAVES.incrementAndGet();
	}

	@Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
	private void cubism$seamTracking(ServerPlayer player, CallbackInfo ci) {
		SeamLayout layout = SeamLayout.of(this.level);
		if (layout == null) {
			return;
		}
		ci.cancel();
		int viewDistance = this.getPlayerViewDistance(player);
		// Vanilla counts the distance from the chunk of the player, which reaches about one chunk further than the
		// distance from the player itself. Without the extra chunk, the far ring of a face showed only while the player
		// stood on that face.
		int seamDistance = viewDistance + 1;
		LongOpenHashSet seamChunks = new LongOpenHashSet();
		layout.collectNeighborChunks(player.getBlockX(), player.getBlockY(), player.getBlockZ(), seamDistance, seamChunks);
		if (player.getChunkTrackingView() instanceof SeamTrackingView previous) {
			// A chunk that just left the distance stays for a few chunks of walking, so that moving along an edge does
			// not drop and send the chunks at the border of the distance again and again. The square of the face that
			// the player just left stays too, so that a crossing does not drop it and send it again as seam chunks.
			LongOpenHashSet loose = new LongOpenHashSet();
			layout.collectNeighborChunks(player.getBlockX(), player.getBlockY(), player.getBlockZ(), seamDistance + SEAM_HOLD_CHUNKS, loose);
			previous.forEach(pos -> {
				if (loose.contains(pos.pack())) {
					seamChunks.add(pos.pack());
				}
			});
		}
		LongOpenHashSet nearSeamChunks = new LongOpenHashSet();
		layout.collectNeighborChunks(player.getBlockX(), player.getBlockY(), player.getBlockZ(), Math.min(viewDistance, SEAM_NEAR_CHUNKS), nearSeamChunks);
		LongOpenHashSet simulatedSeamChunks = new LongOpenHashSet();
		int simulationDistance = Math.min(viewDistance, this.level.getServer().getPlayerList().getSimulationDistance());
		layout.collectNeighborChunks(player.getBlockX(), player.getBlockY(), player.getBlockZ(), simulationDistance, simulatedSeamChunks);
		int region = layout.regionAt(player.getBlockX(), player.getBlockZ());
		int regionX = layout.offsetX(region < 0 ? layout.faceIndexAt(player.getBlockX()) : region);
		int half = region == SeamLayout.SPACE ? SeamLayout.SPACE_HALF : layout.spacing / 2;
		SeamTrackingView next = new SeamTrackingView(
			new ChunkTrackingView.Positioned(player.chunkPosition(), viewDistance),
			SectionPos.blockToSectionCoord(regionX - half), SectionPos.blockToSectionCoord(regionX + half),
			SectionPos.blockToSectionCoord(-half), SectionPos.blockToSectionCoord(half),
			seamChunks, nearSeamChunks, simulatedSeamChunks
		);
		if (!next.equals(player.getChunkTrackingView())) {
			next.addTickets(this.level);
			this.applyChunkTrackingView(player, next);
		}
	}

	/** Vanilla diffs only two square views. For any other pair it drops every chunk and sends every chunk again. */
	@Inject(method = "applyChunkTrackingView", at = @At("HEAD"), cancellable = true)
	private void cubism$applySeamView(ServerPlayer player, ChunkTrackingView next, CallbackInfo ci) {
		ChunkTrackingView previous = player.getChunkTrackingView();
		if (!(next instanceof SeamTrackingView) && !(previous instanceof SeamTrackingView)) {
			return;
		}
		ci.cancel();
		if (player.level() != this.level) {
			return;
		}
		if (next instanceof SeamTrackingView to && !(previous instanceof SeamTrackingView from && from.base().center().equals(to.base().center()))) {
			player.connection.send(new ClientboundSetChunkCacheCenterPacket(to.base().center().x(), to.base().center().z()));
		}
		SeamTrackingView.difference(previous, next, pos -> this.markChunkPendingToSend(player, pos), pos -> dropChunk(player, pos));
		player.setChunkTrackingView(next);
	}

	// A player near a seam counts as close to the spawn chunks across it, at the distance the seam shows.
	@ModifyExpressionValue(method = "playerIsCloseEnoughForSpawning", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;position()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 cubism$playerInChunkRegion(Vec3 position, @Local(argsOnly = true) ChunkPos pos) {
		SeamLayout layout = SeamLayout.of(this.level);
		return layout == null ? position : SeamInteraction.intoRegionOf(layout, position, pos.getMiddleBlockX(), pos.getMiddleBlockZ());
	}

	// The ticket count of vanilla only knows players in the same storage area. FALSE goes to the exact distance check.
	@ModifyExpressionValue(
		method = {"anyPlayerCloseEnoughForSpawning", "getPlayersCloseForSpawning"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap$DistanceManager;hasPlayersNearby(J)Lnet/minecraft/util/TriState;")
	)
	private TriState cubism$exactCheckAcrossSeams(TriState nearby) {
		return nearby == TriState.FALSE && SeamLayout.of(this.level) != null ? TriState.DEFAULT : nearby;
	}

	@Inject(method = "collectSpawningChunks", at = @At("TAIL"))
	private void cubism$spawnInSeamChunks(List<LevelChunk> output, CallbackInfo ci) {
		if (SeamLayout.of(this.level) == null) {
			return;
		}
		LongOpenHashSet added = new LongOpenHashSet();
		for (LevelChunk chunk : output) {
			added.add(chunk.getPos().pack());
		}
		for (ServerPlayer player : this.level.players()) {
			if (player.getChunkTrackingView() instanceof SeamTrackingView view) {
				for (long key : view.simulatedSeamChunks()) {
					ChunkHolder holder = added.add(key) ? this.visibleChunkMap.get(key) : null;
					LevelChunk chunk = holder == null ? null : holder.getTickingChunk();
					if (chunk != null && this.anyPlayerCloseEnoughForSpawningInternal(holder.getPos())) {
						output.add(chunk);
					}
				}
			}
		}
	}
}
