package dev.cubeworld.client;

import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.client.render.CubeSky;
import dev.cubeworld.client.render.SeamRenderer;
import dev.cubeworld.network.CubeNetworking;
import dev.cubeworld.worldgen.SeamLayout;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.entity.Entity;

import java.io.File;

public class CubeWorldClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CubeSky.init();
		CubeNetworking.clientGravitySender = gravity -> ClientPlayNetworking.send(new CubeNetworking.PlayerGravityPayload(gravity));

		ClientPlayNetworking.registerGlobalReceiver(CubeNetworking.PlanetPayload.TYPE, (payload, context) ->
			CubePlanetMath.setClientCubeState(true, payload.diameterChunks()));

		ClientPlayNetworking.registerGlobalReceiver(CubeNetworking.SeamPayload.TYPE, (payload, context) -> {
			SeamClient.layout = new SeamLayout(payload.diameterChunks());
			SeamLayout.setClient(SeamClient.layout);
		});

		ClientPlayNetworking.registerGlobalReceiver(CubeNetworking.EntityGravityPayload.TYPE, (payload, context) -> {
			Entity entity = context.client().level == null ? null : context.client().level.getEntity(payload.entityId());
			if (entity != null) {
				((GravityEntityAccess) entity).cubeworld$forceGravityDirection(payload.gravity());
			}
		});

		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			// Lets test scripts take a screenshot of the game without a keypress: create screenshot.request in the game directory.
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				File request = new File(client.gameDirectory, "screenshot.request");
				if (request.delete()) {
					Screenshot.grab(client, false);
				}
			});
		}

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			CubePlanetMath.setClientCubeState(false, CubePlanetMath.MIN_DIAMETER_CHUNKS);
			SeamClient.layout = null;
			SeamLayout.setClient(null);
			SeamRenderer.clear();
		});
	}
}
