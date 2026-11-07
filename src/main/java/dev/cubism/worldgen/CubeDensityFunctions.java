package dev.cubism.worldgen;

import com.mojang.serialization.MapCodec;
import dev.cubism.math.CubePlanetMath;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Interval;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.generator.GradientFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.ShiftNoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.SliceFunction;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.util.IdentityHashMap;
import java.util.Map;

public final class CubeDensityFunctions {
	private CubeDensityFunctions() {}

	/**
	 * The router with a shift added to the temperature and to the humidity, which the router calls vegetation. The shifts
	 * are in hundredths of the climate range. The biome search takes the nearest biome, so a climate beyond the range
	 * gives the most extreme biomes.
	 */
	public static NoiseRouter withClimateShift(NoiseRouter router, int temperature, int humidity) {
		if (temperature == 0 && humidity == 0) {
			return router;
		}
		return new NoiseRouter(
			shifted(router.temperature(), temperature),
			shifted(router.vegetation(), humidity),
			router.continents(),
			router.erosion(),
			router.depth(),
			router.ridges(),
			router.chunkSurfaceLevel(),
			router.finalDensity()
		);
	}

	private static DensityFunction shifted(DensityFunction function, int hundredths) {
		return hundredths == 0 ? function : DensityFunctions.add(function, DensityFunctions.constant(hundredths / 100.0F));
	}

	public static NoiseRouter rewriteRouter(NoiseRouter router, int centerY, int radiusBlocks, int surfaceY, int innerBedrockRadius, int rounding) {
		DfRewriteRule rule = cubeRule(centerY, radiusBlocks, radiusBlocks, surfaceY, innerBedrockRadius, rounding, false);
		return new NoiseRouter(
			rule.rewrite(router.temperature()),
			rule.rewrite(router.vegetation()),
			rule.rewrite(router.continents()),
			rule.rewrite(router.erosion()),
			rule.rewrite(router.depth()),
			rule.rewrite(router.ridges()),
			rule.rewrite(router.chunkSurfaceLevel()),
			rule.rewrite(router.finalDensity())
		);
	}

	/**
	 * Rewrites a density function to work on the cube in world coordinates. The rule memoizes and is not thread-safe.
	 * 2D noise, such as the climate, is read on the surface of the cube at {@code surfaceNoiseRadius}. With
	 * {@code squareFloor}, gradients that end below {@link #SQUARE_FLOOR_TOP} measure height from the unrounded cube.
	 */
	public static DfRewriteRule cubeRule(
		int centerY, int radiusBlocks, int surfaceNoiseRadius, int surfaceY, int innerBedrockRadius, int rounding, boolean squareFloor
	) {
		Map<DensityFunction, DensityFunction> memo = new IdentityHashMap<>();
		return new DfRewriteRule() {
			@Override
			public DensityFunction rewrite(DensityFunction fn) {
				DensityFunction cached = memo.get(fn);
				if (cached != null) {
					return cached;
				}

				DensityFunction unwrapped = fn;
				DensityFunction next = DfRewriteRule.INLINE_REFERENCE.rewrite(unwrapped);
				while (next != unwrapped) {
					unwrapped = next;
					next = DfRewriteRule.INLINE_REFERENCE.rewrite(unwrapped);
				}

				// Strip 2D/1D SliceFunction wrappers so Y is never zeroed before reaching 3D cube samplers
				if (unwrapped instanceof SliceFunction) {
					DensityFunction[] childRef = new DensityFunction[1];
					unwrapped.rewriteChildren(child -> {
						childRef[0] = child;
						return child;
					});
					if (childRef[0] != null) {
						DensityFunction res = this.rewrite(childRef[0]);
						memo.put(fn, res);
						return res;
					}
				}

				Holder<NormalNoise> autoShiftYNoise = null;
				if (unwrapped instanceof NoiseFunction nf && nf.yScale() == 0.0) {
					DensityFunction rawShiftX = unwrapReferences(nf.shiftX());
					DensityFunction rawShiftY = unwrapReferences(nf.shiftY());
					if (rawShiftX instanceof ShiftNoiseFunction snf && rawShiftY.domainAxes() == DensityFunction.NO_AXES) {
						autoShiftYNoise = snf.offsetNoise();
					}
				}

				DensityFunction rewrittenChildren = unwrapped.rewriteChildren(this);
				DensityFunction transformed = transformNode(
					rewrittenChildren, autoShiftYNoise, centerY, radiusBlocks, surfaceNoiseRadius, surfaceY, innerBedrockRadius, rounding, squareFloor
				);
				memo.put(fn, transformed);
				return transformed;
			}
		};
	}

