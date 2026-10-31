package dev.cubeworld.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.core.Direction;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Settings of one cube world, set in the Customize screen. The overworld generator keeps them, so they are saved in
 * {@code level.dat} with the world. Every field is written, so a change to the preset does not change old worlds.
 *
 * @param pregenRadius chunks around the spawn to generate before the player joins: 0 for none,
 *                     {@link #PREGEN_WHOLE_CUBE} for all six faces
 */
public record CubeSettings(int diameterChunks, int edgeRounding, Direction spawnFace, int pregenRadius) {
	public static final int PREGEN_WHOLE_CUBE = -1;
	public static final int MAX_PREGEN_RADIUS = 64;
	/** The cube of worlds made before these settings existed. New worlds take their values from the world preset. */
	public static final CubeSettings DEFAULT = new CubeSettings(
		CubePlanetMath.MIN_DIAMETER_CHUNKS, CubePlanetMath.DEFAULT_EDGE_ROUNDING, Direction.UP, 0
	);

	public static final Codec<CubeSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.intRange(CubePlanetMath.MIN_DIAMETER_CHUNKS, CubePlanetMath.MAX_DIAMETER_CHUNKS)
			.fieldOf("diameter_chunks").forGetter(CubeSettings::diameterChunks),
		Codec.intRange(CubePlanetMath.MIN_EDGE_ROUNDING, CubePlanetMath.MAX_EDGE_ROUNDING)
			.fieldOf("edge_rounding").forGetter(CubeSettings::edgeRounding),
		Direction.CODEC.fieldOf("spawn_face").forGetter(CubeSettings::spawnFace),
		Codec.intRange(PREGEN_WHOLE_CUBE, MAX_PREGEN_RADIUS)
			.fieldOf("pregen_radius").forGetter(CubeSettings::pregenRadius)
	).apply(i, CubeSettings::new));

	/** Index of the spawn face in {@link SeamLayout#FACES}. */
	public int spawnFaceIndex() {
		return Arrays.asList(SeamLayout.FACES).indexOf(this.spawnFace);
	}

	/** The settings of a seam world generator. Other generators return the defaults. */
	public static CubeSettings of(ChunkGenerator generator) {
		CubeSettings settings = generator instanceof Holder holder ? holder.cubeworld$settings() : null;
		return settings != null ? settings : DEFAULT;
	}

	/** Implemented on the noise generator by a mixin. */
	public interface Holder {
		@Nullable CubeSettings cubeworld$settings();

		void cubeworld$setSettings(@Nullable CubeSettings settings);
	}
}
