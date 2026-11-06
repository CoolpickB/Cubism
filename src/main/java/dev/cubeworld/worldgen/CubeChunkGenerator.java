package dev.cubeworld.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.mixin.ChunkGeneratorAccessor;
import dev.cubeworld.mixin.RandomStateAccessor;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CubeChunkGenerator extends ChunkGenerator {
	public static final MapCodec<CubeChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
		instance.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
			NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(CubeChunkGenerator::generatorSettings),
			Codec.intRange(CubePlanetMath.MIN_DIAMETER_CHUNKS, CubePlanetMath.MAX_DIAMETER_CHUNKS)
				.optionalFieldOf("diameter_chunks", CubePlanetMath.MIN_DIAMETER_CHUNKS)
				.forGetter(CubeChunkGenerator::diameterChunks)
		).apply(instance, CubeChunkGenerator::new)
	);

	private static final int MAX_ATMOSPHERE_ABOVE_SURFACE = 96;
	// Trees grow up to about 30 blocks. Assigning a vegetation feature by a point 12 blocks above its base keeps
	// the full tree inside the 1-chunk write margin on side faces, where up goes across chunks.
	private static final int VEGETATION_ASSIGN_SHIFT = 12;
	private static final int MAX_SURFACE_INDEX = 512;
	private static final int MAX_CACHED_FACE_CHUNKS = 16384;

	private static final BlockState AIR = Blocks.AIR.defaultBlockState();
	private static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
	private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();
	private static final BlockState STONE = Blocks.STONE.defaultBlockState();
	private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
	private static final BlockState COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState();
	private static final BlockState MUD = Blocks.MUD.defaultBlockState();
	private static final BlockState MYCELIUM = Blocks.MYCELIUM.defaultBlockState();
	private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
	private static final BlockState SNOWY_GRASS = Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, true);
	private static final BlockState PODZOL = Blocks.PODZOL.defaultBlockState();
	private static final BlockState SAND = Blocks.SAND.defaultBlockState();
	private static final BlockState SANDSTONE = Blocks.SANDSTONE.defaultBlockState();
	private static final BlockState RED_SAND = Blocks.RED_SAND.defaultBlockState();
	private static final BlockState RED_SANDSTONE = Blocks.RED_SANDSTONE.defaultBlockState();
	private static final BlockState TERRACOTTA = Blocks.TERRACOTTA.defaultBlockState();
	private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
	private static final BlockState CLAY = Blocks.CLAY.defaultBlockState();
	private static final BlockState SNOW_BLOCK = Blocks.SNOW_BLOCK.defaultBlockState();
	private static final BlockState POWDER_SNOW = Blocks.POWDER_SNOW.defaultBlockState();
	private static final BlockState PACKED_ICE = Blocks.PACKED_ICE.defaultBlockState();
	private static final BlockState CALCITE = Blocks.CALCITE.defaultBlockState();

	private final Holder<NoiseGeneratorSettings> settings;
	private final int diameterChunks;
	private final int radiusBlocks;
	private final int centerY;
	private final int innerBedrockRadius;
	private volatile NoiseRouter cubeRouter;
	private volatile RandomState activeRandomState;
	private final Map<Long, short[]> faceHeightCache = new ConcurrentHashMap<>();
	private final Map<Long, Set<Holder<Biome>>> faceBiomeCache = new ConcurrentHashMap<>();

	public CubeChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings, int diameterChunks) {
		super(biomeSource);
		this.settings = settings;
		this.diameterChunks = Mth.clamp(diameterChunks, CubePlanetMath.MIN_DIAMETER_CHUNKS, CubePlanetMath.MAX_DIAMETER_CHUNKS);
		this.radiusBlocks = CubePlanetMath.getRadiusBlocks(this.diameterChunks);
		this.centerY = CubePlanetMath.getCenterY(this.diameterChunks);
		this.innerBedrockRadius = CubePlanetMath.getInnerBedrockRadius(this.diameterChunks);
	}

	public Holder<NoiseGeneratorSettings> generatorSettings() {
		return this.settings;
	}

	public int diameterChunks() {
		return this.diameterChunks;
	}

	public int radiusBlocks() {
		return this.radiusBlocks;
	}

	public int centerY() {
		return this.centerY;
	}

	public int innerBedrockRadius() {
		return this.innerBedrockRadius;
	}

	public NoiseRouter getOrCreateCubeRouter() {
		NoiseRouter router = this.cubeRouter;
		if (router == null) {
			synchronized (this) {
				router = this.cubeRouter;
				if (router == null) {
					router = CubeDensityFunctions.rewriteRouter(
						this.settings.value().noiseRouter(),
						this.centerY,
						this.radiusBlocks,
						this.innerBedrockRadius
					);
					this.cubeRouter = router;
				}
			}
		}
		return router;
	}

	private NoiseRouter ensureCubeRouterInstalled(RandomState randomState) {
		NoiseRouter router = getOrCreateCubeRouter();
		this.activeRandomState = randomState;
		RandomStateAccessor accessor = (RandomStateAccessor) (Object) randomState;
		if (accessor.cubeworld$getRouter() != router) {
			accessor.cubeworld$setRouter(router);
		}
		return router;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public ChunkGeneratorStructureState createState(
		HolderLookup<StructureSet> structureSets,
		RandomState randomState,
		long seed
	) {
		ensureCubeRouterInstalled(randomState);
		return super.createState(structureSets, randomState, seed);
	}

	@Override
	public CompletableFuture<ChunkAccess> createBiomes(
		RandomState randomState,
		Blender blender,
		StructureManager structureManager,
		ChunkAccess chunk
	) {
		ensureCubeRouterInstalled(randomState);
		return super.createBiomes(randomState, blender, structureManager, chunk);
	}

	@Override
	public ChunkPos getOrigin(RandomState randomState) {
		ensureCubeRouterInstalled(randomState);
		return new ChunkPos(0, 0);
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(
		ChunkAccess chunk,
		Blender blender,
		RandomState randomState,
		StructureManager structureManager,
		BiomeManager biomeManager,
		WorldGenRegion region,
		Set<Holder<Biome>> biomes
	) {
		ensureCubeRouterInstalled(randomState);

		ChunkPos chunkPos = chunk.getPos();
		int minBlockX = chunkPos.getMinBlockX();
		int minBlockZ = chunkPos.getMinBlockZ();
		int maxBlockX = chunkPos.getMaxBlockX();
		int maxBlockZ = chunkPos.getMaxBlockZ();

		int maxExtent = this.radiusBlocks + MAX_ATMOSPHERE_ABOVE_SURFACE;
		int closestX = Mth.clamp(0, minBlockX, maxBlockX);
		int closestZ = Mth.clamp(0, minBlockZ, maxBlockZ);
		if (Math.abs(closestX) > maxExtent || Math.abs(closestZ) > maxExtent) {
			return CompletableFuture.completedFuture(chunk);
		}

		return CompletableFuture.supplyAsync(
			() -> generateChunkTerrain(chunk, randomState, minBlockX, minBlockZ, maxExtent),
			Util.backgroundExecutor().forName("buildTerrain")
		);
	}

	private ChunkAccess generateChunkTerrain(
		ChunkAccess chunk,
		RandomState randomState,
		int minBlockX,
		int minBlockZ,
		int maxExtent
	) {
		NoiseRouter router = ensureCubeRouterInstalled(randomState);
		DensitySampler finalDensitySampler = randomState.getSampler(router.finalDensity());

		SamplerContext ctx = SamplerContext.builder().enableCaches().build();
		long seed = randomState.seed();

		int chunkMinY = chunk.getMinY();
		int chunkMaxY = chunkMinY + chunk.getHeight() - 1;
		int minY = Math.max(chunkMinY, this.centerY - maxExtent);
		int maxY = Math.min(chunkMaxY, this.centerY + maxExtent);
		if (minY > maxY) {
			return chunk;
		}

		int cellMinY = minY & ~3;
		int cellMaxY = (maxY + 3) & ~3;
		int numCellsY = (cellMaxY - cellMinY) >> 2;

		int deepslateThreshold = (this.radiusBlocks - this.innerBedrockRadius) / 2;

		Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
		Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);

		float[][] lowerSlice = new float[5][5];
		float[][] upperSlice = new float[5][5];

		sampleHorizontalSlice(finalDensitySampler, ctx, minBlockX, cellMinY, minBlockZ, maxExtent, lowerSlice);

		for (int cyCell = 0; cyCell < numCellsY; cyCell++) {
			int wy0 = cellMinY + (cyCell << 2);
			int wy1 = wy0 + 4;
			sampleHorizontalSlice(finalDensitySampler, ctx, minBlockX, wy1, minBlockZ, maxExtent, upperSlice);

			for (int cx = 0; cx < 4; cx++) {
				int baseLx = cx << 2;
				int baseWx = minBlockX + baseLx;

				for (int cz = 0; cz < 4; cz++) {
					int baseLz = cz << 2;
					int baseWz = minBlockZ + baseLz;

					float d000 = lowerSlice[cx][cz];
					float d100 = lowerSlice[cx + 1][cz];
					float d001 = lowerSlice[cx][cz + 1];
					float d101 = lowerSlice[cx + 1][cz + 1];
					float d010 = upperSlice[cx][cz];
					float d110 = upperSlice[cx + 1][cz];
					float d011 = upperSlice[cx][cz + 1];
					float d111 = upperSlice[cx + 1][cz + 1];

					int midCheb = CubePlanetMath.getChebyshevDistance(baseWx + 2, wy0 + 2, baseWz + 2, this.centerY);
					int midSecond = CubePlanetMath.getSecondMaxCoord(baseWx + 2, wy0 + 2, baseWz + 2, this.centerY);
					// Along the 90-degree cube edges/corners, 4x4x4 trilinear interpolation chamfers off a 2x2 line of blocks;
					// sample exact per-block density whenever a cell straddles a cube edge near the surface
					boolean nearCubeEdge = midSecond >= midCheb - 6
						&& midCheb >= this.radiusBlocks - 32
						&& midCheb <= this.radiusBlocks + 48;

					if (!nearCubeEdge
						&& d000 <= 0.0f && d100 <= 0.0f && d001 <= 0.0f && d101 <= 0.0f
						&& d010 <= 0.0f && d110 <= 0.0f && d011 <= 0.0f && d111 <= 0.0f) {
						if (midCheb < this.innerBedrockRadius - 4 || midCheb > this.innerBedrockRadius + 8) {
							continue;
						}
					}

					for (int dy = 0; dy < 4; dy++) {
						int wy = wy0 + dy;
						if (wy < minY || wy > maxY) {
							continue;
						}
						float fy = dy * 0.25f;
						float d00 = Mth.lerp(fy, d000, d010);
						float d10 = Mth.lerp(fy, d100, d110);
						float d01 = Mth.lerp(fy, d001, d011);
						float d11 = Mth.lerp(fy, d101, d111);

						LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(wy));
						int secY = SectionPos.sectionRelative(wy);
						int ay = Math.abs(wy - this.centerY);

						for (int dx = 0; dx < 4; dx++) {
							int lx = baseLx + dx;
							int wx = baseWx + dx;
							int ax = Math.abs(wx);
							float fx = dx * 0.25f;
							float d0 = Mth.lerp(fx, d00, d10);
							float d1 = Mth.lerp(fx, d01, d11);

							for (int dz = 0; dz < 4; dz++) {
								int lz = baseLz + dz;
								int wz = baseWz + dz;
								int az = Math.abs(wz);
								int cheb = Math.max(ax, Math.max(ay, az));

								if (cheb < this.innerBedrockRadius) {
									continue;
								}

								int distFromCore = cheb - this.innerBedrockRadius;
								if (distFromCore == 0) {
									setSectionBlock(section, oceanFloor, worldSurface, lx, wy, secY, lz, BEDROCK);
									continue;
								}
								if (distFromCore < 5) {
									long h = hashCoords(wx, wy, wz, seed);
									if (Math.floorMod(h, 5) >= distFromCore) {
										setSectionBlock(section, oceanFloor, worldSurface, lx, wy, secY, lz, BEDROCK);
										continue;
									}
								}

								float density;
								if (nearCubeEdge) {
									density = finalDensitySampler.sampleValue(ctx, wx, wy, wz);
								} else {
									float fz = dz * 0.25f;
									density = Mth.lerp(fz, d0, d1);
								}

								if (density > 0.0f) {
									BlockState block = pickBaseStone(wx, wy, wz, distFromCore, deepslateThreshold, seed);
									setSectionBlock(section, oceanFloor, worldSurface, lx, wy, secY, lz, block);
								}
							}
						}
					}
				}
			}

			float[][] swap = lowerSlice;
			lowerSlice = upperSlice;
			upperSlice = swap;
		}

		// Fill ocean & river basins up to sea level (cheb == radiusBlocks) on all 6 faces
		fillAllFacesOceans(chunk, oceanFloor, worldSurface, minBlockX, minBlockZ, minY, maxY, finalDensitySampler, ctx);

		// Apply biome-authentic surface rules along all 6 faces
		applyOrientedSurfaceRules(
			chunk,
			oceanFloor,
			worldSurface,
			minBlockX,
			minBlockZ,
			minY,
			maxY,
			seed,
			finalDensitySampler,
			ctx
		);

		return chunk;
	}

	private void sampleHorizontalSlice(
		DensitySampler finalDensitySampler,
		SamplerContext ctx,
		int minBlockX,
		int wy,
		int minBlockZ,
		int maxExtent,
		float[][] slice
	) {
		int ay = Math.abs(wy - this.centerY);
		for (int gx = 0; gx < 5; gx++) {
			int wx = minBlockX + (gx << 2);
			int ax = Math.abs(wx);
			for (int gz = 0; gz < 5; gz++) {
				int wz = minBlockZ + (gz << 2);
				int az = Math.abs(wz);
				int cheb = Math.max(ax, Math.max(ay, az));
				if (cheb < this.innerBedrockRadius - 4 || cheb > maxExtent + 4) {
					slice[gx][gz] = -1.0f;
				} else {
					slice[gx][gz] = finalDensitySampler.sampleValue(ctx, wx, wy, wz);
				}
			}
		}
	}

	private BlockState pickBaseStone(
		int wx,
		int wy,
		int wz,
		int distFromCore,
		int deepslateThreshold,
		long seed
	) {
		if (distFromCore < deepslateThreshold - 4) {
			return DEEPSLATE;
		}
		if (distFromCore <= deepslateThreshold + 4) {
			long h = hashCoords(wx, wy, wz, seed ^ 0x5DEECE66DL);
			if (Math.floorMod(h >> 8, 9) >= (distFromCore - (deepslateThreshold - 4))) {
				return DEEPSLATE;
			}
		}
		return STONE;
	}

	private void fillAllFacesOceans(
		ChunkAccess chunk,
		Heightmap oceanFloor,
		Heightmap worldSurface,
		int minBlockX,
		int minBlockZ,
		int minY,
		int maxY,
		DensitySampler finalDensitySampler,
		SamplerContext ctx
	) {
		int minBasinCheb = Math.max(this.innerBedrockRadius + 16, this.radiusBlocks - 32);
		int maxSafeEdgeCoord = this.radiusBlocks - 2;

		int minSecIdx = chunk.getSectionIndex(minY);
		int maxSecIdx = chunk.getSectionIndex(maxY);

		for (int secIdx = minSecIdx; secIdx <= maxSecIdx; secIdx++) {
			int secBaseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(secIdx));
			int secMidY = secBaseY + 8;
			int minPossibleCheb = Math.max(
				Math.abs(Mth.clamp(0, minBlockX, minBlockX + 15)),
				Math.max(Math.abs(secMidY - this.centerY) - 8, Math.abs(Mth.clamp(0, minBlockZ, minBlockZ + 15)))
			);
			if (minPossibleCheb > this.radiusBlocks) {
				continue;
			}

			LevelChunkSection section = chunk.getSection(secIdx);
			for (int sy = 0; sy < 16; sy++) {
				int wy = secBaseY + sy;
				if (wy < minY || wy > maxY) {
					continue;
				}
				int ay = Math.abs(wy - this.centerY);

				for (int lx = 0; lx < 16; lx++) {
					int wx = minBlockX + lx;
					int ax = Math.abs(wx);

					for (int lz = 0; lz < 16; lz++) {
						int wz = minBlockZ + lz;
						int az = Math.abs(wz);
						int cheb = Math.max(ax, Math.max(ay, az));
						if (cheb < minBasinCheb || cheb > this.radiusBlocks) {
							continue;
						}
						if (!section.getBlockState(lx, sy, lz).isAir()) {
							continue;
						}
						int secondMax = CubePlanetMath.getSecondMaxCoord(wx, wy, wz, this.centerY);
						if (secondMax > maxSafeEdgeCoord) {
							continue;
						}

						Direction outward = CubePlanetMath.getPyramidOutwardFace(wx, wy, wz, this.centerY);
						int stepsToSky = (this.radiusBlocks + 2) - cheb;
						boolean hasRoof = false;
						for (int s = 1; s <= stepsToSky; s++) {
							int rx = wx + outward.getStepX() * s;
							int ry = wy + outward.getStepY() * s;
							int rz = wz + outward.getStepZ() * s;
							BlockState aboveState = getNeighborBlockOrSample(chunk, minBlockX, minBlockZ, rx, ry, rz, finalDensitySampler, ctx);
							if (!aboveState.isAir() && !aboveState.is(Blocks.WATER)) {
								hasRoof = true;
								break;
							}
						}
						if (!hasRoof) {
							setSectionBlock(section, oceanFloor, worldSurface, lx, wy, sy, lz, WATER);
						}
					}
				}
			}
		}
	}

	private void applyOrientedSurfaceRules(
		ChunkAccess chunk,
		Heightmap oceanFloor,
		Heightmap worldSurface,
		int minBlockX,
		int minBlockZ,
		int minY,
		int maxY,
		long seed,
		DensitySampler finalDensitySampler,
		SamplerContext ctx
	) {
		int minSecIdx = chunk.getSectionIndex(minY);
		int maxSecIdx = chunk.getSectionIndex(maxY);
		int minSurfaceCheb = Math.max(this.innerBedrockRadius + 8, this.radiusBlocks - 36);

		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

		for (int secIdx = minSecIdx; secIdx <= maxSecIdx; secIdx++) {
			LevelChunkSection section = chunk.getSection(secIdx);
			if (section.hasOnlyAir()) {
				continue;
			}
			int secBaseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(secIdx));

			for (int sy = 0; sy < 16; sy++) {
				int wy = secBaseY + sy;
				if (wy < minY || wy > maxY) {
					continue;
				}
				int ay = Math.abs(wy - this.centerY);

				for (int lx = 0; lx < 16; lx++) {
					int wx = minBlockX + lx;
					int ax = Math.abs(wx);

					for (int lz = 0; lz < 16; lz++) {
						BlockState current = section.getBlockState(lx, sy, lz);
						if (!current.is(Blocks.STONE)) {
							continue;
						}

						int wz = minBlockZ + lz;
						int az = Math.abs(wz);
						int cheb = Math.max(ax, Math.max(ay, az));
						if (cheb < minSurfaceCheb) {
							continue;
						}

						Direction outward = CubePlanetMath.getPyramidOutwardFace(wx, wy, wz, this.centerY);
						int ox = wx + outward.getStepX();
						int oy = wy + outward.getStepY();
						int oz = wz + outward.getStepZ();

						BlockState neighborOut = getNeighborBlockOrSample(chunk, minBlockX, minBlockZ, ox, oy, oz, finalDensitySampler, ctx);
						if (!neighborOut.isAir() && !neighborOut.is(Blocks.WATER)) {
							continue;
						}

						boolean underwater = neighborOut.is(Blocks.WATER);
						Holder<Biome> biomeHolder = chunk.getNoiseBiome(wx >> 2, wy >> 2, wz >> 2);
						String biomePath = biomeHolder.unwrapKey().map(k -> k.identifier().getPath()).orElse("plains");
						long h = hashCoords(wx, wy, wz, seed ^ 0x12345678L);

						BlockState topMat;
						BlockState underMat;
						int subDepth = 3;

						if (biomePath.contains("desert")) {
							topMat = SAND;
							underMat = SANDSTONE;
							subDepth = 5;
						} else if (biomePath.contains("badlands")) {
							if (biomePath.contains("wooded") && cheb > this.radiusBlocks + 12) {
								topMat = (Math.floorMod(h, 3) == 0) ? COARSE_DIRT : GRASS;
								underMat = TERRACOTTA;
							} else {
								topMat = RED_SAND;
								underMat = pickBadlandsTerracotta(cheb);
							}
							subDepth = 6;
						} else if (biomePath.contains("mushroom")) {
							topMat = MYCELIUM;
							underMat = DIRT;
						} else if (biomePath.contains("mangrove")) {
							topMat = MUD;
							underMat = MUD;
						} else if (biomePath.contains("beach") && !biomePath.contains("snowy")) {
							topMat = SAND;
							underMat = SANDSTONE;
							subDepth = 4;
						} else if (biomePath.contains("snowy_beach")) {
							topMat = SAND;
							underMat = SANDSTONE;
						} else if (biomePath.contains("stony_shore") || biomePath.contains("stony_peaks")) {
							topMat = (biomePath.contains("stony_peaks") && Math.floorMod(h, 6) == 0) ? CALCITE : STONE;
							underMat = STONE;
						} else if (biomePath.contains("jagged_peaks") || biomePath.contains("frozen_peaks") || biomePath.contains("snowy_slopes") || biomePath.contains("grove")) {
							topMat = (Math.floorMod(h, 5) == 0) ? POWDER_SNOW : SNOW_BLOCK;
							underMat = biomePath.contains("frozen_peaks") ? PACKED_ICE : SNOW_BLOCK;
						} else if (biomePath.contains("ice_spikes")) {
							topMat = SNOW_BLOCK;
							underMat = DIRT;
						} else if (underwater) {
							if (biomePath.contains("warm_ocean") || biomePath.contains("lukewarm")) {
								topMat = SAND;
								underMat = SANDSTONE;
							} else if (biomePath.contains("river") || biomePath.contains("swamp")) {
								topMat = (Math.floorMod(h, 3) == 0) ? CLAY : DIRT;
								underMat = DIRT;
							} else {
								topMat = (Math.floorMod(h, 2) == 0) ? GRAVEL : DIRT;
								underMat = DIRT;
							}
						} else if (biomePath.contains("old_growth_pine") || biomePath.contains("old_growth_spruce")) {
							int m = (int) Math.floorMod(h, 5);
							topMat = (m == 0) ? COARSE_DIRT : ((m <= 2) ? PODZOL : GRASS);
							underMat = DIRT;
						} else if (biomePath.contains("windswept_gravelly")) {
							topMat = GRAVEL;
							underMat = STONE;
						} else if (biomePath.contains("windswept_savanna")) {
							topMat = (Math.floorMod(h, 3) == 0) ? COARSE_DIRT : ((Math.floorMod(h, 4) == 0) ? STONE : GRASS);
							underMat = DIRT;
						} else if (biomePath.contains("snowy")) {
							topMat = SNOWY_GRASS;
							underMat = DIRT;
						} else {
							topMat = GRASS;
							underMat = DIRT;
						}

						setSectionBlock(section, oceanFloor, worldSurface, lx, wy, sy, lz, topMat);

						int inStepX = -outward.getStepX();
						int inStepY = -outward.getStepY();
						int inStepZ = -outward.getStepZ();
						for (int depth = 1; depth <= subDepth; depth++) {
							int ilx = lx + inStepX * depth;
							int iwy = wy + inStepY * depth;
							int ilz = lz + inStepZ * depth;
							if (ilx < 0 || ilx >= 16 || ilz < 0 || ilz >= 16 || iwy < minY || iwy > maxY) {
								break;
							}
							pos.set(minBlockX + ilx, iwy, minBlockZ + ilz);
							if (chunk.getBlockState(pos).is(Blocks.STONE)) {
								LevelChunkSection inSec = chunk.getSection(chunk.getSectionIndex(iwy));
								BlockState mat = (topMat == RED_SAND && depth >= 2)
									? pickBadlandsTerracotta(cheb - depth)
									: underMat;
								setSectionBlock(inSec, oceanFloor, worldSurface, ilx, iwy, SectionPos.sectionRelative(iwy), ilz, mat);
							}
						}
					}
				}
			}
		}
	}

	private BlockState pickBadlandsTerracotta(int cheb) {
		return switch (Math.floorMod(cheb, 4)) {
			case 0 -> RED_SANDSTONE;
			case 1 -> COARSE_DIRT;
			default -> TERRACOTTA;
		};
	}

	private BlockState getNeighborBlockOrSample(
		ChunkAccess chunk,
		int minBlockX,
		int minBlockZ,
		int wx,
		int wy,
		int wz,
		DensitySampler finalDensitySampler,
		SamplerContext ctx
	) {
		int lx = wx - minBlockX;
		int lz = wz - minBlockZ;
		if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16 && wy >= chunk.getMinY() && wy < chunk.getMinY() + chunk.getHeight()) {
			LevelChunkSection sec = chunk.getSection(chunk.getSectionIndex(wy));
			if (sec.hasOnlyAir()) {
				return AIR;
			}
			return sec.getBlockState(lx, SectionPos.sectionRelative(wy), lz);
		}
		int cheb = CubePlanetMath.getChebyshevDistance(wx, wy, wz, this.centerY);
		if (cheb < this.innerBedrockRadius) {
			return AIR;
		}
		return finalDensitySampler.sampleValue(ctx, wx, wy, wz) > 0.0f ? STONE : AIR;
	}

	private static void setSectionBlock(
		LevelChunkSection section,
		Heightmap oceanFloor,
		Heightmap worldSurface,
		int lx,
		int wy,
		int secY,
		int lz,
		BlockState state
	) {
		section.setBlockState(lx, secY, lz, state, false);
		oceanFloor.update(lx, wy, lz, state);
		worldSurface.update(lx, wy, lz, state);
	}

	/**
	 * Runs the vanilla placed features of each face in the virtual frame of that face (see
	 * {@link OrientedWorldGenLevel}). Each face is cut into virtual chunks. Vanilla placement gives the feature
	 * positions of a virtual chunk, and this chunk places the features whose position falls inside it. Thus each
	 * feature is placed exactly once, from inside the region that vanilla permits.
	 */
	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
		RandomState randomState = level.getLevel().getChunkSource().randomState();
		ensureCubeRouterInstalled(randomState);

		ChunkPos chunkPos = chunk.getPos();
		int minX = chunkPos.getMinBlockX();
		int minZ = chunkPos.getMinBlockZ();
		int maxExtent = this.radiusBlocks + MAX_ATMOSPHERE_ABOVE_SURFACE;
		if (Math.abs(Mth.clamp(0, minX, minX + 15)) > maxExtent || Math.abs(Mth.clamp(0, minZ, minZ + 15)) > maxExtent) {
			return;
		}

		List<FeatureSorter.StepFeatureData> featureSteps = ((ChunkGeneratorAccessor) this).cubeworld$getFeaturesPerStep().get();
		int minY = Math.max(level.getMinY(), this.centerY - maxExtent);
		int maxY = Math.min(level.getMinY() + level.getHeight() - 1, this.centerY + maxExtent);
		int lowestOrigin = OrientedWorldGenLevel.VIRTUAL_MIN_Y;
		int highestOrigin = this.centerY + maxExtent + 1;
		int faceReach = Math.floorDiv(maxExtent, 16) + 1;

		for (Direction face : Direction.values()) {
			BlockPos a = OrientedWorldGenLevel.toVirtual(face, this.centerY, minX, minY, minZ);
			BlockPos b = OrientedWorldGenLevel.toVirtual(face, this.centerY, minX + 15, maxY, minZ + 15);
			int windowMinY = Math.min(a.getY(), b.getY());
			int windowMaxY = Math.max(a.getY(), b.getY());
			if (windowMaxY < lowestOrigin || windowMinY - VEGETATION_ASSIGN_SHIFT > highestOrigin) {
				continue;
			}
			int minVcx = Math.max(-faceReach, Math.min(a.getX(), b.getX()) >> 4);
			int maxVcx = Math.min(faceReach, Math.max(a.getX(), b.getX()) >> 4);
			int minVcz = Math.max(-faceReach, Math.min(a.getZ(), b.getZ()) >> 4);
			int maxVcz = Math.min(faceReach, Math.max(a.getZ(), b.getZ()) >> 4);

			OrientedWorldGenLevel faceLevel = new OrientedWorldGenLevel(level, this, face, chunkPos);
			for (int vcx = minVcx; vcx <= maxVcx; vcx++) {
				for (int vcz = minVcz; vcz <= maxVcz; vcz++) {
					// No feature starts above the surface of this virtual chunk.
					if (windowMinY - VEGETATION_ASSIGN_SHIFT > faceHeights(face, vcx, vcz)[MAX_SURFACE_INDEX]) {
						continue;
					}
					decorateVirtualChunk(level, faceLevel, featureSteps, vcx, vcz, chunkPos);
				}
			}
		}
		level.setCurrentlyGenerating(null);
	}

	private void decorateVirtualChunk(
		WorldGenLevel level,
		OrientedWorldGenLevel faceLevel,
		List<FeatureSorter.StepFeatureData> featureSteps,
		int vcx,
		int vcz,
		ChunkPos chunkPos
	) {
		Direction face = faceLevel.face();
		BlockPos origin = new BlockPos(vcx << 4, OrientedWorldGenLevel.VIRTUAL_MIN_Y, vcz << 4);
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
		// The top face is the vanilla frame, so it keeps the vanilla seed.
		long faceSeed = face == Direction.UP ? level.getSeed() : level.getSeed() + face.ordinal() * 0x9E3779B97F4A7C15L;
		long decorationSeed = random.setDecorationSeed(faceSeed, origin.getX(), origin.getZ());
		Set<Holder<Biome>> biomes = faceBiomes(faceLevel, vcx, vcz);
		Registry<PlacedFeature> featureRegistry = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
		WorldgenRandom placeRandom = new WorldgenRandom(new XoroshiroRandomSource(0L));

		for (int stepIndex = 0; stepIndex < featureSteps.size(); stepIndex++) {
			FeatureSorter.StepFeatureData stepData = featureSteps.get(stepIndex);
			IntSet possibleFeatures = new IntArraySet();
			for (Holder<Biome> biome : biomes) {
				List<HolderSet<PlacedFeature>> featuresInBiome = this.getBiomeGenerationSettings(biome).features();
				if (stepIndex < featuresInBiome.size()) {
					for (Holder<PlacedFeature> feature : featuresInBiome.get(stepIndex)) {
						possibleFeatures.add(stepData.indexMapping().applyAsInt(feature.value()));
					}
				}
			}
			int[] featureIndices = possibleFeatures.toIntArray();
			Arrays.sort(featureIndices);
			int assignShift = stepIndex == GenerationStep.Decoration.VEGETAL_DECORATION.ordinal() ? VEGETATION_ASSIGN_SHIFT : 0;

			for (int featureIndex : featureIndices) {
				PlacedFeature placed = stepData.features().get(featureIndex);
				level.setCurrentlyGenerating(() -> featureRegistry.getResourceKey(placed).map(Object::toString).orElseGet(placed::toString));
				random.setFeatureSeed(decorationSeed, featureIndex, stepIndex);
				Feature feature = placed.feature().value();
				if (feature instanceof SnowAndFreezeFeature) {
					freezeColumns(faceLevel, origin, chunkPos);
					continue;
				}

				List<BlockPos> positions = placementPositions(faceLevel, placed, random, origin);
				for (int i = 0; i < positions.size(); i++) {
					BlockPos pos = positions.get(i);
					if (!isInChunk(faceLevel.toWorld(pos.above(assignShift)), chunkPos)) {
						continue;
					}
					BlockPos worldPos = faceLevel.toWorld(pos);
					if (CubePlanetMath.getPyramidOutwardFace(worldPos.getX(), worldPos.getY(), worldPos.getZ(), this.centerY) != face) {
						continue;
					}
					if (hasBiomeFilter(placed) && !this.getBiomeGenerationSettings(faceLevel.getBiome(pos)).hasFeature(placed)) {
						continue;
					}
					// Each position gets its own random, so the result does not depend on which chunk places it.
					placeRandom.setFeatureSeed(decorationSeed + (i + 1L) * 0x5DEECE66DL, featureIndex, stepIndex);
					feature.place(faceLevel, this, placeRandom, pos);
				}
			}
		}
	}

	/**
	 * The positions that {@code FeaturePlacer} gives, in the same order and with the same random calls. The biome
	 * filter is left out here because it reads the biome; the caller applies it to the positions that it keeps.
	 */
	private List<BlockPos> placementPositions(OrientedWorldGenLevel faceLevel, PlacedFeature placed, RandomSource random, BlockPos origin) {
		List<PlacementModifier> modifiers = placed.placement();
		List<BlockPos> result = new ArrayList<>();
		if (modifiers.isEmpty()) {
			result.add(origin);
			return result;
		}
		PlacementContext context = new PlacementContext(faceLevel, this, Optional.of(placed));
		List<BlockPos> pending = new ArrayList<>();
		IntArrayList pendingModifier = new IntArrayList();
		List<BlockPos> modified = new ArrayList<>();
		pending.add(origin);
		pendingModifier.add(0);
		while (!pending.isEmpty()) {
			BlockPos pos = pending.removeLast();
			int modifierIndex = pendingModifier.removeInt(pendingModifier.size() - 1);
			PlacementModifier modifier = modifiers.get(modifierIndex);
			modified.clear();
			if (modifier instanceof BiomeFilter) {
				modified.add(pos);
			} else {
				modifier.modify(context, random, pos, modified::add);
			}
			int next = modifierIndex + 1;
			if (next < modifiers.size()) {
				for (int i = modified.size() - 1; i >= 0; i--) {
					pending.add(modified.get(i));
					pendingModifier.add(next);
				}
			} else {
				result.addAll(modified);
			}
		}
		return result;
	}

	private static boolean hasBiomeFilter(PlacedFeature placed) {
		for (PlacementModifier modifier : placed.placement()) {
			if (modifier instanceof BiomeFilter) {
				return true;
			}
		}
		return false;
	}

	private static boolean isInChunk(BlockPos worldPos, ChunkPos chunkPos) {
		return (worldPos.getX() >> 4) == chunkPos.x() && (worldPos.getZ() >> 4) == chunkPos.z();
	}

	/** {@link SnowAndFreezeFeature} for the columns of a virtual chunk whose surface is in this chunk. */
	private void freezeColumns(OrientedWorldGenLevel faceLevel, BlockPos origin, ChunkPos chunkPos) {
		BlockPos.MutableBlockPos topPos = new BlockPos.MutableBlockPos();
		BlockPos.MutableBlockPos belowPos = new BlockPos.MutableBlockPos();
		for (int dx = 0; dx < 16; dx++) {
			for (int dz = 0; dz < 16; dz++) {
				int x = origin.getX() + dx;
				int z = origin.getZ() + dz;
				topPos.set(x, faceLevel.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
				BlockPos worldTop = faceLevel.toWorld(topPos);
				if (!isInChunk(worldTop, chunkPos)
					|| CubePlanetMath.getPyramidOutwardFace(worldTop.getX(), worldTop.getY(), worldTop.getZ(), this.centerY) != faceLevel.face()) {
					continue;
				}
				belowPos.set(topPos).move(Direction.DOWN);
				Biome biome = faceLevel.getBiome(topPos).value();
				if (biome.shouldFreeze(faceLevel, belowPos, false)) {
					faceLevel.setBlock(belowPos, Blocks.ICE.defaultBlockState(), 2);
				}
				if (biome.shouldSnow(faceLevel, topPos)) {
					faceLevel.setBlock(topPos, Blocks.SNOW.defaultBlockState(), 2);
					BlockState belowState = faceLevel.getBlockState(belowPos);
					if (belowState.hasProperty(SnowyBlock.SNOWY)) {
						faceLevel.setBlock(belowPos, belowState.setValue(SnowyBlock.SNOWY, true), 2);
					}
				}
			}
		}
	}

	public BiomeResolver createBiomeResolver() {
		return this.getBiomeSource().createCachingResolver(this.activeRandomState);
	}

	/** The biomes of a virtual chunk, from the surface down to the vanilla floor. Like vanilla, it limits the features to try. */
	private Set<Holder<Biome>> faceBiomes(OrientedWorldGenLevel faceLevel, int vcx, int vcz) {
		long key = faceCacheKey(faceLevel.face(), vcx, vcz);
		Set<Holder<Biome>> cached = this.faceBiomeCache.get(key);
		if (cached != null) {
			return cached;
		}
		Set<Holder<Biome>> biomes = new ObjectArraySet<>();
		for (int qx = 0; qx < 4; qx++) {
			for (int qz = 0; qz < 4; qz++) {
				int vx = (vcx << 4) + (qx << 2) + 2;
				int vz = (vcz << 4) + (qz << 2) + 2;
				int surface = faceHeight(faceLevel.face(), vx, vz, true);
				for (int vy = surface + 16; vy >= OrientedWorldGenLevel.VIRTUAL_MIN_Y; vy -= 16) {
					biomes.add(faceLevel.getBiome(new BlockPos(vx, vy, vz)));
				}
			}
		}
		biomes.retainAll(this.getBiomeSource().possibleBiomes());
		if (this.faceBiomeCache.size() > MAX_CACHED_FACE_CHUNKS) {
			this.faceBiomeCache.clear();
		}
		this.faceBiomeCache.put(key, biomes);
		return biomes;
	}

	/**
	 * The vanilla heightmap of a face column in the virtual frame, from the terrain density alone: the virtual Y
	 * above the top solid block, or above the sea for {@code oceanFloor == false}.
	 */
	public int faceHeight(Direction face, int vx, int vz, boolean oceanFloor) {
		short[] heights = faceHeights(face, vx >> 4, vz >> 4);
		return heights[(((vx & 15) << 4) | (vz & 15)) * 2 + (oceanFloor ? 0 : 1)];
	}

	private short[] faceHeights(Direction face, int vcx, int vcz) {
		long key = faceCacheKey(face, vcx, vcz);
		short[] cached = this.faceHeightCache.get(key);
		if (cached != null) {
			return cached;
		}

		DensitySampler sampler = this.activeRandomState.getSampler(getOrCreateCubeRouter().finalDensity());
		SamplerContext ctx = SamplerContext.builder().enableCaches().build();
		Long2FloatOpenHashMap corners = new Long2FloatOpenHashMap();
		corners.defaultReturnValue(Float.NaN);
		int seaY = this.centerY + this.radiusBlocks;
		int topY = seaY + MAX_ATMOSPHERE_ABOVE_SURFACE;
		int bottomY = this.centerY + this.innerBedrockRadius;
		short[] heights = new short[MAX_SURFACE_INDEX + 1];
		int maxSurface = Integer.MIN_VALUE;

		for (int lx = 0; lx < 16; lx++) {
			for (int lz = 0; lz < 16; lz++) {
				int vx = (vcx << 4) + lx;
				int vz = (vcz << 4) + lz;
				int floor = bottomY;
				for (int vy = topY; vy > bottomY; vy--) {
					BlockPos w = OrientedWorldGenLevel.toWorld(face, this.centerY, vx, vy, vz);
					if (terrainDensity(sampler, ctx, corners, w.getX(), w.getY(), w.getZ()) > 0.0f) {
						floor = vy + 1;
						break;
					}
				}
				int surface = floor;
				boolean seaColumn = Math.max(Math.abs(vx), Math.abs(vz)) <= this.radiusBlocks - 2;
				if (seaColumn && floor <= seaY) {
					surface = seaY + 1;
				}
				int index = ((lx << 4) | lz) * 2;
				heights[index] = (short) floor;
				heights[index + 1] = (short) surface;
				maxSurface = Math.max(maxSurface, surface);
			}
		}
		heights[MAX_SURFACE_INDEX] = (short) maxSurface;

		if (this.faceHeightCache.size() > MAX_CACHED_FACE_CHUNKS) {
			this.faceHeightCache.clear();
		}
		this.faceHeightCache.put(key, heights);
		return heights;
	}

	/** The density that {@link #generateChunkTerrain} gives a block: exact near cube edges, else interpolated in 4-block cells. */
	private float terrainDensity(DensitySampler sampler, SamplerContext ctx, Long2FloatOpenHashMap corners, int wx, int wy, int wz) {
		int x0 = wx & ~3;
		int y0 = wy & ~3;
		int z0 = wz & ~3;
		int midCheb = CubePlanetMath.getChebyshevDistance(x0 + 2, y0 + 2, z0 + 2, this.centerY);
		int midSecond = CubePlanetMath.getSecondMaxCoord(x0 + 2, y0 + 2, z0 + 2, this.centerY);
		if (midSecond >= midCheb - 6 && midCheb >= this.radiusBlocks - 32 && midCheb <= this.radiusBlocks + 48) {
			return sampler.sampleValue(ctx, wx, wy, wz);
		}
		float fx = (wx - x0) * 0.25f;
		float fy = (wy - y0) * 0.25f;
		float fz = (wz - z0) * 0.25f;
		float d00 = Mth.lerp(fy, densityCorner(sampler, ctx, corners, x0, y0, z0), densityCorner(sampler, ctx, corners, x0, y0 + 4, z0));
		float d10 = Mth.lerp(fy, densityCorner(sampler, ctx, corners, x0 + 4, y0, z0), densityCorner(sampler, ctx, corners, x0 + 4, y0 + 4, z0));
		float d01 = Mth.lerp(fy, densityCorner(sampler, ctx, corners, x0, y0, z0 + 4), densityCorner(sampler, ctx, corners, x0, y0 + 4, z0 + 4));
		float d11 = Mth.lerp(fy, densityCorner(sampler, ctx, corners, x0 + 4, y0, z0 + 4), densityCorner(sampler, ctx, corners, x0 + 4, y0 + 4, z0 + 4));
		return Mth.lerp(fz, Mth.lerp(fx, d00, d10), Mth.lerp(fx, d01, d11));
	}

	private float densityCorner(DensitySampler sampler, SamplerContext ctx, Long2FloatOpenHashMap corners, int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		float value = corners.get(key);
		if (Float.isNaN(value)) {
			int cheb = CubePlanetMath.getChebyshevDistance(x, y, z, this.centerY);
			value = (cheb < this.innerBedrockRadius - 4 || cheb > this.radiusBlocks + MAX_ATMOSPHERE_ABOVE_SURFACE + 4)
				? -1.0f
				: sampler.sampleValue(ctx, x, y, z);
			corners.put(key, value);
		}
		return value;
	}

	private static long faceCacheKey(Direction face, int vcx, int vcz) {
		return ((long) face.ordinal() << 60) | ((vcx & 0x3FFFFFFFL) << 30) | (vcz & 0x3FFFFFFFL);
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
	}

	@Override
	public int getGenDepth() {
		return 4064;
	}

	@Override
	public int getSeaLevel() {
		return CubePlanetMath.TOP_SURFACE_Y;
	}

	@Override
	public int getMinY() {
		return -2032;
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types heightmapType, LevelHeightAccessor heightAccessor, RandomState randomState) {
		NoiseRouter router = ensureCubeRouterInstalled(randomState);
		if (Math.abs(x) > this.radiusBlocks + MAX_ATMOSPHERE_ABOVE_SURFACE || Math.abs(z) > this.radiusBlocks + MAX_ATMOSPHERE_ABOVE_SURFACE) {
			return heightAccessor.getMinY();
		}

		DensitySampler finalDensitySampler = randomState.getSampler(router.finalDensity());
		SamplerContext ctx = SamplerContext.builder().enableCaches().build();

		int maxScanY = Math.min(heightAccessor.getMinY() + heightAccessor.getHeight() - 1, this.centerY + this.radiusBlocks + MAX_ATMOSPHERE_ABOVE_SURFACE);
		int minScanY = Math.max(heightAccessor.getMinY(), this.centerY + this.innerBedrockRadius);

		for (int y = maxScanY; y >= minScanY; y -= 2) {
			if (finalDensitySampler.sampleValue(ctx, x, y, z) > 0.0f) {
				if (y + 1 <= maxScanY && finalDensitySampler.sampleValue(ctx, x, y + 1, z) > 0.0f) {
					return y + 2;
				}
				return y + 1;
			}
		}
		return this.centerY + this.radiusBlocks + 1;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
		ensureCubeRouterInstalled(randomState);
		int minY = heightAccessor.getMinY();
		int height = heightAccessor.getHeight();
		BlockState[] column = new BlockState[height];
		for (int i = 0; i < height; i++) {
			column[i] = AIR;
		}

		if (Math.abs(x) <= this.radiusBlocks && Math.abs(z) <= this.radiusBlocks) {
			int topY = getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heightAccessor, randomState) - 1;
			for (int y = Math.max(minY, this.centerY); y <= Math.min(minY + height - 1, topY); y++) {
				int idx = y - minY;
				if (idx >= 0 && idx < height) {
					column[idx] = (y == topY) ? GRASS : STONE;
				}
			}
		}
		return new NoiseColumn(minY, column);
	}

	@Override
	public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos, SamplerContext ctx) {
		Direction outward = CubePlanetMath.getPyramidOutwardFace(pos.getX(), pos.getY(), pos.getZ(), this.centerY);
		int cheb = CubePlanetMath.getChebyshevDistance(pos.getX(), pos.getY(), pos.getZ(), this.centerY);
		info.add("CubeWorld: diameter=" + this.diameterChunks + "ch (" + (this.radiusBlocks * 2) + "b), centerY=" + this.centerY);
		info.add("CubeSector: face=" + outward.getName() + ", gravity=" + outward.getOpposite().getName() + ", radialDepth=" + (this.radiusBlocks - cheb));
	}

	private static long hashCoords(int x, int y, int z, long seed) {
		long h = seed;
		h ^= (long) x * 0x45D9F3BL;
		h = Long.rotateLeft(h, 21) * 0x9E3779B97F4A7C15L;
		h ^= (long) y * 0x119DE1F3L;
		h = Long.rotateLeft(h, 27) * 0xBF58476D1CE4E5B9L;
		h ^= (long) z * 0x27D4EB2FL;
		h ^= (h >>> 30);
		h *= 0xBF58476D1CE4E5B9L;
		h ^= (h >>> 27);
		h *= 0x94D049BB133111EBL;
		h ^= (h >>> 31);
		return h;
	}
}
