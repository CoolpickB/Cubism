package dev.cubeworld;

import dev.cubeworld.network.CubeNetworking;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.DimensionType;
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

	@Override
	public void onInitialize() {
		Registry.register(
			BuiltInRegistries.CHUNK_GENERATOR,
			Identifier.fromNamespaceAndPath(MOD_ID, "cube"),
			CubeChunkGenerator.CODEC
		);
		CubeNetworking.register();
	}
}
