package dev.cubeworld.client;

import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.network.CubeNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.entity.Entity;

public class CubeWorldClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CubeNetworking.clientGravitySender = gravity -> ClientPlayNetworking.send(new CubeNetworking.PlayerGravityPayload(gravity));

		ClientPlayNetworking.registerGlobalReceiver(CubeNetworking.PlanetPayload.TYPE, (payload, context) ->
			CubePlanetMath.setClientCubeState(true, payload.diameterChunks()));

		ClientPlayNetworking.registerGlobalReceiver(CubeNetworking.EntityGravityPayload.TYPE, (payload, context) -> {
			Entity entity = context.client().level == null ? null : context.client().level.getEntity(payload.entityId());
			if (entity != null) {
				((GravityEntityAccess) entity).cubeworld$forceGravityDirection(payload.gravity());
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
			CubePlanetMath.setClientCubeState(false, CubePlanetMath.MIN_DIAMETER_CHUNKS));
	}
}
