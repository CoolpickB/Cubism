package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.debug.LoadTrace;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.seam.SeamTrackingView;
import dev.cubeworld.worldgen.SeamLayout;
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
	private void cubeworld$countLoad(CallbackInfoReturnable<?> cir) {
		LoadTrace.LOADS.incrementAndGet();
	}

	@Inject(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;copyOf(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;"))
	private void cubeworld$countSave(CallbackInfoReturnable<Boolean> cir) {
		LoadTrace.SAVES.incrementAndGet();
	}

	@Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
	private void cubeworld$seamTracking(ServerPlayer player, CallbackInfo ci) {
		SeamLayout layout = SeamLayout.of(this.level);
		if (layout == null) {
			return;
		}
		ci.cancel();
		int viewDistance = this.getPlayerViewDistance(player);
		LongOpenHashSet seamChunks = new LongOpenHashSet();
		layout.collectNeighborChunks(player.getBlockX(), player.getBlockY(), player.getBlockZ(), viewDistance, seamChunks);
		LongOpenHashSet simulatedSeamChunks = new LongOpenHashSet();
		int simulationDistance = Math.min(viewDistance, this.level.getServer().getPlayerList().getSimulationDistance());
		layout.collectNeighborChunks(player.getBlockX(), player.getBlockY(), player.getBlockZ(), simulationDistance, simulatedSeamChunks);
		int regionMinX = SectionPos.blockToSectionCoord(layout.offsetX(layout.faceIndexAt(player.getBlockX())) - layout.spacing / 2);
		int regionChunks = SectionPos.blockToSectionCoord(layout.spacing);
		SeamTrackingView next = new SeamTrackingView(
			new ChunkTrackingView.Positioned(player.chunkPosition(), viewDistance),
			regionMinX, regionMinX + regionChunks, -regionChunks / 2, regionChunks / 2,
			seamChunks, simulatedSeamChunks
		);
		if (!next.equals(player.getChunkTrackingView())) {
			next.addTickets(this.level);
			this.applyChunkTrackingView(player, next);
		}
	}

	/** Vanilla diffs only two square views. For any other pair it drops every chunk and sends every chunk again. */
	@Inject(method = "applyChunkTrackingView", at = @At("HEAD"), cancellable = true)
	private void cubeworld$applySeamView(ServerPlayer player, ChunkTrackingView next, CallbackInfo ci) {
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
	private Vec3 cubeworld$playerInChunkRegion(Vec3 position, @Local(argsOnly = true) ChunkPos pos) {
		SeamLayout layout = SeamLayout.of(this.level);
		return layout == null ? position : SeamInteraction.intoRegionOf(layout, position, pos.getMiddleBlockX(), pos.getMiddleBlockZ());
	}

	// The ticket count of vanilla only knows players in the same storage area. FALSE goes to the exact distance check.
	@ModifyExpressionValue(
		method = {"anyPlayerCloseEnoughForSpawning", "getPlayersCloseForSpawning"},
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap$DistanceManager;hasPlayersNearby(J)Lnet/minecraft/util/TriState;")
	)
	private TriState cubeworld$exactCheckAcrossSeams(TriState nearby) {
		return nearby == TriState.FALSE && SeamLayout.of(this.level) != null ? TriState.DEFAULT : nearby;
	}

	@Inject(method = "collectSpawningChunks", at = @At("TAIL"))
	private void cubeworld$spawnInSeamChunks(List<LevelChunk> output, CallbackInfo ci) {
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
