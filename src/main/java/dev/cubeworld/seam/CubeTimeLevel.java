package dev.cubeworld.seam;

import org.jspecify.annotations.Nullable;

/** Added to {@link net.minecraft.world.level.Level} for {@link CubeTime}. */
public interface CubeTimeLevel {
	/** Null in any level but the cube. */
	CubeTime.@Nullable State cubeworld$cubeTime();

	void cubeworld$setCubeTime(CubeTime.State state);

	int cubeworld$skyDarken();

	void cubeworld$setSkyDarken(int skyDarken);
}