	private static DensityFunction unwrapReferences(DensityFunction fn) {
		DensityFunction cur = fn;
		DensityFunction next = DfRewriteRule.INLINE_REFERENCE.rewrite(cur);
		while (next != cur) {
			cur = next;
			next = DfRewriteRule.INLINE_REFERENCE.rewrite(cur);
		}
		return cur;
	}

	private static DensityFunction transformNode(
		DensityFunction fn,
		Holder<NormalNoise> autoShiftYNoise,
		int centerY,
		int radiusBlocks,
		int surfaceNoiseRadius,
		int surfaceY,
		int innerBedrockRadius,
		int rounding,
		boolean squareFloor
	) {
		if (fn instanceof NoiseFunction nf && nf.yScale() == 0.0) {
			DensityFunction shiftY = autoShiftYNoise != null
				? new CubeShiftNoiseFunction(autoShiftYNoise, ShiftAxis.Y, centerY, surfaceNoiseRadius, rounding, nf.shiftX().range())
				: nf.shiftY();

			return new CubeSurfaceNoiseFunction(
				nf.noise(),
				nf.xzScale(),
				nf.shiftX(),
				shiftY,
				nf.shiftZ(),
				centerY,
				surfaceNoiseRadius,
				rounding,
				nf.range()
			);
		}

		if (fn instanceof ShiftNoiseFunction snf) {
			ShiftAxis axis = (snf instanceof ShiftNoiseFunction.ShiftB) ? ShiftAxis.Z : ShiftAxis.X;
			return new CubeShiftNoiseFunction(snf.offsetNoise(), axis, centerY, surfaceNoiseRadius, rounding, snf.range());
		}

		if (fn instanceof GradientFunction gf && gf.axis() == Direction.Axis.Y) {
			return new CubeRadialGradientFunction(
				gf.fromCoordinate(),
				gf.toCoordinate(),
				gf.fromValue(),
				gf.toValue(),
				centerY,
				radiusBlocks,
				surfaceY,
				innerBedrockRadius,
				rounding,
				squareFloor && Math.max(gf.fromCoordinate(), gf.toCoordinate()) <= SQUARE_FLOOR_TOP,
				gf.range()
			);
		}

		return fn;
	}

