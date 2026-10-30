package dev.cubeworld.network;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.gravity.CubePlanet;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

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

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(PlanetPayload.TYPE, PlanetPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(EntityGravityPayload.TYPE, EntityGravityPayload.CODEC);
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

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (server.overworld().getChunkSource().getGenerator() instanceof CubeChunkGenerator generator) {
				sender.sendPacket(new PlanetPayload(generator.diameterChunks()));
				sender.sendPacket(payloadFor(handler.player));
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
