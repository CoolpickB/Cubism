package dev.cubeworld.gravity;

import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

public record CubePlanet(int centerY, int radius) {
	/** Returns the planet of this level, or null when the level is not a cube world. */
	public static @Nullable CubePlanet of(Level level) {
		if (level instanceof ServerLevel serverLevel) {
			return serverLevel.getChunkSource().getGenerator() instanceof CubeChunkGenerator generator
				? new CubePlanet(generator.centerY(), generator.radiusBlocks())
				: null;
		}
		if (level.isClientSide() && level.dimension() == Level.OVERWORLD && CubePlanetMath.isClientCubeActive()) {
			return new CubePlanet(CubePlanetMath.getClientCenterY(), CubePlanetMath.getClientRadius());
		}
		return null;
	}
}
