package dev.cubeworld.client;

/** A particle that can move into the face region of the camera. */
public interface SeamParticle {
	double cubeworld$x();

	double cubeworld$z();

	/** Applies a seam motion to the position, the last position and the velocity. */
	void cubeworld$move(int[] motion);
}
