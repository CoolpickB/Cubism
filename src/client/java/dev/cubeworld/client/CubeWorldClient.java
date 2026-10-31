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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;

import java.io.File;

public class CubeWorldClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CubeSky.init();
		CubeNetworking.clientGravitySender = gravity -> ClientPlayNetworking.send(new CubeNetworking.PlayerGravityPayload(gravity));

		ClientPlayNetworking.registerGlobalReceiver(CubeNetworking.PlanetPayload.TYPE, (payload, context) ->
			CubePlanetMath.setClientCubeState(true, payload.diameterChunks()));

		ClientPlayNetworking.registerGlobalReceiver(CubeNetworking.SeamPayload.TYPE, (payload, context) -> {
			CubePlanetMath.setEdgeRounding(payload.edgeRounding());
			SeamClient.layout = new SeamLayout(payload.diameterChunks());
			SeamLayout.setClient(SeamClient.layout);
		});

		ClientPlayNetworking.registerGlobalReceiver(CubeNetworking.MovingBlockPayload.TYPE, (payload, context) -> {
			ClientLevel level = context.client().level;
			if (level == null) {
				return;
			}
			BlockPos pos = payload.pos();
			// A move that the client ran itself already has its moving block. A new one would restart the animation.
			if (level.getBlockEntity(pos) instanceof PistonMovingBlockEntity own && own.getMovedState() == payload.movedState()
				&& own.getDirection() == payload.direction() && own.isExtending() == payload.extending()) {
				return;
			}
			// The payload comes before the block update of the same tick.
			level.setBlock(pos, payload.pistonState(), Block.UPDATE_CLIENTS);
			level.setBlockEntity(MovingPistonBlock.newMovingBlockEntity(
				pos, payload.pistonState(), payload.movedState(), payload.direction(), payload.extending(), payload.source()
			));
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
