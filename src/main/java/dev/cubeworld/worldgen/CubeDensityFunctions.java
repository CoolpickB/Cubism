package dev.cubeworld.worldgen;

import com.mojang.serialization.MapCodec;
import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Interval;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
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

	public static NoiseRouter rewriteRouter(NoiseRouter router, int centerY, int radiusBlocks, int innerBedrockRadius) {
		Map<DensityFunction, DensityFunction> memo = new IdentityHashMap<>();
		DfRewriteRule rule = new DfRewriteRule() {
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
				DensityFunction transformed = transformNode(rewrittenChildren, autoShiftYNoise, centerY, radiusBlocks, innerBedrockRadius);
				memo.put(fn, transformed);
				return transformed;
			}
		};

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
		int innerBedrockRadius
	) {
		if (fn instanceof NoiseFunction nf && nf.yScale() == 0.0) {
			DensityFunction shiftY = autoShiftYNoise != null
				? new CubeShiftNoiseFunction(autoShiftYNoise, ShiftAxis.Y, centerY, radiusBlocks, nf.shiftX().range())
				: nf.shiftY();

			return new CubeSurfaceNoiseFunction(
				nf.noise(),
				nf.xzScale(),
				nf.shiftX(),
				shiftY,
				nf.shiftZ(),
				centerY,
				radiusBlocks,
				nf.range()
			);
		}

		if (fn instanceof ShiftNoiseFunction snf) {
			ShiftAxis axis = (snf instanceof ShiftNoiseFunction.ShiftB) ? ShiftAxis.Z : ShiftAxis.X;
			return new CubeShiftNoiseFunction(snf.offsetNoise(), axis, centerY, radiusBlocks, snf.range());
		}

		if (fn instanceof GradientFunction gf && gf.axis() == Direction.Axis.Y) {
			return new CubeRadialGradientFunction(
				gf.fromCoordinate(),
				gf.toCoordinate(),
				gf.fromValue(),
				gf.toValue(),
				centerY,
				radiusBlocks,
				innerBedrockRadius,
				gf.range()
			);
		}

		return fn;
	}

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

			return new DensitySampler() {
				@Override
				public void sampleVolume(SamplerContext ctx, DensityBuffer buffer, DensityVolume volume) {
					DensitySampler.sampleVolumeNaive(ctx, buffer, volume, this);
				}

				@Override
				public float sampleValue(SamplerContext ctx, int x, int y, int z) {
					double dx = x;
					double dy = y - cy;
					double dz = z;
					double cheb = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
					double surfX;
					double surfY;
					double surfZ;
					if (cheb < 1.0) {
						surfX = 0.0;
						surfY = r;
						surfZ = 0.0;
					} else {
						double proj = r / cheb;
						surfX = dx * proj;
						surfY = dy * proj;
						surfZ = dz * proj;
					}

					double nx = surfX * s + sxSampler.sampleValue(ctx, x, y, z);
					double ny = surfY * s + sySampler.sampleValue(ctx, x, y, z);
					double nz = surfZ * s + szSampler.sampleValue(ctx, x, y, z);
					return compiledNoise.get(nx, ny, nz);
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
			return new CubeSurfaceNoiseFunction(this.noise, this.scale, nx, ny, nz, this.centerY, this.radiusBlocks, this.range);
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

	public record CubeShiftNoiseFunction(
		Holder<NormalNoise> offsetNoise,
		ShiftAxis axis,
		int centerY,
		int radiusBlocks,
		Interval range
	) implements DensityFunction {
		@Override
		public DensitySampler compileSampler(CompileContext context) {
			Noise compiledNoise = context.createNoiseSampler(this.offsetNoise);
			ShiftAxis ax = this.axis;
			int cy = this.centerY;
			double r = this.radiusBlocks;

			return new DensitySampler() {
				@Override
				public void sampleVolume(SamplerContext ctx, DensityBuffer buffer, DensityVolume volume) {
					DensitySampler.sampleVolumeNaive(ctx, buffer, volume, this);
				}

				@Override
				public float sampleValue(SamplerContext ctx, int x, int y, int z) {
					double dx = x;
					double dy = y - cy;
					double dz = z;
					double cheb = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
					double sx;
					double sy;
					double sz;
					if (cheb < 1.0) {
						sx = 0.0;
						sy = r * 0.25;
						sz = 0.0;
					} else {
						double proj = (r * 0.25) / cheb;
						sx = dx * proj;
						sy = dy * proj;
						sz = dz * proj;
					}

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
		int innerBedrockRadius,
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
			float invCrust = 128.0f / (float) Math.max(1, r - innerR);

			return new DensitySampler() {
				@Override
				public void sampleVolume(SamplerContext ctx, DensityBuffer buffer, DensityVolume volume) {
					DensitySampler.sampleVolumeNaive(ctx, buffer, volume, this);
				}

				@Override
				public float sampleValue(SamplerContext ctx, int x, int y, int z) {
					int cheb = CubePlanetMath.getChebyshevDistance(x, y, z, cy);
					float vanillaY;
					if (cheb >= r) {
						vanillaY = 64.0f + (cheb - r);
					} else if (cheb <= innerR) {
						vanillaY = -64.0f;
					} else {
						vanillaY = 64.0f - (r - cheb) * invCrust;
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
