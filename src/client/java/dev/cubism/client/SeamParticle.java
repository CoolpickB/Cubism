package dev.cubism.client;

/** A particle that can move into the face region of the camera. */
public interface SeamParticle {
	double cubism$x();

	double cubism$z();

	/** Applies a seam motion to the position, the last position and the velocity. */
	void cubism$move(int[] motion);
}
