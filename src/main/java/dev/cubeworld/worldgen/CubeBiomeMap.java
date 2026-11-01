package dev.cubeworld.worldgen;

import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The biomes of a cube world for one seed, without generating any terrain, for the preview of the Customize screen. The
 * climate comes from the same noise router as the generator, rewritten for the cube, so the map matches the world.
 *
 * <p>Each face is a grid of cells, as seen from outside the cube: cell (s, t) is at {@code a * u + b * v + n * r} from
 * the center, with u growing with s and v with t, and a, b from {@link CubePregen#viewAxes}. The surface biome of a
 * cell is chosen with a depth of 0, which is the depth at the terrain surface. Cave biomes come from two layers under
 * the surface on a coarser grid.
 */
public final class CubeBiomeMap {
	/** Cave layers, as heights of a vanilla world. The cube maps its crust onto the vanilla heights. */
	private static final int[] CAVE_LAYERS_VANILLA_Y = {0, -40};
	private static final int CAVE_STEP = 4;

	public final int cells;
	/** Surface biome of each face and cell, at index {@code s * cells + t}. */
	public final Holder<Biome>[][] surface;
	/** Every biome on the surface or in the cave layers. */
	public final Set<Holder<Biome>> present;

	private CubeBiomeMap(int cells, Holder<Biome>[][] surface, Set<Holder<Biome>> present) {
		this.cells = cells;
		this.surface = surface;
		this.present = present;
	}

	/** The two axes of a face as the map lays it out. */
	public static Vec3i[] axes(int face) {
		Vec3i[] view = CubePregen.viewAxes(SeamLayout.FACES[face]);
		return new Vec3i[] {view[0], view[1]};
	}

	/** The climate of one seed. Making it builds every noise of the seed, so a search makes one for each seed it tries. */
	public static final class Sampler {
		private final MultiNoiseBiomeSource biomes;
		private final RandomState randomState;
		private final long seed;
		private final Climate.Sampler climate;
		private final int radius;
		private final int centerY;
		private final int innerBedrockRadius;
		private final int edgeRounding;

		public Sampler(
			HolderGetter<NormalNoise> noises, NoiseGeneratorSettings settings, MultiNoiseBiomeSource biomes, CubeSettings cube, long seed
		) {
			this.biomes = biomes;
			this.seed = seed;
			this.radius = CubePlanetMath.getRadiusBlocks(cube.diameterChunks());
			this.centerY = CubePlanetMath.getCenterY(cube.diameterChunks());
			this.innerBedrockRadius = CubePlanetMath.getInnerBedrockRadius(cube.diameterChunks());
			this.edgeRounding = cube.edgeRounding();
			RandomState state = RandomState.create(
				noises, seed, settings.useLegacyRandomSource(), settings.defaultBlock(), settings.seaLevel(),
				CubeDensityFunctions.rewriteRouter(
					CubeDensityFunctions.withClimateShift(settings.noiseRouter(), cube.temperature(), cube.humidity()),
					this.centerY, this.radius, this.innerBedrockRadius
				)
			);
			this.randomState = state;
			this.climate = state.createClimateSampler(SamplerContext.EMPTY_UNCACHED);
		}

		public RandomState randomState() {
			return this.randomState;
		}

		public long seed() {
			return this.seed;
		}

		/** The surface biome at a block position of the level, in cube coordinates with the cube centered on its center height. */
		public Holder<Biome> surfaceBiomeAt(int x, int y, int z) {
			return this.surfaceBiome(new int[] {x, y, z});
		}

		/**
		 * Samples the cube with {@code cells} cells along each face edge. Returns null when {@code stop} becomes true.
		 * With {@code wanted}, sampling ends after the face where the last wanted biome turns up: a search needs no more
		 * than that, and the map it gets has no surface after that face.
		 */
		@SuppressWarnings("unchecked")
		public @Nullable CubeBiomeMap sample(int cells, @Nullable Set<Holder<Biome>> wanted, BooleanSupplier stop) {
			// The cube surface functions read the edge rounding from here. No server runs while the Customize screen is
			// open, and the server sets its own value when its level loads.
			CubePlanetMath.setEdgeRounding(this.edgeRounding);
			Holder<Biome>[][] surface = new Holder[SeamLayout.FACES.length][cells * cells];
			Set<Holder<Biome>> present = new HashSet<>();
			Set<Holder<Biome>> missing = wanted == null ? null : new HashSet<>(wanted);
			double step = 2.0 * this.radius / cells;
			float crustScale = (this.radius - this.innerBedrockRadius) / 128.0F;
			for (int face = 0; face < SeamLayout.FACES.length; face++) {
				if (stop.getAsBoolean()) {
					return null;
				}
				Vec3i n = SeamLayout.FACES[face].getUnitVec3i();
				Vec3i[] axes = axes(face);
				for (int s = 0; s < cells; s++) {
					double u = -this.radius + (s + 0.5) * step;
					for (int t = 0; t < cells; t++) {
						double v = -this.radius + (t + 0.5) * step;
						Holder<Biome> biome = this.surfaceBiome(point(axes, n, u, v, this.radius));
						surface[face][s * cells + t] = biome;
						present.add(biome);
						if (s % CAVE_STEP == 0 && t % CAVE_STEP == 0) {
							for (int vanillaY : CAVE_LAYERS_VANILLA_Y) {
								int[] p = point(axes, n, u, v, this.radius - (64 - vanillaY) * crustScale);
								present.add(this.biomes.getNoiseBiome(this.climate.sample(p[0] >> 2, p[1] >> 2, p[2] >> 2)));
							}
						}
					}
				}
				if (missing != null && missing.removeIf(present::contains) && missing.isEmpty()) {
					break;
				}
			}
			return new CubeBiomeMap(cells, surface, present);
		}

		private Holder<Biome> surfaceBiome(int[] p) {
			Climate.Sampler c = this.climate;
			return this.biomes.getNoiseBiome(Climate.target(
				c.temperature().sampleValue(p[0], p[1], p[2]),
				c.humidity().sampleValue(p[0], p[1], p[2]),
				c.continentalness().sampleValue(p[0], p[1], p[2]),
				c.erosion().sampleValue(p[0], p[1], p[2]),
				0.0F,
				c.weirdness().sampleValue(p[0], p[1], p[2])
			));
		}

		/** A block position of the level at {@code a * u + b * v + n * height} from the cube center. */
		private int[] point(Vec3i[] axes, Vec3i n, double u, double v, double height) {
			Vec3i a = axes[0];
			Vec3i b = axes[1];
			return new int[] {
				(int) Math.floor(a.getX() * u + b.getX() * v + n.getX() * height),
				(int) Math.floor(a.getY() * u + b.getY() * v + n.getY() * height) + this.centerY,
				(int) Math.floor(a.getZ() * u + b.getZ() * v + n.getZ() * height)
			};
		}
	}
}
