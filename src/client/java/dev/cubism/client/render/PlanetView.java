package dev.cubism.client.render;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.cubism.client.SeamClient;
import dev.cubism.worldgen.PlanetMap;
import dev.cubism.worldgen.SeamLayout;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * The cube from space as a coarse mesh, for where the levels of detail draw nothing and for a game without them. The
 * heights and colors come from the server ({@link PlanetMap}). Each node sits on the rounded cube surface, pushed out
 * along its normal by the height of the ground, so the faces meet at the diagonals without a gap.
 */
public final class PlanetView {
	/** In the sky pass, drawn after the stars and before the air. */
	static final RenderPipeline PIPELINE = RenderPipelines.register(
		RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("cubism", "pipeline/planet"))
			.withVertexShader(Identifier.fromNamespaceAndPath("cubism", "core/planet"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("cubism", "core/planet"))
			.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.build()
	);

	/** A column on the edge of a face has a wall this deep, so the next face shows no gap if its ground is lower. */
	private static final int EDGE_DEPTH = 128;
	private static byte @Nullable [] data;
	private static @Nullable GpuBuffer mesh;
	private static int vertices;
	private static final int[] faceStart = new int[SeamLayout.FACES.length];
	private static final int[] faceCount = new int[SeamLayout.FACES.length];
	private static @Nullable SeamLayout builtFor;

	private PlanetView() {}

	public static void load(byte[] bytes) {
		close();
		data = bytes;
	}

	public static void close() {
		if (mesh != null) {
			mesh.close();
			mesh = null;
		}
		builtFor = null;
		vertices = 0;
	}

	public static void clear() {
		close();
		data = null;
	}

	/** The mesh buffer, built when the data or the layout is new. Null while the server has not sent the map. */
	static @Nullable GpuBuffer mesh() {
		SeamLayout layout = SeamClient.layout;
		if (data == null || layout == null) {
			return null;
		}
		if (mesh == null || builtFor != layout) {
			close();
			build(layout, data);
			builtFor = layout;
		}
		return mesh;
	}

	static int vertices() {
		return vertices;
	}

	static int faceStart(int face) {
		return faceStart[face];
	}

	static int faceCount(int face) {
		return faceCount[face];
	}

