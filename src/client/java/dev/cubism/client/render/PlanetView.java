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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

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

	/** Blocks that each column top sits under the lowest of its nodes. */
	private static final int SINK = 6;

	/**
	 * The columns for a shader pack, bound by the pack properties (see IrisShaderPackMixin). The mesh writes no depth, so the
	 * clouds of the pack find where a ray meets it here. Face k of the cube frame (+X, -X, +Y, -Y, +Z, -Z) is the block of
	 * 64 by 64 texels from x = 64k. Its texels run along the two other axes in order, from -radius to +radius. Red and
	 * green hold the top of the column over the top of the face plus {@link #TEXTURE_OFFSET}, low byte first, and blue is
	 * 255 over a cold biome. Row 64 holds the edge rounding of the mesh at x 0 and the highest top at x 1, in the same
	 * two bytes.
	 */
	public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("cubism", "dynamic/planet");
	private static final int TEXTURE_OFFSET = 1024;
	private static @Nullable DynamicTexture texture;

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
		LoggerFactory.getLogger("cubism").info("Planet map received: {} bytes, layout {}", bytes.length, SeamClient.layout != null);
	}

	public static void close() {
		if (mesh != null) {
			if (!mesh.isClosed()) {
				mesh.close();
			}
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
		prepare();
		return mesh != null && !mesh.isClosed() ? mesh : null;
	}

	/** Builds the mesh and the texture when the data or the layout is new. */
	static void prepare() {
		SeamLayout layout = SeamClient.layout;
		if (data == null || layout == null || (mesh != null && !mesh.isClosed() && builtFor == layout)) {
			return;
		}
		close();
		build(layout, data);
		builtFor = layout;
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
		if (bytes.length != faces * nodes * nodes * PlanetMap.BYTES_PER_NODE) {
			LoggerFactory.getLogger("cubism").warn("Planet map has {} bytes, expected {}", bytes.length, faces * nodes * nodes * PlanetMap.BYTES_PER_NODE);
			// A map of another version of the mod. Without it, this ran again each frame.
			data = null;
			return;
		}
		ByteBuffer in = ByteBuffer.wrap(bytes);
		short[][] nodeHeight = new short[faces][nodes * nodes];
		int[][] nodeColor = new int[faces][nodes * nodes];
		boolean[][] nodeCold = new boolean[faces][nodes * nodes];
		for (int face = 0; face < faces; face++) {
			for (int n = 0; n < nodes * nodes; n++) {
				nodeHeight[face][n] = in.getShort();
				nodeColor[face][n] = (in.get() & 0xFF) << 16 | (in.get() & 0xFF) << 8 | (in.get() & 0xFF);
				nodeCold[face][n] = in.get() != 0;
			}
		}
		int[][] top = new int[faces][cells * cells];
		int[][] cellColor = new int[faces][cells * cells];
		for (int face = 0; face < faces; face++) {
			for (int i = 0; i < cells; i++) {
				for (int j = 0; j < cells; j++) {
					int a = i * nodes + j;
					// Under the real ground, so that where the terrain draws it covers the mesh, and the mesh shows only
					// where no terrain draws. A mean of the nodes stands over valleys and shows past hills of other faces.
					top[face][i * cells + j] = Math.min(Math.min(nodeHeight[face][a], nodeHeight[face][a + 1]),
						Math.min(nodeHeight[face][a + nodes], nodeHeight[face][a + nodes + 1])) - SINK;
					cellColor[face][i * cells + j] = 0xFF000000 | nodeColor[face][a];
				}
			}
		}
		fillTexture(layout, top, nodeCold);
		Corners corners = new Corners(layout);
		// The columns on the two sides of a diagonal share the two nodes of their common edge. Their tops differ, and the
		// higher one gets a wall down to the lower, found by the position of that edge.
		Map<EdgeKey, List<int[]>> edges = new HashMap<>();
		for (int face = 0; face < faces; face++) {
			for (int i = 0; i < cells; i++) {
				for (int j = 0; j < cells; j++) {
					if (i + 1 == cells) {
						edges.computeIfAbsent(edge(corners, face, i + 1, j, i + 1, j + 1), k -> new ArrayList<>()).add(new int[] {face, top[face][i * cells + j]});
					}
					if (i == 0) {
						edges.computeIfAbsent(edge(corners, face, i, j, i, j + 1), k -> new ArrayList<>()).add(new int[] {face, top[face][i * cells + j]});
					}
					if (j + 1 == cells) {
						edges.computeIfAbsent(edge(corners, face, i, j + 1, i + 1, j + 1), k -> new ArrayList<>()).add(new int[] {face, top[face][i * cells + j]});
					}
					if (j == 0) {
						edges.computeIfAbsent(edge(corners, face, i, j, i + 1, j), k -> new ArrayList<>()).add(new int[] {face, top[face][i * cells + j]});
					}
				}
			}
		}
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
						int low0 = i + 1 < cells ? top[face][(i + 1) * cells + j] : across(edges, edge(corners, face, i + 1, j, i + 1, j + 1), face, height);
						if (low0 < height) {
							total += wall(builder, color, t10, t11, corners.at(face, i + 1, j, low0), corners.at(face, i + 1, j + 1, low0), true);
						}
						int low1 = i > 0 ? top[face][(i - 1) * cells + j] : across(edges, edge(corners, face, i, j, i, j + 1), face, height);
						if (low1 < height) {
							total += wall(builder, color, t00, t01, corners.at(face, i, j, low1), corners.at(face, i, j + 1, low1), false);
						}
						int low2 = j + 1 < cells ? top[face][i * cells + j + 1] : across(edges, edge(corners, face, i, j + 1, i + 1, j + 1), face, height);
						if (low2 < height) {
							total += wall(builder, color, t01, t11, corners.at(face, i, j + 1, low2), corners.at(face, i + 1, j + 1, low2), false);
						}
						int low3 = j > 0 ? top[face][i * cells + j - 1] : across(edges, edge(corners, face, i, j, i + 1, j), face, height);
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

	private static void fillTexture(SeamLayout layout, int[][] top, boolean[][] nodeCold) {
		int cells = PlanetMap.CELLS;
		DynamicTexture target = texture;
		if (target == null) {
			target = new DynamicTexture(() -> "Cube planet columns", cells * SeamLayout.FACES.length, cells + 1, false);
			Minecraft.getInstance().getTextureManager().register(TEXTURE, target);
			texture = target;
		}
		int radius = layout.radius;
		int highest = Integer.MIN_VALUE;
		for (int face = 0; face < SeamLayout.FACES.length; face++) {
			int[] m = layout.rotation(face);
			for (int i = 0; i < cells; i++) {
				double u = -radius + 2.0 * radius * (i + 0.5) / cells;
				for (int j = 0; j < cells; j++) {
					double v = -radius + 2.0 * radius * (j + 0.5) / cells;
					double[] c = {m[0] * u + m[1] * radius + m[2] * v, m[3] * u + m[4] * radius + m[5] * v, m[6] * u + m[7] * radius + m[8] * v};
					int axis = Math.abs(c[0]) >= Math.abs(c[1]) && Math.abs(c[0]) >= Math.abs(c[2]) ? 0 : Math.abs(c[1]) >= Math.abs(c[2]) ? 1 : 2;
					int x = (axis * 2 + (c[axis] < 0.0 ? 1 : 0)) * cells + texel(c[axis == 0 ? 1 : 0], radius);
					int y = texel(c[axis == 2 ? 1 : 2], radius);
					int height = top[face][i * cells + j];
					highest = Math.max(highest, height);
					target.getPixels().setPixel(x, y, packed(height, nodeCold[face][i * (cells + 1) + j]));
				}
			}
		}
		target.getPixels().setPixel(0, cells, packed(layout.edgeRounding(), false));
		target.getPixels().setPixel(1, cells, packed(highest, false));
		target.upload();
		int cold = 0;
		for (boolean[] face : nodeCold) {
			for (boolean node : face) {
				cold += node ? 1 : 0;
			}
		}
		LoggerFactory.getLogger("cubism").info("Planet texture for shader packs: rounding {}, highest column {}, {} cold nodes, registered {}",
			layout.edgeRounding(), highest, cold, Minecraft.getInstance().getTextureManager().getTexture(TEXTURE) == target);
	}

	private static int texel(double along, int radius) {
		return Mth.clamp((int) Math.floor((along + radius) / (2.0 * radius) * PlanetMap.CELLS), 0, PlanetMap.CELLS - 1);
	}

	private static int packed(int value, boolean cold) {
		int stored = Mth.clamp(value + TEXTURE_OFFSET, 0, 0xFFFF);
		return 0xFF000000 | (stored & 0xFF) << 16 | (stored >> 8) << 8 | (cold ? 0xFF : 0);
	}

	private record EdgeKey(int ax, int ay, int az, int bx, int by, int bz) {}

	/** The edge between two nodes of a face, by the positions of its ends on the ground of height 0, in half blocks. */
	private static EdgeKey edge(Corners corners, int face, int i0, int j0, int i1, int j1) {
		float[] a = corners.at(face, i0, j0, 0);
		float[] b = corners.at(face, i1, j1, 0);
		int[] ka = {Math.round(a[0] * 2), Math.round(a[1] * 2), Math.round(a[2] * 2)};
		int[] kb = {Math.round(b[0] * 2), Math.round(b[1] * 2), Math.round(b[2] * 2)};
		boolean swap = ka[0] != kb[0] ? ka[0] > kb[0] : ka[1] != kb[1] ? ka[1] > kb[1] : ka[2] > kb[2];
		int[] first = swap ? kb : ka;
		int[] second = swap ? ka : kb;
		return new EdgeKey(first[0], first[1], first[2], second[0], second[1], second[2]);
	}

	/** The top of the column of the other face on an edge. Without one, no wall. */
	private static int across(Map<EdgeKey, List<int[]>> edges, EdgeKey key, int face, int height) {
		for (int[] other : edges.getOrDefault(key, List.of())) {
			if (other[0] != face) {
				return other[1];
			}
		}
		return height;
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
