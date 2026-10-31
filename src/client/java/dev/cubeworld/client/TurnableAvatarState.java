package dev.cubeworld.client;

/** The player state that the client crossing of {@link CubeFlight} carries into the region of the next face. */
public interface TurnableAvatarState {
	/** Applies a seam motion to the stored velocity and cape positions, with the body turning about its center. */
	void cubeworld$turn(int[] motion, double halfHeight);
}
