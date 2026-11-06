package dev.cubism.client.compat.sodium;

import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;

/**
 * The frustum of the real camera, asked about boxes in the frame of a neighbor face. The boxes are relative to the
 * camera as it shows in that face, so the turn of the fold carries them to the frame of the real camera first.
 * The turn only swaps and negates axes, so a box stays a box.
 */
public final class FoldedFrustum implements Frustum {
	private final Frustum real;
	private final int[] r;

	public FoldedFrustum(Frustum real, int[] motion) {
		this.real = real;
		this.r = motion;
	}

	private float x(float x, float y, float z) {
		return r[0] * x + r[1] * y + r[2] * z;
	}

	private float y(float x, float y, float z) {
		return r[3] * x + r[4] * y + r[5] * z;
	}

	private float z(float x, float y, float z) {
		return r[6] * x + r[7] * y + r[8] * z;
	}

	@Override
	public boolean testAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		float ax = x(minX, minY, minZ), ay = y(minX, minY, minZ), az = z(minX, minY, minZ);
		float bx = x(maxX, maxY, maxZ), by = y(maxX, maxY, maxZ), bz = z(maxX, maxY, maxZ);
		return real.testAab(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz), Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
	}

	@Override
	public int intersectAab(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
		float ax = x(minX, minY, minZ), ay = y(minX, minY, minZ), az = z(minX, minY, minZ);
		float bx = x(maxX, maxY, maxZ), by = y(maxX, maxY, maxZ), bz = z(maxX, maxY, maxZ);
		return real.intersectAab(Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz), Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
	}

	/** Sodium passes the center of the section, and pads the test itself. */
	@Override
	public boolean testSection(float x, float y, float z) {
		return real.testSection(x(x, y, z), y(x, y, z), z(x, y, z));
	}

	@Override
	public boolean testSectionExpanded(float x, float y, float z, float extend) {
		return real.testSectionExpanded(x(x, y, z), y(x, y, z), z(x, y, z), extend);
	}
}
