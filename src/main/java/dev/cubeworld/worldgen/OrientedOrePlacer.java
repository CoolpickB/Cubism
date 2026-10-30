package dev.cubeworld.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.OreFeature;

import java.util.BitSet;

/**
 * A copy of {@code OreFeature.doPlace} that reads and writes through the level, so that the face level can turn
 * the positions. The random calls are the same as in vanilla.
 */
public final class OrientedOrePlacer {
	private OrientedOrePlacer() {}

	public static boolean doPlace(
		OreFeature feature, WorldGenLevel level, RandomSource random,
		double x0, double x1, double z0, double z1, double y0, double y1,
		int xStart, int yStart, int zStart, int sizeXZ, int sizeY
	) {
		int size = feature.size();
		int placed = 0;
		BitSet tested = new BitSet(sizeXZ * sizeY * sizeXZ);
		BlockPos.MutableBlockPos orePos = new BlockPos.MutableBlockPos();
		double[] data = new double[size * 4];

		for (int i = 0; i < size; i++) {
			float step = (float) i / size;
			double ss = random.nextDouble() * size / 16.0;
			double r = ((Mth.sin((float) Math.PI * step) + 1.0F) * ss + 1.0) / 2.0;
			data[i * 4] = Mth.lerp(step, x0, x1);
			data[i * 4 + 1] = Mth.lerp(step, y0, y1);
			data[i * 4 + 2] = Mth.lerp(step, z0, z1);
			data[i * 4 + 3] = r;
		}

		for (int i1 = 0; i1 < size - 1; i1++) {
			if (data[i1 * 4 + 3] <= 0.0) {
				continue;
			}
			for (int i2 = i1 + 1; i2 < size; i2++) {
				if (data[i2 * 4 + 3] <= 0.0) {
					continue;
				}
				double dx = data[i1 * 4] - data[i2 * 4];
				double dy = data[i1 * 4 + 1] - data[i2 * 4 + 1];
				double dz = data[i1 * 4 + 2] - data[i2 * 4 + 2];
				double dr = data[i1 * 4 + 3] - data[i2 * 4 + 3];
				if (dr * dr > dx * dx + dy * dy + dz * dz) {
					if (dr > 0.0) {
						data[i2 * 4 + 3] = -1.0;
					} else {
						data[i1 * 4 + 3] = -1.0;
					}
				}
			}
		}

		for (int i = 0; i < size; i++) {
			double r = data[i * 4 + 3];
			if (r < 0.0) {
				continue;
			}
			double xx = data[i * 4];
			double yy = data[i * 4 + 1];
			double zz = data[i * 4 + 2];
			int xMin = Math.max(Mth.floor(xx - r), xStart);
			int yMin = Math.max(Mth.floor(yy - r), yStart);
			int zMin = Math.max(Mth.floor(zz - r), zStart);
			int xMax = Math.max(Mth.floor(xx + r), xMin);
			int yMax = Math.max(Mth.floor(yy + r), yMin);
			int zMax = Math.max(Mth.floor(zz + r), zMin);

			for (int x = xMin; x <= xMax; x++) {
				double xd = (x + 0.5 - xx) / r;
				if (xd * xd >= 1.0) {
					continue;
				}
				for (int y = yMin; y <= yMax; y++) {
					double yd = (y + 0.5 - yy) / r;
					if (xd * xd + yd * yd >= 1.0) {
						continue;
					}
					for (int z = zMin; z <= zMax; z++) {
						double zd = (z + 0.5 - zz) / r;
						if (xd * xd + yd * yd + zd * zd >= 1.0 || level.isOutsideBuildHeight(y)) {
							continue;
						}
						int bitSetIndex = x - xStart + (y - yStart) * sizeXZ + (z - zStart) * sizeXZ * sizeY;
						if (tested.get(bitSetIndex)) {
							continue;
						}
						tested.set(bitSetIndex);
						orePos.set(x, y, z);
						if (!level.ensureCanWrite(orePos)) {
							continue;
						}
						BlockState blockState = level.getBlockState(orePos);
						for (BlockReplacement targetState : feature.targetStates()) {
							if (feature.canPlaceOre(blockState, level::getBlockState, random, targetState, orePos)) {
								level.setBlock(orePos, targetState.state(), 2);
								placed++;
								break;
							}
						}
					}
				}
			}
		}

		return placed > 0;
	}
}
