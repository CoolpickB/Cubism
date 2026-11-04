package dev.cubeworld.mixin;

import com.google.common.base.Suppliers;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.worldgen.CubeSettings;
import dev.cubeworld.worldgen.SeamFold;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SpawnTargetPoint;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.apache.commons.lang3.mutable.MutableObject;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Outside its wedge, a face region is first filled with the real cube terrain, so that surface rules, aquifers and
 * carvers near the wedge wall see the same neighbors as in the cube. When the terrain step is done, that space is
 * cleared, because it belongs to other faces.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin implements CubeSettings.Holder {
	@Shadow
	@Final
	@Mutable
	public static MapCodec<NoiseBasedChunkGenerator> CODEC;

	@Unique
	private @Nullable CubeSettings cubeworld$settings;

	@Unique
	private static final int SEA_BARRIER_RANGE = 24;

	@Unique
	private static final int SEA_SURFACE_DEPTH = 8;

	/**
	 * The vanilla codec, with the cube settings as an optional field. The chunk generator registry takes the codec
	 * from this field after the class is initialized, so the registry gets this one.
	 */
	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void cubeworld$codecWithSettings(CallbackInfo ci) {
		CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
			NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(NoiseBasedChunkGenerator::generatorSettings),
			CubeSettings.CODEC.optionalFieldOf("cubeworld").forGetter(g -> Optional.ofNullable(((CubeSettings.Holder) (Object) g).cubeworld$settings()))
		).apply(i, i.stable((biomes, settings, cube) -> {
			NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomes, settings);
			((CubeSettings.Holder) (Object) generator).cubeworld$setSettings(cube.orElse(null));
			return generator;
		})));
	}

	@Override
	public @Nullable CubeSettings cubeworld$settings() {
		return this.cubeworld$settings;
	}

	@Override
	public void cubeworld$setSettings(@Nullable CubeSettings settings) {
		this.cubeworld$settings = settings;
	}

	@Shadow
	@Final
	@Mutable
	private Supplier<Aquifer.FluidPicker> globalFluidPicker;

	/**
	 * The vanilla sea is flat in each face frame. Next to an edge the rounded ground sinks under it, and the flat sea
	 * stops at the diagonal as a wall of water. In a seam world, the sea level of a column follows the rounded cube.
	 * The settings and the layout are known only when the first chunk generates, so the choice is made then.
	 */
	@Inject(method = "<init>", at = @At("TAIL"))
	private void cubeworld$roundedSea(CallbackInfo ci) {
		Supplier<Aquifer.FluidPicker> vanilla = this.globalFluidPicker;
		this.globalFluidPicker = Suppliers.memoize(() -> {
			Aquifer.FluidPicker picker = vanilla.get();
			NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) (Object) this;
			SeamLayout layout = SeamLayout.active();
			if (layout == null || !generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
				return picker;
			}
			int seaLevel = generator.generatorSettings().value().seaLevel();
			BlockState fluid = generator.generatorSettings().value().defaultFluid();
			return (x, y, z) -> {
				Aquifer.FluidStatus status = picker.computeFluid(x, y, z);
				return status.fluidType().is(Blocks.LAVA) ? status : new Aquifer.FluidStatus(layout.seaLevelAt(x, z, seaLevel), fluid);
			};
		});
	}

	/**
	 * Where the sea bends over a rounded edge, its level drops from column to column, but the aquifer takes the level
	 * once per aquifer cell. The sea was then flat over each cell, with cliffs of water and pockets of air between
	 * cells, and the ice of a cold sea showed them as broken shelves. It also built a stone barrier between the water
	 * of one cell and the air of the next, which stood as a ridge in the open sea. In the rounded band, the open space
	 * near the sea surface takes the sea of its own column.
	 */
	@WrapOperation(
		method = "doFill",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/Aquifer;computeSubstance(IIID)Lnet/minecraft/world/level/block/state/BlockState;")
	)
	private @Nullable BlockState cubeworld$seaInsteadOfBarrier(Aquifer aquifer, int x, int y, int z, double density, Operation<BlockState> original) {
		BlockState state = original.call(aquifer, x, y, z, density);
		if (density > 0.0 || state != null && state.is(Blocks.LAVA)) {
			return state;
		}
		SeamLayout layout = SeamLayout.active();
		NoiseBasedChunkGenerator generator = (NoiseBasedChunkGenerator) (Object) this;
		if (layout == null || !layout.inRoundedEdgeBand(x, z) || !generator.generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
			return state;
		}
		Aquifer.FluidStatus sea = this.globalFluidPicker.get().computeFluid(x, y, z);
		if (state == null) {
			return Math.abs(y - sea.fluidLevel()) <= SEA_BARRIER_RANGE ? sea.at(y) : state;
		}
		// Above the flat sea level and deeper than the surface layer, the aquifer keeps its own water and dry caves.
		return y >= sea.fluidLevel() - SEA_SURFACE_DEPTH && y < generator.generatorSettings().value().seaLevel() ? sea.at(y) : state;
	}

	/**
	 * Vanilla searches up to 2048 blocks from the origin for land, which leads into other face regions and the space
	 * between wedges. Here the search stays near the center of the spawn face, away from the mountain rims of the
	 * edges. The fitness is the vanilla one.
	 */
	@WrapOperation(
		method = "getOrigin",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseSpawnFinder;findSpawnPosition(Ljava/util/List;Lnet/minecraft/world/level/levelgen/densityfunction/DensitySamplerSet;)Lnet/minecraft/core/BlockPos;")
	)
	private BlockPos cubeworld$spawnOnTopFace(List<SpawnTargetPoint> targets, DensitySamplerSet samplers, Operation<BlockPos> original) {
		SeamLayout layout = SeamLayout.active();
		if (layout == null || !((NoiseBasedChunkGenerator) (Object) this).generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
			return original.call(targets, samplers);
		}
		int limit = layout.radius / 2;
		int offset = layout.offsetX(CubeSettings.of((ChunkGenerator) (Object) this).spawnFaceIndex());
		BlockPos best = new BlockPos(offset, 0, 0);
		long bestFitness = Long.MAX_VALUE;
		for (int x = -limit; x <= limit; x += 16) {
			for (int z = -limit; z <= limit; z += 16) {
				long fitness = Long.MAX_VALUE;
				for (SpawnTargetPoint target : targets) {
					fitness = Math.min(fitness, target.sampleFitness(samplers, offset + x, 0, z));
				}
				fitness = fitness * 2048L * 2048L + (long) x * x + (long) z * z;
				if (fitness < bestFitness) {
					bestFitness = fitness;
					best = new BlockPos(offset + x, 0, z);
				}
			}
		}
		return best;
	}

	/**
	 * Structures take their height from this column of the terrain density, before the space outside the wedge is
	 * cleared. Past an edge that space holds the next face, which stands as a wall up to the top of the cube, and a
	 * village placed on it floats in the sky of that face. The column reads air there, as the generated chunk does.
	 */
	@WrapOperation(
		method = "iterateNoiseColumn",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/Aquifer;computeSubstance(IIID)Lnet/minecraft/world/level/block/state/BlockState;")
	)
	private @Nullable BlockState cubeworld$airOutsideWedge(Aquifer aquifer, int x, int y, int z, double density, Operation<BlockState> original) {
		SeamLayout layout = SeamLayout.active();
		if (layout != null && ((NoiseBasedChunkGenerator) (Object) this).generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)
			&& !layout.insideWedge(x, y, z)) {
			return Blocks.AIR.defaultBlockState();
		}
		return original.call(aquifer, x, y, z, density);
	}

	/**
	 * A structure is laid out on the flat plane of its face, and the plane past an edge is folded onto the neighbor
	 * face (see {@link SeamFold}). The ground of that part is the ground of the neighbor face at the folded column.
	 */
	@WrapMethod(method = "iterateNoiseColumn")
	private OptionalInt cubeworld$foldedColumn(
		LevelHeightAccessor heights, RandomState randomState, int x, int z, @Nullable MutableObject<NoiseColumn> column,
		@Nullable Predicate<BlockState> tester, Operation<OptionalInt> original
	) {
		SeamLayout layout = SeamLayout.active();
		if (layout != null && ((NoiseBasedChunkGenerator) (Object) this).generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
			SeamFold.Fold fold = layout.foldOfColumn(x, z);
			if (fold != null) {
				return original.call(heights, randomState, fold.x(x, z), fold.z(x, z), column, tester);
			}
		}
		return original.call(heights, randomState, x, z, column, tester);
	}

	@ModifyReturnValue(method = "buildTerrain", at = @At("RETURN"))
	private CompletableFuture<ChunkAccess> cubeworld$clearOutsideWedge(CompletableFuture<ChunkAccess> future) {
		SeamLayout layout = SeamLayout.active();
		if (layout == null || !((NoiseBasedChunkGenerator) (Object) this).generatorSettings().is(CubeWorldMod.SEAM_SETTINGS)) {
			return future;
		}
		return future.thenApply(chunk -> {
			clearOutsideWedge(chunk, layout);
			return chunk;
		});
	}

	private static void clearOutsideWedge(ChunkAccess chunk, SeamLayout layout) {
		BlockState air = Blocks.AIR.defaultBlockState();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		boolean changed = false;
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				// The wedge widens upward, so each column is outside up to some height and inside above it.
				for (int y = chunk.getMinY(); y < chunk.getMaxY() && !layout.insideWedge(minX + x, y, minZ + z); y++) {
					LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
					if (!section.getBlockState(x, y & 15, z).isAir()) {
						section.setBlockState(x, y & 15, z, air, false);
						changed = true;
					}
				}
			}
		}
		if (changed) {
			EnumSet<Heightmap.Types> types = EnumSet.noneOf(Heightmap.Types.class);
			for (var entry : chunk.getHeightmaps()) {
				types.add(entry.getKey());
				chunk.setHeightmap(entry.getKey(), new long[entry.getValue().getRawData().length]);
			}
			Heightmap.primeHeightmaps(chunk, types);
		}
	}
}
