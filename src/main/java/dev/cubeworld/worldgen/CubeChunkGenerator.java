package dev.cubeworld.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.mixin.RandomStateAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
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
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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

	public CubeChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings, int diameterChunks) {
		super(biomeSource);
		this.settings = settings;
		this.diameterChunks = Mth.clamp(diameterChunks, CubePlanetMath.MIN_DIAMETER_CHUNKS, CubePlanetMath.MAX_DIAMETER_CHUNKS);
		this.radiusBlocks = CubePlanetMath.getRadiusBlocks(this.diameterChunks);
		this.centerY = CubePlanetMath.getCenterY(this.diameterChunks);
		this.innerBedrockRadius = CubePlanetMath.getInnerBedrockRadius(this.diameterChunks);
		CubePlanetMath.setClientCubeState(true, this.diameterChunks);
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

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
		RandomState randomState = level.getLevel().getChunkSource().randomState();
		ensureCubeRouterInstalled(randomState);

		ChunkPos chunkPos = chunk.getPos();
		int minX = chunkPos.getMinBlockX();
		int minZ = chunkPos.getMinBlockZ();
		long seed = level.getSeed();

		int maxExtent = this.radiusBlocks + 32;
		int closestX = Mth.clamp(0, minX, minX + 15);
		int closestZ = Mth.clamp(0, minZ, minZ + 15);
		if (Math.abs(closestX) > maxExtent || Math.abs(closestZ) > maxExtent) {
			return;
		}

		// 1. Place real vanilla underground ore & disk features across the chunk's crust
		placeVanillaUndergroundOres(level, chunk, chunkPos, minX, minZ, seed);

		// 2. Decorate Top (+Y) and Bottom (-Y) faces using real vanilla PlacedFeatures from BiomeGenerationSettings
		if (Math.abs(closestX) <= this.radiusBlocks && Math.abs(closestZ) <= this.radiusBlocks) {
			decorateHorizontalFaceWithVanillaFeatures(level, chunkPos, minX, minZ, Direction.UP, seed);
			decorateHorizontalFaceWithVanillaFeatures(level, chunkPos, minX, minZ, Direction.DOWN, seed);
		}

		// 3. Decorate Side faces (+X, -X, +Z, -Z) using real vanilla PlacedFeatures via OrientedWorldGenLevel
		for (Direction side : new Direction[]{Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.NORTH}) {
			int surfaceCoord = (side.getAxisDirection() == Direction.AxisDirection.POSITIVE) ? this.radiusBlocks : -this.radiusBlocks;
			if (side.getAxis() == Direction.Axis.X) {
				if (surfaceCoord >= minX - 36 && surfaceCoord <= minX + 51 && Math.abs(closestZ) <= this.radiusBlocks) {
					decorateSideFaceWithVanillaFeatures(level, chunk, chunkPos, minX, minZ, side, seed);
				}
			} else {
				if (surfaceCoord >= minZ - 36 && surfaceCoord <= minZ + 51 && Math.abs(closestX) <= this.radiusBlocks) {
					decorateSideFaceWithVanillaFeatures(level, chunk, chunkPos, minX, minZ, side, seed);
				}
			}
		}
	}

	private void placeVanillaUndergroundOres(
		WorldGenLevel level,
		ChunkAccess chunk,
		ChunkPos chunkPos,
		int minX,
		int minZ,
		long seed
	) {
		long chunkSeed = hashCoords(minX, 0, minZ, seed ^ 0x7F4A7C15L);
		RandomSource random = new LegacyRandomSource(chunkSeed);

		int minY = Math.max(chunk.getMinY() + 8, this.centerY - this.radiusBlocks);
		int maxY = Math.min(chunk.getMinY() + chunk.getHeight() - 8, this.centerY + this.radiusBlocks);
		if (minY >= maxY) {
			return;
		}

		// Sample several crust sections in this chunk and run the biome's real UNDERGROUND_ORES features directly on the world level
		for (int sample = 0; sample < 24; sample++) {
			int wx = minX + 4 + random.nextInt(8);
			int wz = minZ + 4 + random.nextInt(8);
			int wy = minY + random.nextInt(maxY - minY);
			int cheb = CubePlanetMath.getChebyshevDistance(wx, wy, wz, this.centerY);
			if (cheb < this.innerBedrockRadius + 6 || cheb > this.radiusBlocks - 8) {
				continue;
			}
			BlockPos wPos = new BlockPos(wx, wy, wz);
			BlockState state = level.getBlockState(wPos);
			if (!state.is(Blocks.STONE) && !state.is(Blocks.DEEPSLATE)) {
				continue;
			}

			Holder<Biome> biome = level.getBiome(wPos);
			List<HolderSet<PlacedFeature>> steps = this.getBiomeGenerationSettings(biome).features();
			int oreStepIdx = GenerationStep.Decoration.UNDERGROUND_ORES.ordinal();
			if (oreStepIdx < steps.size()) {
				HolderSet<PlacedFeature> oreFeatures = steps.get(oreStepIdx);
				if (oreFeatures.size() > 0) {
					Holder<PlacedFeature> chosen = oreFeatures.get(random.nextInt(oreFeatures.size()));
					String key = chosen.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
					if (key.contains("ore_")) {
						chosen.value().feature().value().place(level, this, random, wPos);
					}
				}
			}
		}
	}

	private void decorateHorizontalFaceWithVanillaFeatures(
		WorldGenLevel level,
		ChunkPos chunkPos,
		int minX,
		int minZ,
		Direction outward,
		long seed
	) {
		int seaY = (outward == Direction.UP)
			? (this.centerY + this.radiusBlocks)
			: (this.centerY - this.radiusBlocks);
		int maxFaceCoord = this.radiusBlocks - 3;

		for (int gx = 3; gx < 15; gx += 4) {
			for (int gz = 3; gz < 15; gz += 4) {
				long cellSeed = hashCoords(minX + gx, seaY, minZ + gz, seed ^ outward.ordinal() * 0x9E3779B9L);
				RandomSource random = new LegacyRandomSource(cellSeed);
				int wx = minX + gx + random.nextInt(3) - 1;
				int wz = minZ + gz + random.nextInt(3) - 1;
				if (Math.abs(wx) > maxFaceCoord || Math.abs(wz) > maxFaceCoord) {
					continue;
				}

				BlockPos surfaceAbove = findHorizontalFaceSurfaceAbove(level, wx, seaY, wz, outward);
				if (surfaceAbove != null) {
					OrientedWorldGenLevel orientedLevel = new OrientedWorldGenLevel(level, chunkPos, outward, this.centerY, surfaceAbove);
					runVanillaSurfaceFeaturesAt(level, orientedLevel, surfaceAbove, outward, random);
				}
			}
		}
	}

	private void decorateSideFaceWithVanillaFeatures(
		WorldGenLevel level,
		ChunkAccess chunk,
		ChunkPos chunkPos,
		int minX,
		int minZ,
		Direction outward,
		long seed
	) {
		int maxFaceCoord = this.radiusBlocks - 3;
		int minY = Math.max(chunk.getMinY() + 8, this.centerY - maxFaceCoord);
		int maxY = Math.min(chunk.getMinY() + chunk.getHeight() - 8, this.centerY + maxFaceCoord);

		for (int gt = 3; gt < 15; gt += 4) {
			int wTangent = (outward.getAxis() == Direction.Axis.X ? minZ : minX) + gt;
			if (Math.abs(wTangent) > maxFaceCoord) {
				continue;
			}

			for (int wyBase = minY + 2; wyBase <= maxY - 2; wyBase += 4) {
				long cellSeed = hashCoords(wTangent, wyBase, minX ^ minZ, seed ^ (outward.ordinal() * 0xBF58476DL));
				RandomSource random = new LegacyRandomSource(cellSeed);
				int lt = Mth.clamp(gt + random.nextInt(3) - 1, 1, 14);
				int wy = wyBase + random.nextInt(3) - 1;
				if (Math.abs(wy - this.centerY) > maxFaceCoord) {
					continue;
				}

				BlockPos surfaceAbove = findSideFaceSurfaceAboveInChunk(level, chunk, minX, minZ, lt, wy, outward);
				if (surfaceAbove != null) {
					OrientedWorldGenLevel orientedLevel = new OrientedWorldGenLevel(level, chunkPos, outward, this.centerY, surfaceAbove);
					runVanillaSurfaceFeaturesAt(level, orientedLevel, surfaceAbove, outward, random);
				}
			}
		}
	}

	private BlockPos findHorizontalFaceSurfaceAbove(WorldGenLevel level, int wx, int seaY, int wz, Direction outward) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(wx, seaY, wz).move(outward, 44);
		Direction inward = outward.getOpposite();
		for (int step = 0; step < 80; step++) {
			BlockState state = level.getBlockState(cursor);
			if (!state.isAir() && !state.is(Blocks.SNOW)) {
				if (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
					return null;
				}
				if (CubePlanetMath.getPyramidOutwardFace(cursor.getX(), cursor.getY(), cursor.getZ(), this.centerY) != outward) {
					return null;
				}
				BlockPos above = cursor.relative(outward);
				BlockState aboveState = level.getBlockState(above);
				if (aboveState.isAir() || aboveState.is(Blocks.WATER) || aboveState.is(Blocks.SNOW)) {
					return above;
				}
				return null;
			}
			cursor.move(inward);
		}
		return null;
	}

	private BlockPos findSideFaceSurfaceAboveInChunk(
		WorldGenLevel level,
		ChunkAccess chunk,
		int minX,
		int minZ,
		int localTangent,
		int wy,
		Direction outward
	) {
		LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(wy));
		if (section.hasOnlyAir()) {
			return null;
		}
		int secY = SectionPos.sectionRelative(wy);
		boolean positive = outward.getAxisDirection() == Direction.AxisDirection.POSITIVE;
		boolean xAxis = outward.getAxis() == Direction.Axis.X;

		int startStep = positive ? 15 : 0;
		int endStep = positive ? -1 : 16;
		int delta = positive ? -1 : 1;

		for (int ln = startStep; ln != endStep; ln += delta) {
			int lx = xAxis ? ln : localTangent;
			int lz = xAxis ? localTangent : ln;
			BlockState state = section.getBlockState(lx, secY, lz);
			if (!state.isAir() && !state.is(Blocks.SNOW)) {
				if (state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
					return null;
				}
				int wx = minX + lx;
				int wz = minZ + lz;
				int cheb = CubePlanetMath.getChebyshevDistance(wx, wy, wz, this.centerY);
				if (cheb < this.radiusBlocks - 28 || cheb > this.radiusBlocks + 44) {
					return null;
				}
				if (CubePlanetMath.getPyramidOutwardFace(wx, wy, wz, this.centerY) != outward) {
					return null;
				}
				BlockPos above = new BlockPos(wx + outward.getStepX(), wy, wz + outward.getStepZ());
				BlockState aboveState = level.getBlockState(above);
				if (aboveState.isAir() || aboveState.is(Blocks.WATER) || aboveState.is(Blocks.SNOW)) {
					return above;
				}
				return null;
			}
		}
		return null;
	}

	private void runVanillaSurfaceFeaturesAt(
		WorldGenLevel rawLevel,
		OrientedWorldGenLevel orientedLevel,
		BlockPos surfaceAboveWorldPos,
		Direction outward,
		RandomSource random
	) {
		BlockPos groundWorldPos = surfaceAboveWorldPos.relative(outward.getOpposite());
		BlockState groundState = rawLevel.getBlockState(groundWorldPos);
		BlockState aboveState = rawLevel.getBlockState(surfaceAboveWorldPos);

		Holder<Biome> biomeHolder = rawLevel.getBiome(groundWorldPos);
		String biomePath = biomeHolder.unwrapKey().map(k -> k.identifier().getPath()).orElse("plains");
		BiomeGenerationSettings genSettings = this.getBiomeGenerationSettings(biomeHolder);
		List<HolderSet<PlacedFeature>> steps = genSettings.features();

		BlockPos vSurfacePos = orientedLevel.toVirtual(surfaceAboveWorldPos);

		int vegStepIdx = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
		if (vegStepIdx < steps.size()) {
			HolderSet<PlacedFeature> vegFeatures = steps.get(vegStepIdx);
			boolean placedTree = false;
			boolean placedFlora = false;

			for (Holder<PlacedFeature> holder : vegFeatures) {
				String key = holder.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
				Feature feature = holder.value().feature().value();

				if (aboveState.is(Blocks.WATER)) {
					if (!placedFlora && (key.contains("seagrass") || key.contains("kelp"))) {
						if (random.nextInt(4) == 0 && feature.place(orientedLevel, this, random, vSurfacePos)) {
							placedFlora = true;
						}
					}
					continue;
				}

				boolean isTreeFeature = key.contains("tree") || key.contains("birch") || key.contains("oak")
					|| key.contains("spruce") || key.contains("pine") || key.contains("acacia")
					|| key.contains("jungle") || key.contains("cherry") || key.contains("mangrove")
					|| key.contains("dark_forest") || key.contains("bamboo") || key.contains("mushroom_island");

				if (isTreeFeature) {
					if (!placedTree && shouldAttemptTreeInBiome(key, random)) {
						if (feature.place(orientedLevel, this, random, vSurfacePos)) {
							placedTree = true;
							if (groundState.is(Blocks.GRASS_BLOCK) || groundState.is(Blocks.PODZOL) || groundState.is(Blocks.MYCELIUM)) {
								orientedLevel.setBlock(vSurfacePos.below(), DIRT, 2);
							}
						}
					}
				} else if (!placedTree && !placedFlora && (key.contains("flower") || key.contains("grass") || key.contains("fern")
					|| key.contains("bush") || key.contains("cactus") || key.contains("sugar_cane")
					|| key.contains("pumpkin") || key.contains("melon") || key.contains("patch_"))) {
					if (random.nextInt(3) == 0 && feature.place(orientedLevel, this, random, vSurfacePos)) {
						placedFlora = true;
					}
				}
			}
		}

		// Snow layer in snowy biomes
		if (aboveState.isAir()
			&& (biomePath.contains("snowy") || biomePath.contains("frozen") || biomePath.contains("grove") || biomePath.contains("ice_spikes"))
			&& orientedLevel.getBlockState(vSurfacePos).isAir()
			&& groundState.isSolidRender()) {
			orientedLevel.setBlock(vSurfacePos, Blocks.SNOW.defaultBlockState(), 2);
		}
	}

	private static boolean shouldAttemptTreeInBiome(String featureKey, RandomSource random) {
		if (featureKey.contains("dark_forest") || featureKey.contains("jungle") || featureKey.contains("bamboo")) {
			return random.nextInt(2) == 0;
		}
		if (featureKey.contains("plains") || featureKey.contains("meadow")) {
			return random.nextInt(28) == 0;
		}
		if (featureKey.contains("savanna") || featureKey.contains("snowy") || featureKey.contains("windswept")) {
			return random.nextInt(10) == 0;
		}
		return random.nextInt(4) == 0;
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
