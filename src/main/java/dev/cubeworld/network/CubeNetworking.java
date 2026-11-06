package dev.cubeworld.network;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.gravity.GravityEntityAccess;
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

public final class CubeNetworking {
	private CubeNetworking() {}

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
		PayloadTypeRegistry.clientboundPlay().register(SeamPayload.TYPE, SeamPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EntityGravityPayload.TYPE, EntityGravityPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MovingBlockPayload.TYPE, MovingBlockPayload.CODEC);

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

	private static EntityGravityPayload payloadFor(Entity entity) {
		return new EntityGravityPayload(entity.getId(), ((GravityEntityAccess) entity).cubeworld$getGravityDirection());
	}
}
