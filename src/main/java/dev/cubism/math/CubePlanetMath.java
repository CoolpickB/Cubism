package dev.cubism.math;

public final class CubePlanetMath {
	public static final int MIN_DIAMETER_CHUNKS = 24;
	public static final int MAX_DIAMETER_CHUNKS = 128;
	public static final int TOP_SURFACE_Y = 64;
	public static final int CRUST_THICKNESS = 191;
	/** The storage Y above the top block of the cube dimension (min_y -128, height 384). Space starts above it. */
	public static final int BUILD_TOP_Y = 256;

	private CubePlanetMath() {}

	public static int getRadiusBlocks(int diameterChunks) {
		return Math.max(MIN_DIAMETER_CHUNKS, diameterChunks) * 8;
	}

	public static int getCenterY(int diameterChunks) {
		return TOP_SURFACE_Y - getRadiusBlocks(diameterChunks);
	}

	public static int getInnerBedrockRadius(int diameterChunks) {
		int r = getRadiusBlocks(diameterChunks);
		return Math.max(1, r - CRUST_THICKNESS);
	}

	/** Height of the open sky above the surface of each face. */
	public static final int ATMOSPHERE_HEIGHT = 96;

	public static final int MIN_EDGE_ROUNDING = 2;
	public static final int MAX_EDGE_ROUNDING = 96;
	public static final int DEFAULT_EDGE_ROUNDING = 16;
	/** Radius of the cube edges and corners that terrain height is measured from. It comes from the {@code CubeSettings} of the world: the server sets it when the level starts, a client when it joins. */
	private static volatile int edgeRounding = DEFAULT_EDGE_ROUNDING;

	public static int edgeRounding() {
		return edgeRounding;
	}

	/**
	 * The lower bound keeps the vanilla sea level, one block under the surface, on the rounded part of the contour,
	 * where {@code SeamLayout.seaLevelAt} can solve it.
	 */
	public static void setEdgeRounding(int rounding) {
		edgeRounding = Math.clamp(rounding, MIN_EDGE_ROUNDING, MAX_EDGE_ROUNDING);
	}

	/**
	 * Signed distance from the cube of half size {@code r} with edges and corners rounded by {@code rounding}.
	 * The value is negative inside. With this distance as the terrain height, contours of equal height are rounded
	 * boxes, so terrain near an edge bends over the edge. The Chebyshev distance makes square contours, and terrain
	 * on them goes on past the edge as a flat shelf in the sky of the next face.
	 */
	public static double roundedCubeDistance(double dx, double dy, double dz, double r, int rounding) {
		double core = r - rounding;
		double qx = Math.abs(dx) - core;
		double qy = Math.abs(dy) - core;
		double qz = Math.abs(dz) - core;
		double ox = Math.max(qx, 0.0);
		double oy = Math.max(qy, 0.0);
		double oz = Math.max(qz, 0.0);
		return Math.sqrt(ox * ox + oy * oy + oz * oz) + Math.min(Math.max(qx, Math.max(qy, qz)), 0.0) - rounding;
	}

	/**
	 * Writes the nearest point of the rounded cube surface of {@link #roundedCubeDistance} into {@code out}. A point
	 * deeper than the rounding maps straight out along its face axis, so the points of a face column share one
	 * surface point, like a vanilla column. At that depth the map jumps across the diagonal between two faces.
	 */
	public static void roundedCubeSurfacePoint(double dx, double dy, double dz, double r, int rounding, double[] out) {
		double core = r - rounding;
		double cx = Math.clamp(dx, -core, core);
		double cy = Math.clamp(dy, -core, core);
		double cz = Math.clamp(dz, -core, core);
		double ex = dx - cx;
		double ey = dy - cy;
		double ez = dz - cz;
		double length = Math.sqrt(ex * ex + ey * ey + ez * ez);
		if (length > 1.0e-6) {
			double scale = rounding / length;
			out[0] = cx + ex * scale;
			out[1] = cy + ey * scale;
			out[2] = cz + ez * scale;
			return;
		}
		out[0] = dx;
		out[1] = dy;
		out[2] = dz;
		double ax = Math.abs(dx);
		double ay = Math.abs(dy);
		double az = Math.abs(dz);
		if (ay >= ax && ay >= az) {
			out[1] = Math.copySign(r, dy);
		} else if (ax >= az) {
			out[0] = Math.copySign(r, dx);
		} else {
			out[2] = Math.copySign(r, dz);
		}
	}
}
