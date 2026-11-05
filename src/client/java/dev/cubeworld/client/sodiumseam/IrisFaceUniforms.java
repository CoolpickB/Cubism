package dev.cubeworld.client.sodiumseam;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.nio.FloatBuffer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL46C;

/**
 * Shader packs read the world through one frame per frame: the gbuffer and shadow model view matrices and the camera
 * position. A face drawn folded next to the camera face keeps its own up in its storage frame, but in the frame of the
 * camera face its ground faces sideways, and a pack lights it as a wall. While a folded face draws, the program of the
 * pack gets the matrices and camera position of that face, so its ground is up. View space does not change, so the sun,
 * the shadow lookup and the screen position stay the same. Only load this class when Iris is present.
 */
public final class IrisFaceUniforms {
	private static final String[] MATRICES = {"gbufferModelView", "gbufferPreviousModelView", "shadowModelView"};
	private static final String[] INVERSES = {"gbufferModelViewInverse", "shadowModelViewInverse"};
	private static final String[] POSITIONS = {"cameraPosition", "previousCameraPosition"};

	/** The motion {@code s_camera = R s_face + t} of the face that draws, or null outside a folded face. */
	private static int @Nullable [] motion;
	/** The values of the camera face in each program that a folded face changed this draw, to put back afterwards. */
	private static final Int2ObjectOpenHashMap<Saved> SAVED = new Int2ObjectOpenHashMap<>();

	private IrisFaceUniforms() {}

	static void beginFace(int[] faceMotion) {
		motion = faceMotion;
	}

	static void endFaces() {
		motion = null;
		for (Saved saved : SAVED.values()) {
			saved.restore();
		}
		SAVED.clear();
	}

	/** Iris sets up the program for every draw, so this runs after Iris has written the values of the camera face. */
	public static void onSetup(int program) {
		int[] m = motion;
		if (m != null) {
			SAVED.computeIfAbsent(program, Saved::new).apply(m);
		}
	}

	private static final class Saved {
		private final int program;
		private final int[] matrixAt = new int[MATRICES.length];
		private final Matrix4f[] matrices = new Matrix4f[MATRICES.length];
		private final int[] inverseAt = new int[INVERSES.length];
		private final Matrix4f[] inverses = new Matrix4f[INVERSES.length];
		private final int normalAt;
		private final @Nullable Matrix3f normalMatrix;
		private final int[] positionAt = new int[POSITIONS.length];
		private final float[][] positions = new float[POSITIONS.length][3];
		private final FloatBuffer floats = BufferUtils.createFloatBuffer(16);

		Saved(int program) {
			this.program = program;
			for (int i = 0; i < MATRICES.length; i++) {
				this.matrixAt[i] = GL46C.glGetUniformLocation(program, MATRICES[i]);
				this.matrices[i] = this.readMatrix(this.matrixAt[i]);
			}
			for (int i = 0; i < INVERSES.length; i++) {
				this.inverseAt[i] = GL46C.glGetUniformLocation(program, INVERSES[i]);
				this.inverses[i] = this.readMatrix(this.inverseAt[i]);
			}
			this.normalAt = GL46C.glGetUniformLocation(program, "iris_NormalMat");
			if (this.normalAt >= 0) {
				this.floats.clear();
				GL46C.glGetUniformfv(program, this.normalAt, this.floats);
				this.normalMatrix = new Matrix3f().set(this.floats);
			} else {
				this.normalMatrix = null;
			}
			for (int i = 0; i < POSITIONS.length; i++) {
				this.positionAt[i] = GL46C.glGetUniformLocation(program, POSITIONS[i]);
				if (this.positionAt[i] >= 0) {
					this.floats.clear();
					GL46C.glGetUniformfv(program, this.positionAt[i], this.floats);
					this.floats.get(0, this.positions[i]);
				}
			}
		}

		private @Nullable Matrix4f readMatrix(int location) {
			if (location < 0) {
				return null;
			}
			this.floats.clear();
			GL46C.glGetUniformfv(this.program, location, this.floats);
			return new Matrix4f().set(this.floats);
		}

		void apply(int[] m) {
			// Columns of the turn are the columns of R, the same turn that folds the face into the camera face.
			Matrix4f turn = new Matrix4f(m[0], m[3], m[6], 0, m[1], m[4], m[7], 0, m[2], m[5], m[8], 0, 0, 0, 0, 1);
			for (int i = 0; i < MATRICES.length; i++) {
				if (this.matrices[i] != null) {
					this.writeMatrix(this.matrixAt[i], new Matrix4f(this.matrices[i]).mul(turn));
				}
			}
			for (int i = 0; i < INVERSES.length; i++) {
				if (this.inverses[i] != null) {
					this.writeMatrix(this.inverseAt[i], new Matrix4f(turn).transpose().mul(this.inverses[i]));
				}
			}
			if (this.normalMatrix != null) {
				// The program takes the normal matrix from the camera face, as it does not read the matrix of the draw.
				this.writeNormal(new Matrix3f(this.normalMatrix).mul(new Matrix3f(turn)));
			}
			for (int i = 0; i < POSITIONS.length; i++) {
				if (this.positionAt[i] >= 0) {
					float[] p = this.positions[i];
					float x = p[0] - m[9], y = p[1] - m[10], z = p[2] - m[11];
					GL46C.glProgramUniform3f(this.program, this.positionAt[i],
						m[0] * x + m[3] * y + m[6] * z, m[1] * x + m[4] * y + m[7] * z, m[2] * x + m[5] * y + m[8] * z);
				}
			}
		}

		void restore() {
			if (this.normalMatrix != null) {
				this.writeNormal(this.normalMatrix);
			}
			for (int i = 0; i < MATRICES.length; i++) {
				if (this.matrices[i] != null) {
					this.writeMatrix(this.matrixAt[i], this.matrices[i]);
				}
			}
			for (int i = 0; i < INVERSES.length; i++) {
				if (this.inverses[i] != null) {
					this.writeMatrix(this.inverseAt[i], this.inverses[i]);
				}
			}
			for (int i = 0; i < POSITIONS.length; i++) {
				if (this.positionAt[i] >= 0) {
					float[] p = this.positions[i];
					GL46C.glProgramUniform3f(this.program, this.positionAt[i], p[0], p[1], p[2]);
				}
			}
		}

		private void writeNormal(Matrix3f value) {
			this.floats.clear();
			value.get(this.floats);
			GL46C.glProgramUniformMatrix3fv(this.program, this.normalAt, false, this.floats);
		}

		private void writeMatrix(int location, Matrix4f value) {
			this.floats.clear();
			value.get(this.floats);
			GL46C.glProgramUniformMatrix4fv(this.program, location, false, this.floats);
		}
	}
}
