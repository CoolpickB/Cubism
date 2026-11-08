package dev.cubism.network;

import dev.cubism.CubismMod;
import dev.cubism.worldgen.CubeSettings;
import dev.cubism.worldgen.PlanetMap;
import dev.cubism.worldgen.SeamLayout;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class CubeNetworking {
	private CubeNetworking() {}

	/** Tells the client that the level stores the cube in seam regions, and the shape of the cube. */
	/** The cube of the overworld, and whether the Nether is a cube too. Worlds made before the cube Nether have a vanilla Nether. */
	public record SeamPayload(int diameterChunks, int edgeRounding, boolean cubeNether) implements CustomPacketPayload {
		public static final Type<SeamPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CubismMod.MOD_ID, "seam"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SeamPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, SeamPayload::diameterChunks,
			ByteBufCodecs.VAR_INT, SeamPayload::edgeRounding,
			ByteBufCodecs.BOOL, SeamPayload::cubeNether,
			SeamPayload::new
		);

		@Override
		public Type<SeamPayload> type() {
			return TYPE;
		}
	}

	/** The coarse map of the surface for the view from space, see {@link dev.cubism.worldgen.PlanetMap}. */
	public record PlanetPayload(byte[] data) implements CustomPacketPayload {
		public static final Type<PlanetPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CubismMod.MOD_ID, "planet"));
		public static final StreamCodec<RegistryFriendlyByteBuf, PlanetPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BYTE_ARRAY, PlanetPayload::data,
			PlanetPayload::new
		);

		@Override
		public Type<PlanetPayload> type() {
			return TYPE;
		}
	}

	/**
	 * A moving piston block next to a seam. The client runs piston moves itself, but it does not see the blocks across a
	 * seam, so it cannot make the moving blocks that come from or go to the next face.
	 */
	public record MovingBlockPayload(BlockPos pos, BlockState pistonState, BlockState movedState, Direction direction, boolean extending, boolean source)
		implements CustomPacketPayload {
		public static final Type<MovingBlockPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(CubismMod.MOD_ID, "moving_block"));
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
		PayloadTypeRegistry.clientboundPlay().register(MovingBlockPayload.TYPE, MovingBlockPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PlanetPayload.TYPE, PlanetPayload.CODEC);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> PlanetMap.clear());

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
				ServerLevel nether = server.getLevel(Level.NETHER);
				sender.sendPacket(new SeamPayload(settings.diameterChunks(), settings.edgeRounding(), nether != null && SeamLayout.of(nether) != null));
				// The map is made in the background and goes out when it is ready.
				PlanetMap.of(server.overworld()).thenAccept(data -> server.execute(() -> {
					if (handler.getPlayer().connection == handler) {
						ServerPlayNetworking.send(handler.getPlayer(), new PlanetPayload(data));
					}
				}));
			}
		});
	}
}
