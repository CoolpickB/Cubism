package dev.cubism.client.compat.sodium;

/** Implemented by the slice of Sodium. */
public interface ShadeSlice {
	/** Sets the block that the mesher is about to render, which the shade of its faces depends on. */
	void cubism$setShadePos(int x, int y, int z);
}
