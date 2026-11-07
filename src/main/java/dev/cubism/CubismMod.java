package dev.cubism;

import dev.cubism.loot.FiniteLoot;
import dev.cubism.network.CubeNetworking;
import dev.cubism.seam.SeamCrossing;
import dev.cubism.seam.SeamLight;
import dev.cubism.seam.SeamTrackingView;
import dev.cubism.worldgen.CubePregen;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class CubismMod implements ModInitializer {
	public static final String MOD_ID = "cubism";
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

	/** The vanilla Nether on the faces of a small core, see {@code SeamLayout#nether}. */
	public static final ResourceKey<NoiseGeneratorSettings> NETHER_SEAM_SETTINGS = ResourceKey.create(
		Registries.NOISE_SETTINGS,
		Identifier.fromNamespaceAndPath(MOD_ID, "seam_nether")
	);

	/** Keeps seam chunks loaded. It expires unless {@link SeamTrackingView#refreshTickets} runs again within its timeout. */
	public static final TicketType SEAM_TICKET = new TicketType(3L * SeamTrackingView.REFRESH_TICKS, TicketType.FLAG_LOADING);
	/** Runs entities and block ticks in the seam chunks within simulation distance, refreshed like {@link #SEAM_TICKET}. */
	public static final TicketType SEAM_SIMULATION_TICKET = new TicketType(
		3L * SeamTrackingView.REFRESH_TICKS, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
	);

	@Override
	public void onInitialize() {
		Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "seam"), SEAM_TICKET);
		Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "seam_simulation"), SEAM_SIMULATION_TICKET);
		Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "pregen"), CubePregen.TICKET);
		FiniteLoot.register();
		CubeNetworking.register();
		ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> SeamLight.onChunkLoad(level, chunk));
		ServerTickEvents.END_LEVEL_TICK.register(level -> {
			SeamCrossing.tick(level);
			if (level.getGameTime() % SeamTrackingView.REFRESH_TICKS == 0) {
				SeamTrackingView.refreshTickets(level);
			}
		});
	}
}
