package dev.cubism.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.ListIterator;

/**
 * Rain and snow of the cube. Vanilla puts its weather columns around the camera on the camera face only. Here the
 * neighbor faces near the camera add their own columns, around the camera position in their region. All the columns
 * then go into the frame of the camera face.
 *
 * <p>The weather falls toward the rounded cube surface, not straight down in its face. Over a rounded edge these
 * paths are straight lines out from the edge of the inner box, so a column is a straight line that leans by the
 * surface direction at the camera height. Inside a face it stands straight. On the diagonal it leans 45 degrees, the
 * same for both faces, and no path crosses the diagonal, so the rain of two faces meets without a line. A leaning
 * column stops at the first block along its path, as a straight column stops at the ground.
 */
public final class CubeWeather {
	/** The rounded edges of the cube surface have this radius, as in CubeTime and the sky shader. */
	private static final double EDGE = 32.0;
	/** The faces turn into each other about the center of the local block (0, 0, 0), as the seam motion does. */
	private static final double CENTER = 0.5;

	private CubeWeather() {}

	/** True when a neighbor face can have columns within the weather radius of the camera. */
	public static boolean reaches(SeamLayout layout, int face, Vec3 cameraInFace, int radius) {
		double x = cameraInFace.x - layout.offsetX(face) - CENTER;
		double y = cameraInFace.y - layout.centerY - CENTER;
		double z = cameraInFace.z - CENTER;
		return Math.max(Math.abs(x), Math.abs(z)) - y < 1.5 * radius + 2.0;
	}

	/**
	 * Fits the columns that vanilla made around a camera position in one face region to the cube. A column passes
	 * through the center of its block at the camera height. Columns that are outside the wedge there belong to a
	 * neighbor face and go. A leaning column runs from the top of vanilla down its path, to the first block.
	 */
	public static void fitColumns(ClientLevel level, SeamLayout layout, List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 camera, int radius) {
		int face = layout.regionOfX(Mth.floor(camera.x));
		int anchorY = Mth.floor(camera.y);
		ListIterator<WeatherEffectRenderer.ColumnInstance> iterator = columns.listIterator();
		while (iterator.hasNext()) {
			WeatherEffectRenderer.ColumnInstance column = iterator.next();
			double x = column.x() + 0.5;
			double z = column.z() + 0.5;
			if (!open(level, layout, face, x, anchorY, z, false)) {
				iterator.remove();
				continue;
			}
			double[] lean = lean(layout, face, column.x(), column.z(), anchorY);
			if (lean[0] == 0.0 && lean[1] == 0.0) {
				continue;
			}
			int top = anchorY + radius;
			if (!open(level, layout, face, x + lean[0] * radius, top, z + lean[1] * radius, true)) {
				iterator.remove();
				continue;
			}
			int bottom = top;
			while (bottom > anchorY - radius) {
				int shift = bottom - 1 - anchorY;
				if (!open(level, layout, face, x + lean[0] * shift, bottom - 1, z + lean[1] * shift, true)) {
					break;
				}
				bottom--;
			}
			iterator.set(new WeatherEffectRenderer.ColumnInstance(
				column.x(), column.z(), bottom, top, column.uOffset(), column.vOffset(), column.lightCoords()
			));
		}
	}

	/** True when a point is inside the wedge of its face, and, if asked, above the ground there. */
	private static boolean open(ClientLevel level, SeamLayout layout, int face, double x, int y, double z, boolean ground) {
		double localX = x - layout.offsetX(face) - CENTER;
		double localZ = z - CENTER;
		if (Math.max(Math.abs(localX), Math.abs(localZ)) > y - layout.centerY) {
			return false;
		}
		return !ground || level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z)) <= y;
	}

	/**
	 * The lean of the column through the center of a block at a height, as a shift per block of height: the surface
	 * direction there, over its part along the face normal. Zero inside the flat part of a face.
	 */
	private static double[] lean(SeamLayout layout, int face, int x, int z, int y) {
		double inner = layout.radius - EDGE;
		double localX = x - layout.offsetX(face);
		double localY = y - layout.centerY;
		double qx = localX - Mth.clamp(localX, -inner, inner);
		double qy = localY - Mth.clamp(localY, -inner, inner);
		double qz = z - Mth.clamp(z, -inner, inner);
		return qy > 0.0 ? new double[] {qx / qy, qz / qy} : new double[] {0.0, 0.0};
	}

	/** Adds the quads of the columns of all faces, in the order of the list, as vanilla does. */
	public static void addColumns(
		VertexConsumer builder, SeamLayout layout, List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 cameraPos, float maxAlpha, int radius,
		float intensity
	) {
		int cameraFace = layout.regionOfX(Mth.floor(cameraPos.x));
		float radiusSq = radius * radius;
		int lastFace = -1;
		int[] toCamera = null;
		Vec3 camera = cameraPos;
		for (WeatherEffectRenderer.ColumnInstance column : columns) {
			int face = layout.faceIndexAt(column.x());
			if (face != lastFace) {
				lastFace = face;
				toCamera = layout.motion(face, cameraFace);
				camera = SeamInteraction.move(layout.motion(cameraFace, face), cameraPos);
			}
			// The same anchor height as in fitColumns, so the column follows the path that was traced.
			int anchorY = Mth.floor(camera.y);
			double[] lean = lean(layout, face, column.x(), column.z(), anchorY);
			double relativeX = column.x() + 0.5 - camera.x;
			double relativeZ = column.z() + 0.5 - camera.z;
			float distanceSq = (float) (relativeX * relativeX + relativeZ * relativeZ);
			float alpha = Mth.lerp(Math.min(distanceSq / radiusSq, 1.0F), maxAlpha, 0.5F) * intensity;
			int color = ARGB.white(alpha);
			// The quad faces the camera, as in vanilla.
			double length = Math.sqrt(distanceSq);
			double halfX = length > 1.0E-4 ? -relativeZ / length / 2.0 : 0.5;
			double halfZ = length > 1.0E-4 ? relativeX / length / 2.0 : 0.0;
			double topShift = column.topY() - anchorY;
			double bottomShift = column.bottomY() - anchorY;
			double y0 = column.bottomY() - camera.y;
			double y1 = column.topY() - camera.y;
			float u0 = column.uOffset();
			float u1 = column.uOffset() + 1.0F;
			float v0 = column.bottomY() * 0.25F + column.vOffset();
			float v1 = column.topY() * 0.25F + column.vOffset();
			int light = column.lightCoords();
			vertex(builder, toCamera, relativeX - halfX + lean[0] * topShift, y1, relativeZ - halfZ + lean[1] * topShift, u0, v0, color, light);
			vertex(builder, toCamera, relativeX + halfX + lean[0] * topShift, y1, relativeZ + halfZ + lean[1] * topShift, u1, v0, color, light);
			vertex(builder, toCamera, relativeX + halfX + lean[0] * bottomShift, y0, relativeZ + halfZ + lean[1] * bottomShift, u1, v1, color, light);
			vertex(builder, toCamera, relativeX - halfX + lean[0] * bottomShift, y0, relativeZ - halfZ + lean[1] * bottomShift, u0, v1, color, light);
		}
	}

	private static void vertex(VertexConsumer builder, int[] m, double x, double y, double z, float u, float v, int color, int light) {
		builder.addVertex(
			(float) (m[0] * x + m[1] * y + m[2] * z),
			(float) (m[3] * x + m[4] * y + m[5] * z),
			(float) (m[6] * x + m[7] * y + m[8] * z)
		).setUv(u, v).setColor(color).setLight(light);
	}
}