	/** The height above the square cube along the axis of the face, which is the height in storage. */
	private static double squareCubeDistance(int x, int y, int z, int r) {
		return Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) - r;
	}

	/**
	 * The highest vanilla Y of the floor gradient of the Nether. The rounded cube lies outside the square one, so on a
	 * rounded floor the ground near an edge sinks under storage Y 0, and lava floods the bedrock there. A square floor
	 * keeps the ground at the bedrock, and the roof still bends over each edge.
	 */
	private static final int SQUARE_FLOOR_TOP = 64;

	public enum ShiftAxis {
		X, Y, Z
	}

	public record CubeSurfaceNoiseFunction(
		Holder<NormalNoise> noise,
		double scale,
		DensityFunction shiftX,
		DensityFunction shiftY,
		DensityFunction shiftZ,
		int centerY,
		int radiusBlocks,
		int rounding,
		Interval range
	) implements DensityFunction {
		@Override
		public DensitySampler compileSampler(CompileContext context) {
			Noise compiledNoise = context.createNoiseSampler(this.noise);
			DensitySampler sxSampler = this.shiftX.compileSampler(context);
			DensitySampler sySampler = this.shiftY.compileSampler(context);
			DensitySampler szSampler = this.shiftZ.compileSampler(context);
			double s = this.scale;
			int cy = this.centerY;
			double r = this.radiusBlocks;
			int rounding = this.rounding;
			boolean continentalness = this.noise.is(Noises.CONTINENTALNESS) || this.noise.is(Noises.CONTINENTALNESS_LARGE);

			return new DensitySampler() {
				@Override
				public void sampleVolume(SamplerContext ctx, DensityBuffer buffer, DensityVolume volume) {
					DensitySampler.sampleVolumeNaive(ctx, buffer, volume, this);
				}

				@Override
				public float sampleValue(SamplerContext ctx, int x, int y, int z) {
					double[] surface = new double[3];
					CubePlanetMath.roundedCubeSurfacePoint(x, y - cy, z, r, rounding, surface);
					double nx = surface[0] * s + sxSampler.sampleValue(ctx, x, y, z);
					double ny = surface[1] * s + sySampler.sampleValue(ctx, x, y, z);
					double nz = surface[2] * s + szSampler.sampleValue(ctx, x, y, z);
					float value = (float) compiledNoise.get(nx, ny, nz);
					return continentalness ? value + edgeLandBias(surface, r, rounding) : value;
				}
			};
		}

		@Override
		public DensityFunction rewriteChildren(DfRewriteRule rule) {
			DensityFunction nx = rule.rewrite(this.shiftX);
			DensityFunction ny = rule.rewrite(this.shiftY);
			DensityFunction nz = rule.rewrite(this.shiftZ);
			if (nx == this.shiftX && ny == this.shiftY && nz == this.shiftZ) {
				return this;
			}
			return new CubeSurfaceNoiseFunction(this.noise, this.scale, nx, ny, nz, this.centerY, this.radiusBlocks, this.rounding, this.range);
		}

		@Override
		public int domainAxes() {
			return DensityFunction.ALL_AXES;
		}

		@Override
		public MapCodec<? extends DensityFunction> codec() {
			return NoiseFunction.CODEC;
		}
	}

	/**
	 * How far continentalness rises at an edge. Inland starts near 0.03 and oceans are under -0.19. Off for now, so
	 * that the seed alone decides the terrain. A setting for edge gravity can bring it back.
	 */
	private static final float EDGE_LAND_BIAS = 0.0f;
	/** How far the edge bias reaches into a face beyond the rounded shoulder, in blocks. */
	private static final int EDGE_LAND_WIDTH = 48;

	/**
	 * Raises continentalness near the cube edges. The ground of the rounded shoulder sinks below the face plane, and
	 * a sea there coats the slope as a thin sheet that bends over the edge. With the bias, shoulders are land, and
	 * the sea gathers toward the face centers, a mild version of the bowl that real cube gravity would make.
	 */
	private static float edgeLandBias(double[] surface, double r, int rounding) {
		double ax = Math.abs(surface[0]);
		double ay = Math.abs(surface[1]);
		double az = Math.abs(surface[2]);
		// The distance to the nearest edge, along the face: the largest coordinate is the face axis, the next is the edge.
		double second = Math.max(Math.min(ax, ay), Math.min(Math.max(ax, ay), az));
		double width = rounding + EDGE_LAND_WIDTH;
		double t = Mth.clamp(1.0 - (r - second) / width, 0.0, 1.0);
		return (float) (EDGE_LAND_BIAS * t * t * (3.0 - 2.0 * t));
	}

	public record CubeShiftNoiseFunction(
		Holder<NormalNoise> offsetNoise,
		ShiftAxis axis,
		int centerY,
		int radiusBlocks,
		int rounding,
		Interval range
	) implements DensityFunction {
		@Override
		public DensitySampler compileSampler(CompileContext context) {
			Noise compiledNoise = context.createNoiseSampler(this.offsetNoise);
			ShiftAxis ax = this.axis;
			int cy = this.centerY;
			double r = this.radiusBlocks;
			int rounding = this.rounding;

			return new DensitySampler() {
				@Override
				public void sampleVolume(SamplerContext ctx, DensityBuffer buffer, DensityVolume volume) {
					DensitySampler.sampleVolumeNaive(ctx, buffer, volume, this);
				}

				@Override
				public float sampleValue(SamplerContext ctx, int x, int y, int z) {
					double[] surface = new double[3];
					CubePlanetMath.roundedCubeSurfacePoint(x, y - cy, z, r, rounding, surface);
					double sx = surface[0] * 0.25;
					double sy = surface[1] * 0.25;
					double sz = surface[2] * 0.25;

					float raw = switch (ax) {
						case X -> compiledNoise.get(sx, sy, sz);
						case Y -> compiledNoise.get(sy, sz, sx);
						case Z -> compiledNoise.get(sz, sx, sy);
					};
					return raw * 4.0f;
				}
			};
		}

		@Override
		public DensityFunction rewriteChildren(DfRewriteRule rule) {
			return this;
		}

		@Override
		public int domainAxes() {
			return DensityFunction.ALL_AXES;
		}

		@Override
		public MapCodec<? extends DensityFunction> codec() {
			return ShiftNoiseFunction.ShiftA.CODEC;
		}
	}

	public record CubeRadialGradientFunction(
		int fromCoordinate,
		int toCoordinate,
		float fromValue,
		float toValue,
		int centerY,
		int radiusBlocks,
		int surfaceY,
		int innerBedrockRadius,
		int rounding,
		boolean squareFloor,
		Interval range
	) implements DensityFunction {
		@Override
		public DensitySampler compileSampler(CompileContext context) {
			int fromC = this.fromCoordinate;
			float invSpan = 1.0f / (float) (this.toCoordinate - this.fromCoordinate);
			float fromV = this.fromValue;
			float deltaV = this.toValue - this.fromValue;
			int cy = this.centerY;
			int r = this.radiusBlocks;
			int innerR = this.innerBedrockRadius;
			int rounding = this.rounding;
			boolean squareFloor = this.squareFloor;
			float surface = this.surfaceY;
			float invCrust = 128.0f / (float) Math.max(1, r - innerR);

			return new DensitySampler() {
				@Override
				public void sampleVolume(SamplerContext ctx, DensityBuffer buffer, DensityVolume volume) {
					DensitySampler.sampleVolumeNaive(ctx, buffer, volume, this);
				}

				@Override
				public float sampleValue(SamplerContext ctx, int x, int y, int z) {
					float height = (float) (squareFloor ? squareCubeDistance(x, y - cy, z, r) : CubePlanetMath.roundedCubeDistance(x, y - cy, z, r, rounding));
					float vanillaY;
					// The crust down to the inner bedrock takes the 128 vanilla heights below the surface.
					if (height >= 0.0f) {
						vanillaY = surface + height;
					} else if (height <= innerR - r) {
						vanillaY = surface - 128.0f;
					} else {
						vanillaY = surface + height * invCrust;
					}

					float t = Mth.clamp((vanillaY - fromC) * invSpan, 0.0f, 1.0f);
					return fromV + t * deltaV;
				}
			};
		}

		@Override
		public DensityFunction rewriteChildren(DfRewriteRule rule) {
			return this;
		}

		@Override
		public int domainAxes() {
			return DensityFunction.ALL_AXES;
		}

		@Override
		public MapCodec<? extends DensityFunction> codec() {
			return GradientFunction.CODEC;
		}
	}
}
