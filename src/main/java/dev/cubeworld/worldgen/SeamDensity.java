package dev.cubeworld.worldgen;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.ScopedDensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.generator.DistanceToPointFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.EndIslandFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.GradientFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.ShiftNoiseFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Makes a density function work in storage coordinates. The cube rewrite of {@link CubeDensityFunctions} runs
 * first. Then every node that reads coordinates (noises and gradients) gets the storage-to-cube remap of
 * {@link SeamLayout}. All other nodes stay in storage coordinates, so interpolation cells, the top-surface search
 * and structure terrain adaptation work in the upright face frame, the same as in a vanilla world.
 */
public final class SeamDensity {
	private final SeamLayout layout;
	private final DfRewriteRule cubeRule;
	private final Map<DensityFunction, DensityFunction> memo = new IdentityHashMap<>();
	private final DfRewriteRule remapRule = new DfRewriteRule() {
		@Override
		public DensityFunction rewrite(DensityFunction fn) {
			DensityFunction cached = SeamDensity.this.memo.get(fn);
			if (cached != null) {
				return cached;
			}
			DensityFunction result = readsCoordinates(fn) ? new ToCube(fn, SeamDensity.this.layout) : fn.rewriteChildren(this);
			SeamDensity.this.memo.put(fn, result);
			return result;
		}
	};

	public SeamDensity(SeamLayout layout) {
		this.layout = layout;
		this.cubeRule = CubeDensityFunctions.cubeRule(layout.centerY, layout.radius, layout.innerBedrockRadius);
	}

	public synchronized DensityFunction rewrite(DensityFunction fn) {
		return this.remapRule.rewrite(this.cubeRule.rewrite(fn));
	}

	/** Nodes that sample their position directly. Their children get the same, already remapped, position. */
	private static boolean readsCoordinates(DensityFunction fn) {
		return fn instanceof NoiseFunction
			|| fn instanceof ShiftNoiseFunction
			|| fn instanceof GradientFunction
			|| fn instanceof EndIslandFunction
			|| fn instanceof DistanceToPointFunction
			|| fn instanceof BlendedNoise
			|| fn instanceof CubeDensityFunctions.CubeSurfaceNoiseFunction
			|| fn instanceof CubeDensityFunctions.CubeShiftNoiseFunction
			|| fn instanceof CubeDensityFunctions.CubeRadialGradientFunction;
	}

	record ToCube(DensityFunction input, SeamLayout layout) implements DensityFunction {
		@Override
		public DensitySampler compileSampler(CompileContext context) {
			return new Sampler(this.input.compileSampler(context), this.layout);
		}

		@Override
		public DensityFunction rewriteChildren(DfRewriteRule rule) {
			DensityFunction input = rule.rewrite(this.input);
			return input == this.input ? this : new ToCube(input, this.layout);
		}

		@Override
		public Interval range() {
			return this.input.range();
		}

		@Override
		public int domainAxes() {
			return DensityFunction.ALL_AXES;
		}

		@Override
		public MapCodec<? extends DensityFunction> codec() {
			return this.input.codec();
		}
	}

	private record Sampler(DensitySampler input, SeamLayout layout) implements DensitySampler {
		@Override
		public float sampleValue(SamplerContext context, int x, int y, int z) {
			int[] cube = new int[3];
			this.layout.toCube(this.layout.faceIndexAt(x), x, y, z, cube);
			return this.input.sampleValue(context, cube[0], cube[1], cube[2]);
		}

		/**
		 * The face rotation only permutes and negates axes, so a storage volume is an axis-aligned cube volume.
		 * Sample that volume in one call to keep the fast volume paths of the input, then reorder the result.
		 */
		@Override
		public void sampleVolume(SamplerContext context, DensityBuffer output, DensityVolume volume) {
			int face = this.layout.faceIndexAt(volume.minBlockX());
			if (face != this.layout.faceIndexAt(volume.maxBlockX())) {
				DensitySampler.sampleVolumeNaive(context, output, volume, this);
				return;
			}

			int[] m = this.layout.rotation(face);
			int[] size = {volume.sizeX(), volume.sizeY(), volume.sizeZ()};
			int[] step = {volume.stepBlockX(), volume.stepBlockY(), volume.stepBlockZ()};
			int[] cubeMin = new int[3];
			this.layout.toCube(face, volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(), cubeMin);
			// For storage axis a: the cube axis it maps to, and the sign of that mapping.
			int[] axisOf = new int[3];
			int[] signOf = new int[3];
			int[] cubeSize = new int[3];
			int[] cubeStep = new int[3];
			for (int a = 0; a < 3; a++) {
				for (int b = 0; b < 3; b++) {
					if (m[b * 3 + a] != 0) {
						axisOf[a] = b;
						signOf[a] = m[b * 3 + a];
					}
				}
				int b = axisOf[a];
				cubeSize[b] = size[a];
				cubeStep[b] = step[a];
				if (signOf[a] < 0) {
					cubeMin[b] -= (size[a] - 1) * step[a];
				}
			}

			DensityVolume cubeVolume = new DensityVolume(
				cubeSize[0], cubeSize[1], cubeSize[2], cubeMin[0], cubeMin[1], cubeMin[2], cubeStep[0], cubeStep[1], cubeStep[2]
			);
			try (ScopedDensityBuffer cubeBuffer = context.acquireBuffer(cubeVolume)) {
				this.input.sampleVolume(context, cubeBuffer, cubeVolume);
				int[] index = new int[3];
				int[] cubeIndex = new int[3];
				for (index[2] = 0; index[2] < size[2]; index[2]++) {
					for (index[0] = 0; index[0] < size[0]; index[0]++) {
						for (index[1] = 0; index[1] < size[1]; index[1]++) {
							for (int a = 0; a < 3; a++) {
								cubeIndex[axisOf[a]] = signOf[a] > 0 ? index[a] : size[a] - 1 - index[a];
							}
							output.set(
								volume.indexUnchecked(index[0], index[1], index[2]),
								cubeBuffer.get(cubeVolume.indexUnchecked(cubeIndex[0], cubeIndex[1], cubeIndex[2]))
							);
						}
					}
				}
			}
		}
	}
}
