package dev.cubeworld;

import dev.cubeworld.network.CubeNetworking;
import dev.cubeworld.seam.SeamTrackingView;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

public class CubeWorldMod implements ModInitializer {
	public static final String MOD_ID = "cubeworld";

	public static final ResourceKey<WorldPreset> CUBE_PRESET = ResourceKey.create(
		Registries.WORLD_PRESET,
		Identifier.fromNamespaceAndPath(MOD_ID, "cube")
	);

	public static final ResourceKey<DimensionType> CUBE_OVERWORLD_TYPE = ResourceKey.create(
		Registries.DIMENSION_TYPE,
		Identifier.fromNamespaceAndPath(MOD_ID, "cube_overworld")
	);

	/** Noise settings that make a vanilla noise generator store the cube as six face regions joined by seams. */
	public static final ResourceKey<NoiseGeneratorSettings> SEAM_SETTINGS = ResourceKey.create(
		Registries.NOISE_SETTINGS,
		Identifier.fromNamespaceAndPath(MOD_ID, "seam")
	);

	public static final int SEAM_DIAMETER_CHUNKS = 24;

	/** Keeps seam chunks loaded. It expires unless {@link SeamTrackingView#refreshTickets} runs again within its timeout. */
	public static final TicketType SEAM_TICKET = new TicketType(3L * SeamTrackingView.REFRESH_TICKS, TicketType.FLAG_LOADING);

	@Override
	public void onInitialize() {
		Registry.register(
			BuiltInRegistries.CHUNK_GENERATOR,
			Identifier.fromNamespaceAndPath(MOD_ID, "cube"),
			CubeChunkGenerator.CODEC
		);
		Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "seam"), SEAM_TICKET);
		CubeNetworking.register();
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			if (level.getGameTime() % SeamTrackingView.REFRESH_TICKS == 0) {
				SeamTrackingView.refreshTickets(level);
			}
		});
	}
}
