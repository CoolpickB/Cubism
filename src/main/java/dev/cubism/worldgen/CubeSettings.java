package dev.cubism.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.cubism.math.CubePlanetMath;
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
 * @param temperature a shift of the temperature of the climate, in hundredths of its range: 100 is a full range hotter
 * @param humidity a shift of the humidity of the climate, in hundredths of its range
 */
public record CubeSettings(int diameterChunks, int edgeRounding, Direction spawnFace, Pregen pregen, int temperature, int humidity) {
	public static final int MIN_CLIMATE_SHIFT = -100;
	public static final int MAX_CLIMATE_SHIFT = 100;

	/** The cube of worlds made before these settings existed. New worlds take their values from the world preset. */
	public static final CubeSettings DEFAULT = new CubeSettings(
		CubePlanetMath.MIN_DIAMETER_CHUNKS, CubePlanetMath.DEFAULT_EDGE_ROUNDING, Direction.UP, Pregen.OFF, 0, 0
	);

	public static final Codec<CubeSettings> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.intRange(CubePlanetMath.MIN_DIAMETER_CHUNKS, CubePlanetMath.MAX_DIAMETER_CHUNKS)
			.fieldOf("diameter_chunks").forGetter(CubeSettings::diameterChunks),
		Codec.intRange(CubePlanetMath.MIN_EDGE_ROUNDING, CubePlanetMath.MAX_EDGE_ROUNDING)
			.fieldOf("edge_rounding").forGetter(CubeSettings::edgeRounding),
		Direction.CODEC.fieldOf("spawn_face").forGetter(CubeSettings::spawnFace),
		// Pre-generation runs only when the world is created, so a world without the field loses nothing.
		Pregen.CODEC.optionalFieldOf("pregen", Pregen.OFF).forGetter(CubeSettings::pregen),
		// A world without the fields has the climate of the vanilla noise.
		Codec.intRange(MIN_CLIMATE_SHIFT, MAX_CLIMATE_SHIFT).optionalFieldOf("temperature", 0).forGetter(CubeSettings::temperature),
		Codec.intRange(MIN_CLIMATE_SHIFT, MAX_CLIMATE_SHIFT).optionalFieldOf("humidity", 0).forGetter(CubeSettings::humidity)
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

	public CubeSettings withDiameterChunks(int diameterChunks) {
		return new CubeSettings(diameterChunks, this.edgeRounding, this.spawnFace, this.pregen, this.temperature, this.humidity);
	}

	public CubeSettings withEdgeRounding(int edgeRounding) {
		return new CubeSettings(this.diameterChunks, edgeRounding, this.spawnFace, this.pregen, this.temperature, this.humidity);
	}

	public CubeSettings withPregen(Pregen pregen) {
		return new CubeSettings(this.diameterChunks, this.edgeRounding, this.spawnFace, pregen, this.temperature, this.humidity);
	}

	public CubeSettings withTemperature(int temperature) {
		return new CubeSettings(this.diameterChunks, this.edgeRounding, this.spawnFace, this.pregen, temperature, this.humidity);
	}

	public CubeSettings withHumidity(int humidity) {
		return new CubeSettings(this.diameterChunks, this.edgeRounding, this.spawnFace, this.pregen, this.temperature, humidity);
	}

	/** True when the climate differs from the vanilla noise. */
	public boolean shiftsClimate() {
		return this.temperature != 0 || this.humidity != 0;
	}

	/** Index of the spawn face in {@link SeamLayout#FACES}. */
	public int spawnFaceIndex() {
		return Arrays.asList(SeamLayout.FACES).indexOf(this.spawnFace);
	}

	/** The settings of a seam world generator. Other generators return the defaults. */
	public static CubeSettings of(ChunkGenerator generator) {
		CubeSettings settings = generator instanceof Holder holder ? holder.cubism$settings() : null;
		return settings != null ? settings : DEFAULT;
	}

	/** Implemented on the noise generator by a mixin. */
	public interface Holder {
		@Nullable CubeSettings cubism$settings();

		void cubism$setSettings(@Nullable CubeSettings settings);
	}
}
