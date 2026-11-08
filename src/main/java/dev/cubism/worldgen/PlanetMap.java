package dev.cubism.worldgen;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.jspecify.annotations.Nullable;

/**
 * A coarse picture of the surface of the cube for the view from space: for each face a grid of {@link #CELLS} cells,
 * with the height of the ground over the face and its color at each node. The terrain is read from the generator in
 * the storage frame of each face, so it matches the world, and it is not generated into chunks.
 *
 * <p>Data layout: for each face, for each node (u outer, v inner) a short with the height over the top of the face,
 * then three bytes of color. Node (i, j) is at {@code -radius + 2 * radius * i / CELLS} along each axis of the face.
 */
public final class PlanetMap {
	public static final int CELLS = 64;
	private static final int SNOW_ABOVE = 110;
	private static final int STONE_ABOVE = 80;
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Cubism planet map");
		thread.setDaemon(true);
		return thread;
	});
	private static @Nullable CompletableFuture<byte[]> pending;

	private PlanetMap() {}

	/** The map of the overworld of a server, made once on a worker thread. */
	public static synchronized CompletableFuture<byte[]> of(ServerLevel level) {
		if (pending == null) {
			SeamLayout layout = SeamLayout.of(level);
			pending = CompletableFuture.supplyAsync(() -> build(level, layout), WORKER);
		}
		return pending;
	}

	public static synchronized void clear() {
		pending = null;
	}

	private static byte[] build(ServerLevel level, SeamLayout layout) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		RandomState random = level.getChunkSource().randomState();
		BiomeResolver biomes = generator.getBiomeSource().createResolver(random.createClimateSampler(SamplerContext.builder().enableCaches().build()));
		int sea = generator.getSeaLevel();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(SeamLayout.FACES.length * (CELLS + 1) * (CELLS + 1) * 5);
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			for (int face = 0; face < SeamLayout.FACES.length; face++) {
				for (int i = 0; i <= CELLS; i++) {
					int x = layout.offsetX(face) + Math.round(-layout.radius + 2.0F * layout.radius * i / CELLS);
					for (int j = 0; j <= CELLS; j++) {
						int z = Math.round(-layout.radius + 2.0F * layout.radius * j / CELLS);
						int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random);
						boolean water = floor <= sea;
						int top = water ? sea : floor;
						Biome biome = biomes.getNoiseBiome(x >> 2, top >> 2, z >> 2).value();
						int rgb = color(biome, x, z, top - sea, water, sea - floor);
						out.writeShort(top - layout.surfaceY);
						out.writeByte(rgb >> 16);
						out.writeByte(rgb >> 8);
						out.writeByte(rgb);
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}

	/** The color of a node as 0xRRGGBB. */
	private static int color(Biome biome, int x, int z, int aboveSea, boolean water, int depth) {
		float temperature = biome.getBaseTemperature();
		if (water) {
			float shade = Mth.clamp(1.0F - depth / 60.0F, 0.35F, 1.0F);
			return scale(temperature <= 0.0F && depth < 4 ? 0xA8C8E8 : biome.getWaterColor() & 0xFFFFFF, shade);
		}
		if (aboveSea > SNOW_ABOVE || temperature <= 0.15F) {
			return 0xF2F6F8;
		}
		if (aboveSea > STONE_ABOVE) {
			return 0x8A8A8A;
		}
		if (!biome.hasPrecipitation() && temperature >= 1.0F) {
			return 0xD8C690;
		}
		return biome.getGrassColor(x, z) & 0xFFFFFF;
	}

	private static int scale(int rgb, float factor) {
		int r = (int) (((rgb >> 16) & 0xFF) * factor);
		int g = (int) (((rgb >> 8) & 0xFF) * factor);
		int b = (int) ((rgb & 0xFF) * factor);
		return r << 16 | g << 8 | b;
	}
}
