package dev.cubism.time;

import org.jspecify.annotations.Nullable;

/** Added to {@link net.minecraft.world.level.Level} for {@link CubeTime}. */
public interface CubeTimeLevel {
	/** Null in any level but the cube. */
	CubeTime.@Nullable State cubism$cubeTime();

	void cubism$setCubeTime(CubeTime.State state);

	int cubism$skyDarken();

	void cubism$setSkyDarken(int skyDarken);
}
