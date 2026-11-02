package dev.cubeworld.worldgen;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.mixin.RandomStateAccessor;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Tells whether a structure set really generates a structure in a chunk of a cube world, by running the generator of
 * the world on that chunk. The Customize map finds a place from the placement and the biome only. The terrain, the
 * edges and the wedge of a face decide the rest, and only the generator knows them.
 *
 * <p>The verifier has its own noise state with seams, as the server makes it, so its answers are those of a world with
 * the same settings and seed. The code that it runs reads the layout from {@link SeamLayout#active()}, which the
 * verifier sets for its own thread, so searches of different sizes can run at the same time.
 */
public final class StructureVerifier {
	private static final Logger LOGGER = LogUtils.getLogger();

	private final SeamLayout layout;
	private final NoiseBasedChunkGenerator generator;
	private final ChunkGeneratorStructureState state;
	private final RegistryAccess registries;
	private final StructureTemplateManager templates;
	private final LevelHeightAccessor heights;
	private volatile boolean failed;

	public StructureVerifier(
		RegistryAccess registries, Holder<NoiseGeneratorSettings> settings, HolderGetter<NormalNoise> noises, MultiNoiseBiomeSource biomes,
		HolderLookup<StructureSet> sets, CubeSettings cube, long seed, StructureTemplateManager templates, LevelHeightAccessor heights
	) {
		this.layout = new SeamLayout(cube.diameterChunks());
		this.registries = registries;
		this.templates = templates;
		this.heights = heights;
		// The generator is new because it keeps the sea level of the first layout that it sees.
		this.generator = new NoiseBasedChunkGenerator(biomes, settings);
		CubePlanetMath.setEdgeRounding(cube.edgeRounding());
		RandomState random = RandomState.create(noises, seed, settings.value());
		((SeamRandomState) (Object) random).cubeworld$enableSeams(this.layout);
		if (cube.shiftsClimate()) {
			RandomStateAccessor router = (RandomStateAccessor) (Object) random;
			router.cubeworld$setRouter(CubeDensityFunctions.withClimateShift(router.cubeworld$getRouter(), cube.temperature(), cube.humidity()));
		}
		this.state = ChunkGeneratorStructureState.createForNormal(random, seed, ChunkPos.ZERO, biomes, sets);
		StructureMargins.register(this.state);
	}

	/** Templates from the vanilla data pack, for the structures that read them. */
	public static StructureTemplateManager templates(RegistryAccess registries) throws IOException {
		Path directory = Files.createTempDirectory("cubeworld-verify");
		try (LevelStorageSource.LevelStorageAccess access = LevelStorageSource.createDefault(directory).createAccess("verify")) {
			FallbackResourceManager data = new FallbackResourceManager(PackType.SERVER_DATA, "minecraft");
			data.push(ServerPacksSource.createVanillaPackSource().fullResources());
			return new StructureTemplateManager(
				data,
				access, DataFixers.getDataFixer(), registries.lookupOrThrow(Registries.BLOCK)
			);
		} finally {
			try (Stream<Path> files = Files.walk(directory)) {
				files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
			}
		}
	}

	/** Whether the set makes a valid structure in the chunk. When the generator fails, the answer is yes, so the map stays as it was. */
	public boolean generates(Holder<StructureSet> set, int chunkX, int chunkZ) {
		if (this.failed) {
			return true;
		}
		try {
			return SeamLayout.withActive(this.layout, () -> {
				Climate.Sampler climate = this.state.randomState().createClimateSampler(SamplerContext.builder().enableCaches().build());
				return FoldedStarts.startOf(set, this.generator, this.registries, this.state, this.templates, Level.OVERWORLD, this.heights, climate, chunkX, chunkZ) != null;
			});
		} catch (RuntimeException e) {
			LOGGER.error("Cannot check structures with the generator, so the map shows where they can start", e);
			this.failed = true;
			return true;
		}
	}
}
