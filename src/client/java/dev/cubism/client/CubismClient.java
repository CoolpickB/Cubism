package dev.cubism.client;

import dev.cubism.client.debug.DebugLight;
import dev.cubism.client.debug.LoadTraceClient;
import dev.cubism.client.render.CubeSky;
import dev.cubism.client.render.SeamRenderer;
import dev.cubism.cube.CubePlanetMath;
import dev.cubism.cube.SeamLayout;
import dev.cubism.debug.LoadTrace;
import dev.cubism.network.CubeNetworking;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Screenshot;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.ClientLevel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;

import java.io.File;

public class CubismClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		LoadTrace.enabled |= ClientConfig.traceLoads;
		CubeSky.init();
		// Debug: writes a trace of the next seconds to logs/cubism-load-<time>.log, with the lines of DebugTrace.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommands.literal("cubetrace").then(ClientCommands.argument("seconds", IntegerArgumentType.integer(1, 600)).executes(context -> {
				LoadTrace.enabled = true;
				LoadTraceClient.begin("/cubetrace");
				LoadTrace.endAfter(IntegerArgumentType.getInteger(context, "seconds") * 1000L);
				context.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Tracing to logs/cubism-load-*.log"));
				return 1;
			}))));
		if (ClientConfig.debugCommands) {
			ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommands.literal("cubelight").executes(context -> DebugLight.print())));
			ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommands.literal("cubevoxy").then(ClientCommands.literal("hide").then(
					ClientCommands.argument("mode", IntegerArgumentType.integer(0, 2)).executes(context -> {
						ClientConfig.voxyHide = IntegerArgumentType.getInteger(context, "mode");
						return 1;
					}))).then(ClientCommands.literal("only").then(
					ClientCommands.argument("face", IntegerArgumentType.integer(-1, 6)).executes(context -> {
						ClientConfig.voxyOnly = IntegerArgumentType.getInteger(context, "face");
						return 1;
					})))));
		}

		ClientPlayNetworking.registerGlobalReceiver(CubeNetworking.SeamPayload.TYPE, (payload, context) -> {
			CubePlanetMath.setEdgeRounding(payload.edgeRounding());
			SeamLayout overworld = new SeamLayout(payload.diameterChunks());
			SeamLayout.setClient(overworld, payload.cubeNether() ? SeamLayout.nether(overworld) : null);
			ClientLevel level = context.client().level;
			SeamClient.layout = level == null ? null : SeamLayout.of(level);
		});

		ClientPlayNetworking.registerGlobalReceiver(CubeNetworking.PlanetPayload.TYPE, (payload, context) -> context.client().execute(() -> dev.cubism.client.render.PlanetView.load(payload.data())));
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> dev.cubism.client.render.PlanetView.clear());

		// The seam chunks of the old level are of another cube, or of none.
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((client, level) -> {
			SeamRenderer.clear();
			SeamClient.layout = SeamLayout.of(level);
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
			LoadTrace.end("disconnected");
			SeamClient.layout = null;
			SeamLayout.setClient(null, null);
			SeamRenderer.clear();
		});
	}
}
