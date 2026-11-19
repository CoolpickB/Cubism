package dev.cubism.client.compat.iris;

import dev.cubism.client.SeamClient;
import dev.cubism.cube.SeamLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

/**
 * A shader pack reprojects the last frame with the camera position and view matrix of that frame. When the camera
 * crosses to the next face, both are written in the frame of the old face, and the reprojection reads the last frame from
 * the wrong place for one frame. The motion that carries the old face to the new one puts them in the new frame.
 */
public final class FrameCrossing {
	private static int face = -1;
	private static int @Nullable [] motion;

	private FrameCrossing() {}

	/** Called once per frame, before the shader pack reads the values of the last frame. */
	public static void begin() {
		SeamLayout layout = SeamClient.layout;
		Minecraft client = Minecraft.getInstance();
		if (layout == null || client.level == null) {
			face = -1;
			motion = null;
			return;
		}
		int now = layout.regionOfX(Mth.floor(client.gameRenderer.mainCamera().position().x));
		int[] m = face >= 0 && face != now ? layout.motion(face, now) : null;
		// Opposite faces never meet, but the space region meets every face, the bottom one too.
		motion = m != null && (m[4] != -1 || face == SeamLayout.SPACE || now == SeamLayout.SPACE) ? m : null;
		face = now;
	}

	public static Vector3d position(Vector3d previous) {
		int[] m = motion;
		if (m == null) {
			return previous;
		}
		double x = previous.x, y = previous.y, z = previous.z;
		return new Vector3d(m[0] * x + m[1] * y + m[2] * z + m[9], m[3] * x + m[4] * y + m[5] * z + m[10], m[6] * x + m[7] * y + m[8] * z + m[11]);
	}

	/** The turn from the frame of the new face back into the one of the old face, or the identity outside a crossing. */
	public static Matrix3f backTurn() {
		int[] m = motion;
		// The columns of the transpose of R are its rows.
		return m == null ? new Matrix3f() : new Matrix3f(m[0], m[1], m[2], m[3], m[4], m[5], m[6], m[7], m[8]);
	}

	/** A model view matrix of the last frame. Other matrices, such as the projection, are not affine and stay as they are. */
	public static Matrix4fc modelView(Matrix4fc previous) {
		int[] m = motion;
		if (m == null || !previous.isAffine()) {
			return previous;
		}
		Matrix4f turn = new Matrix4f(m[0], m[3], m[6], 0, m[1], m[4], m[7], 0, m[2], m[5], m[8], 0, 0, 0, 0, 1);
		return new Matrix4f(previous).mul(turn.transpose());
	}
}
