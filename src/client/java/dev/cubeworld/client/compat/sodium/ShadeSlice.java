package dev.cubeworld.client.compat.sodium;

/** Implemented by the slice of Sodium. */
public interface ShadeSlice {
	/** Sets the block that the mesher is about to render, which the shade of its faces depends on. */
	void cubeworld$setShadePos(int x, int y, int z);
}
