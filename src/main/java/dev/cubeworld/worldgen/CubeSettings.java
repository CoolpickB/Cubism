package dev.cubeworld.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Settings of one cube world, set in the Customize screen. The overworld generator keeps them, so they are saved in
 * {@code level.dat} with the world. Every field is written, so a change to the preset does not change old worlds.
 *
 * @param pregen what to generate before the first player joins
 */
public record CubeSettings(int diameterChunks, int edgeRounding, Direction spawnFace, Pregen pregen) {
	/** The cube of worlds made before these settings existed. New worlds take their values from the world preset. */
	public static final CubeSettings DEFAULT = new CubeSettings(
		CubePlanetMath.MIN_DIAMETER_CHUNKS, CubePlanetMath.DEFAULT_EDGE_ROUNDING, Direction.UP, Pregen.OFF
	);

	public static final Codec<CubeSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.intRange(CubePlanetMath.MIN_DIAMETER_CHUNKS, CubePlanetMath.MAX_DIAMETER_CHUNKS)
			.fieldOf("diameter_chunks").forGetter(CubeSettings::diameterChunks),
		Codec.intRange(CubePlanetMath.MIN_EDGE_ROUNDING, CubePlanetMath.MAX_EDGE_ROUNDING)
			.fieldOf("edge_rounding").forGetter(CubeSettings::edgeRounding),
		Direction.CODEC.fieldOf("spawn_face").forGetter(CubeSettings::spawnFace),
		// Pre-generation runs only when the world is created, so a world without the field loses nothing.
		Pregen.CODEC.optionalFieldOf("pregen", Pregen.OFF).forGetter(CubeSettings::pregen)
	).apply(i, CubeSettings::new));

	public enum Pregen implements StringRepresentable {
		OFF("off"),
		/** The spawn face and {@link CubePregen#SPAWN_FACE_MARGIN} chunks over each of its edges. */
		SPAWN_FACE("spawn_face"),
		WHOLE_CUBE("whole_cube");

		public static final Codec<Pregen> CODEC = StringRepresentable.fromEnum(Pregen::values);
		private final String name;

		Pregen(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return this.name;
		}
	}

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
