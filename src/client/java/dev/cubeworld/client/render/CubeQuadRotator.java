package dev.cubeworld.client.render;

import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class CubeQuadRotator {
	private CubeQuadRotator() {}

	public static BakedQuad rotateQuad(BakedQuad quad, Direction gravity) {
		if (quad == null || gravity == Direction.DOWN) {
			return quad;
		}
		Quaternionf q = CubePlanetMath.getCameraRotation(gravity);
		Vector3fc p0 = rotateVertex(quad.position0(), q);
		Vector3fc p1 = rotateVertex(quad.position1(), q);
		Vector3fc p2 = rotateVertex(quad.position2(), q);
		Vector3fc p3 = rotateVertex(quad.position3(), q);
		Direction rotatedDir = CubePlanetMath.rotateDirection(quad.direction(), gravity);

		return new BakedQuad(
			p0,
			p1,
			p2,
			p3,
			quad.packedUV0(),
			quad.packedUV1(),
			quad.packedUV2(),
			quad.packedUV3(),
			rotatedDir,
			quad.materialInfo()
		);
	}

	private static Vector3fc rotateVertex(Vector3fc pos, Quaternionf q) {
		Vector3f v = new Vector3f(pos.x() - 0.5f, pos.y() - 0.5f, pos.z() - 0.5f);
		q.transform(v);
		v.add(0.5f, 0.5f, 0.5f);

		if (Math.abs(v.x) < 1.0E-5f) v.x = 0.0f;
		else if (Math.abs(v.x - 1.0f) < 1.0E-5f) v.x = 1.0f;

		if (Math.abs(v.y) < 1.0E-5f) v.y = 0.0f;
		else if (Math.abs(v.y - 1.0f) < 1.0E-5f) v.y = 1.0f;

		if (Math.abs(v.z) < 1.0E-5f) v.z = 0.0f;
		else if (Math.abs(v.z - 1.0f) < 1.0E-5f) v.z = 1.0f;

		return v;
	}
}
