package dev.cubeworld.network;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.gravity.CubePlanet;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import dev.cubeworld.worldgen.CubeSettings;
import dev.cubeworld.worldgen.SeamLayout;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class CubeNetworking {
	/** Set by the client entrypoint. The local player calls it when its gravity changes. */
	public static Consumer<Direction> clientGravitySender = direction -> {};

	private CubeNetworking() {}

	public record PlanetPayload(int diameterChunks) implements CustomPacketPayload {
		public static final Type<PlanetPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CubeWorldMod.MOD_ID, "planet"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PlanetPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, PlanetPayload::diameterChunks,
			PlanetPayload::new
		);

		@Override
		public Type<PlanetPayload> type() {
			return TYPE;
		}
	}

	/** Tells the client that the level stores the cube in seam regions, and the shape of the cube. */
	public record SeamPayload(int diameterChunks, int edgeRounding) implements CustomPacketPayload {
		public static final Type<SeamPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CubeWorldMod.MOD_ID, "seam"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SeamPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SeamPayload::diameterChunks,
			ByteBufCodecs.VAR_INT, SeamPayload::edgeRounding,
			SeamPayload::new
		);

		@Override
		public Type<SeamPayload> type() {
			return TYPE;
		}
	}

	public record EntityGravityPayload(int entityId, Direction gravity) implements CustomPacketPayload {
		public static final Type<EntityGravityPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CubeWorldMod.MOD_ID, "entity_gravity"));
		public static final StreamCodec<RegistryFriendlyByteBuf, EntityGravityPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, EntityGravityPayload::entityId,
			Direction.STREAM_CODEC, EntityGravityPayload::gravity,
			EntityGravityPayload::new
		);

		@Override
		public Type<EntityGravityPayload> type() {
			return TYPE;
		}
	}

	public record PlayerGravityPayload(Direction gravity) implements CustomPacketPayload {
		public static final Type<PlayerGravityPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CubeWorldMod.MOD_ID, "player_gravity"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PlayerGravityPayload> CODEC = StreamCodec.composite(
			Direction.STREAM_CODEC, PlayerGravityPayload::gravity,
			PlayerGravityPayload::new
		);

		@Override
		public Type<PlayerGravityPayload> type() {
			return TYPE;
		}
	}

	/**
	 * A moving piston block next to a seam. The client runs piston moves itself, but it does not see the blocks across a
	 * seam, so it cannot make the moving blocks that come from or go to the next face.
	 */
	public record MovingBlockPayload(BlockPos pos, BlockState pistonState, BlockState movedState, Direction direction, boolean extending, boolean source)
		implements CustomPacketPayload {
		public static final Type<MovingBlockPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CubeWorldMod.MOD_ID, "moving_block"));
		private static final StreamCodec<ByteBuf, BlockState> STATE = ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY);
		public static final StreamCodec<RegistryFriendlyByteBuf, MovingBlockPayload> CODEC = StreamCodec.composite(
			BlockPos.STREAM_CODEC, MovingBlockPayload::pos,
			STATE, MovingBlockPayload::pistonState,
			STATE, MovingBlockPayload::movedState,
			Direction.STREAM_CODEC, MovingBlockPayload::direction,
			ByteBufCodecs.BOOL, MovingBlockPayload::extending,
			ByteBufCodecs.BOOL, MovingBlockPayload::source,
			MovingBlockPayload::new
		);

		@Override
		public Type<MovingBlockPayload> type() {
			return TYPE;
		}
	}

	private record PendingMovingBlock(ServerLevel level, MovingBlockPayload payload) {}

	private static final List<PendingMovingBlock> PENDING_MOVING_BLOCKS = new ArrayList<>();

	/**
	 * Sends the moving block at the end of the level tick. The piston block event goes out when it runs, and the client
	 * runs the move itself, which ends or removes the moving blocks that it finds in front of the piston. A moving block
	 * that came before the event would be lost.
	 */
	public static void sendMovingBlock(ServerLevel level, PistonMovingBlockEntity moving) {
		PENDING_MOVING_BLOCKS.add(new PendingMovingBlock(level, new MovingBlockPayload(
			moving.getBlockPos(), moving.getBlockState(), moving.getMovedState(), moving.getDirection(), moving.isExtending(), moving.isSourcePiston()
		)));
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(PlanetPayload.TYPE, PlanetPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SeamPayload.TYPE, SeamPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EntityGravityPayload.TYPE, EntityGravityPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MovingBlockPayload.TYPE, MovingBlockPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PlayerGravityPayload.TYPE, PlayerGravityPayload.CODEC);

		// The client simulates its own player, so the server takes the player's gravity from the client.
		ServerPlayNetworking.registerGlobalReceiver(PlayerGravityPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (CubePlanet.of(player.level()) == null) {
				return;
			}
			((GravityEntityAccess) player).cubeworld$forceGravityDirection(payload.gravity());
			broadcastGravity(player, false);
		});

		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			if (PENDING_MOVING_BLOCKS.isEmpty()) {
				return;
			}
			PENDING_MOVING_BLOCKS.removeIf(pending -> {
				if (pending.level() != level) {
					return false;
				}
				for (ServerPlayer player : PlayerLookup.tracking(level, pending.payload().pos())) {
					ServerPlayNetworking.send(player, pending.payload());
				}
				return true;
			});
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (server.overworld().getChunkSource().getGenerator() instanceof CubeChunkGenerator generator) {
				sender.sendPacket(new PlanetPayload(generator.diameterChunks()));
				sender.sendPacket(payloadFor(handler.player));
			}
			if (SeamLayout.of(server.overworld()) != null) {
				CubeSettings settings = CubeSettings.of(server.overworld().getChunkSource().getGenerator());
				sender.sendPacket(new SeamPayload(settings.diameterChunks(), settings.edgeRounding()));
			}
		});

		EntityTrackingEvents.START_TRACKING.register((entity, player) -> {
			if (((GravityEntityAccess) entity).cubeworld$getGravityDirection() != Direction.DOWN) {
				ServerPlayNetworking.send(player, payloadFor(entity));
			}
		});
	}

	/**
	 * Sends the gravity of an entity to the players that track it. A player gets its own gravity only
	 * when the server changed it: an echo of a client change can arrive after a newer client change.
	 */
	public static void broadcastGravity(Entity entity, boolean includeSelf) {
		EntityGravityPayload payload = payloadFor(entity);
		for (ServerPlayer tracker : PlayerLookup.tracking(entity)) {
			if (tracker != entity) {
				ServerPlayNetworking.send(tracker, payload);
			}
		}
		if (includeSelf && entity instanceof ServerPlayer self && self.connection != null) {
			ServerPlayNetworking.send(self, payload);
		}
	}

	private static EntityGravityPayload payloadFor(Entity entity) {
		return new EntityGravityPayload(entity.getId(), ((GravityEntityAccess) entity).cubeworld$getGravityDirection());
	}
}
