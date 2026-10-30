package dev.cubeworld;

import dev.cubeworld.network.CubeNetworking;
import dev.cubeworld.seam.SeamCrossing;
import dev.cubeworld.seam.SeamTrackingView;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import dev.cubeworld.math.CubePlanetMath;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class CubeWorldMod implements ModInitializer {
	public static final String MOD_ID = "cubeworld";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

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
	/** Runs entities and block ticks in the seam chunks within simulation distance, refreshed like {@link #SEAM_TICKET}. */
	public static final TicketType SEAM_SIMULATION_TICKET = new TicketType(
		3L * SeamTrackingView.REFRESH_TICKS, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
	);

	@Override
	public void onInitialize() {
		loadConfig();
		Registry.register(
			BuiltInRegistries.CHUNK_GENERATOR,
			Identifier.fromNamespaceAndPath(MOD_ID, "cube"),
			CubeChunkGenerator.CODEC
		);
		Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "seam"), SEAM_TICKET);
		Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "seam_simulation"), SEAM_SIMULATION_TICKET);
		CubeNetworking.register();
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			SeamCrossing.tick(level);
			if (level.getGameTime() % SeamTrackingView.REFRESH_TICKS == 0) {
				SeamTrackingView.refreshTickets(level);
			}
		});
	}

	/**
	 * Reads {@code config/cubeworld.properties}, and writes it with the defaults when it is missing. The values apply
	 * to all worlds. A change shows only in chunks that generate after it, so old and new terrain meet at a step.
	 */
	private static void loadConfig() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".properties");
		Properties properties = new Properties();
		try {
			if (Files.exists(path)) {
				try (Reader reader = Files.newBufferedReader(path)) {
					properties.load(reader);
				}
			}
			String rounding = properties.getProperty("edge_rounding");
			if (rounding != null) {
				CubePlanetMath.setEdgeRounding(Integer.parseInt(rounding.trim()));
			} else {
				properties.setProperty("edge_rounding", Integer.toString(CubePlanetMath.DEFAULT_EDGE_ROUNDING));
				try (Writer writer = Files.newBufferedWriter(path)) {
					properties.store(writer, "edge_rounding: radius in blocks of the rounded cube edges, "
						+ CubePlanetMath.MIN_EDGE_ROUNDING + " to " + CubePlanetMath.MAX_EDGE_ROUNDING);
				}
			}
		} catch (IOException | NumberFormatException e) {
			LOGGER.error("Could not read {}, using the defaults", path, e);
		}
	}
}