	/** Cells are columns: a flat top at one height, and a wall on each side where the next cell is lower. */
	private static void build(SeamLayout layout, byte[] bytes) {
		int cells = PlanetMap.CELLS;
		int nodes = cells + 1;
		int faces = SeamLayout.FACES.length;
		if (bytes.length != faces * nodes * nodes * 5) {
			return;
		}
		ByteBuffer in = ByteBuffer.wrap(bytes);
		short[][] nodeHeight = new short[faces][nodes * nodes];
		int[][] nodeColor = new int[faces][nodes * nodes];
		for (int face = 0; face < faces; face++) {
			for (int n = 0; n < nodes * nodes; n++) {
				nodeHeight[face][n] = in.getShort();
				nodeColor[face][n] = (in.get() & 0xFF) << 16 | (in.get() & 0xFF) << 8 | (in.get() & 0xFF);
			}
		}
		int[][] top = new int[faces][cells * cells];
		int[][] cellColor = new int[faces][cells * cells];
		for (int face = 0; face < faces; face++) {
			for (int i = 0; i < cells; i++) {
				for (int j = 0; j < cells; j++) {
					int a = i * nodes + j;
					top[face][i * cells + j] = Math.round((nodeHeight[face][a] + nodeHeight[face][a + 1] + nodeHeight[face][a + nodes] + nodeHeight[face][a + nodes + 1]) / 4.0F);
					cellColor[face][i * cells + j] = 0xFF000000 | nodeColor[face][a];
				}
			}
		}
		Corners corners = new Corners(layout);
		try (ByteBufferBuilder buffer = new ByteBufferBuilder(1 << 20)) {
			BufferBuilder builder = new BufferBuilder(buffer, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
			int total = 0;
			for (int face = 0; face < faces; face++) {
				faceStart[face] = total;
				for (int i = 0; i < cells; i++) {
					for (int j = 0; j < cells; j++) {
						int height = top[face][i * cells + j];
						int color = cellColor[face][i * cells + j];
						float[] t00 = corners.at(face, i, j, height);
						float[] t01 = corners.at(face, i, j + 1, height);
						float[] t10 = corners.at(face, i + 1, j, height);
						float[] t11 = corners.at(face, i + 1, j + 1, height);
						// Counterclockwise seen from outside: u runs along X of the face, v along Z, and the outside is +Y.
						total += emit(builder, color, t00, t01, t10);
						total += emit(builder, color, t10, t01, t11);
						int low0 = i + 1 < cells ? top[face][(i + 1) * cells + j] : height - EDGE_DEPTH;
						if (low0 < height) {
							total += wall(builder, color, t10, t11, corners.at(face, i + 1, j, low0), corners.at(face, i + 1, j + 1, low0), true);
						}
						int low1 = i > 0 ? top[face][(i - 1) * cells + j] : height - EDGE_DEPTH;
						if (low1 < height) {
							total += wall(builder, color, t00, t01, corners.at(face, i, j, low1), corners.at(face, i, j + 1, low1), false);
						}
						int low2 = j + 1 < cells ? top[face][i * cells + j + 1] : height - EDGE_DEPTH;
						if (low2 < height) {
							total += wall(builder, color, t01, t11, corners.at(face, i, j + 1, low2), corners.at(face, i + 1, j + 1, low2), false);
						}
						int low3 = j > 0 ? top[face][i * cells + j - 1] : height - EDGE_DEPTH;
						if (low3 < height) {
							total += wall(builder, color, t00, t10, corners.at(face, i, j, low3), corners.at(face, i + 1, j, low3), true);
						}
					}
				}
				faceCount[face] = total - faceStart[face];
			}
			try (MeshData data = builder.buildOrThrow()) {
				mesh = RenderSystem.getDevice().createBuffer(() -> "Cube planet", 32, data.vertexBuffer());
				vertices = total;
			}
		}
	}

	private static int emit(BufferBuilder builder, int color, float[] a, float[] b, float[] c) {
		builder.addVertex(a[0], a[1], a[2]).setColor(color);
		builder.addVertex(b[0], b[1], b[2]).setColor(color);
		builder.addVertex(c[0], c[1], c[2]).setColor(color);
		return 3;
	}

	/**
	 * A wall along one side of a cell, from the top edge ({@code a}, {@code b}) down to ({@code lowA}, {@code lowB}).
	 * {@code b} is the end that is further along the edge. {@code positive} picks the winding that faces +u or -v, the
	 * other one faces -u or +v.
	 */
	private static int wall(BufferBuilder builder, int color, float[] a, float[] b, float[] lowA, float[] lowB, boolean positive) {
		if (positive) {
			emit(builder, color, a, b, lowA);
			emit(builder, color, b, lowB, lowA);
		} else {
			emit(builder, color, a, lowA, b);
			emit(builder, color, b, lowA, lowB);
		}
		return 6;
	}

	/** The position in the cube frame of a node of a face at a height over the top of the face. */
	private static final class Corners {
		private final SeamLayout layout;
		private final double rounding;
		private final double core;

		Corners(SeamLayout layout) {
			this.layout = layout;
			this.rounding = layout.edgeRounding();
			this.core = layout.radius - this.rounding;
		}

		float[] at(int face, int i, int j, int height) {
			int cells = PlanetMap.CELLS;
			double u = -this.layout.radius + 2.0 * this.layout.radius * i / cells;
			double v = -this.layout.radius + 2.0 * this.layout.radius * j / cells;
			double qx = Mth.clamp(u, -this.core, this.core);
			double qz = Mth.clamp(v, -this.core, this.core);
			double nx = u - qx;
			double ny = this.layout.radius - this.core;
			double nz = v - qz;
			double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
			double out = this.rounding + height;
			double x = qx + nx / length * out;
			double y = this.core + ny / length * out;
			double z = qz + nz / length * out;
			int[] m = this.layout.rotation(face);
			return new float[] {
				(float) (m[0] * x + m[1] * y + m[2] * z),
				(float) (m[3] * x + m[4] * y + m[5] * z),
				(float) (m[6] * x + m[7] * y + m[8] * z)
			};
		}
	}
}
