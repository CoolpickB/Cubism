package dev.cubeworld.mixin;

import dev.cubeworld.seam.SeamTrackingView;
import dev.cubeworld.worldgen.SeamLayout;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sends a player the neighbor-face chunks that it can see across seams, together with the vanilla square. */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
	@Shadow @Final ServerLevel level;

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
		SeamTrackingView next = new SeamTrackingView(new ChunkTrackingView.Positioned(player.chunkPosition(), viewDistance), seamChunks);
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
}
