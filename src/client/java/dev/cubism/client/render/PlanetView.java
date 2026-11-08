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

	private static byte @Nullable [] data;
	private static @Nullable GpuBuffer mesh;
	private static int vertices;
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

	private static void build(SeamLayout layout, byte[] bytes) {
		int cells = PlanetMap.CELLS;
		int nodes = cells + 1;
		int faces = SeamLayout.FACES.length;
		if (bytes.length != faces * nodes * nodes * 5) {
			return;
		}
		ByteBuffer in = ByteBuffer.wrap(bytes);
		float[][] position = new float[faces * nodes * nodes][];
		int[] color = new int[position.length];
		double rounding = layout.edgeRounding();
		double core = layout.radius - rounding;
		for (int face = 0; face < faces; face++) {
			int[] m = layout.rotation(face);
			for (int i = 0; i < nodes; i++) {
				double u = -layout.radius + 2.0 * layout.radius * i / cells;
				for (int j = 0; j < nodes; j++) {
					double v = -layout.radius + 2.0 * layout.radius * j / cells;
					int height = in.getShort();
					int rgb = (in.get() & 0xFF) << 16 | (in.get() & 0xFF) << 8 | (in.get() & 0xFF);
					double qx = Mth.clamp(u, -core, core);
					double qz = Mth.clamp(v, -core, core);
					double nx = u - qx;
					double ny = layout.radius - core;
					double nz = v - qz;
					double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
					double out = rounding + height;
					double x = qx + nx / length * out;
					double y = core + ny / length * out;
					double z = qz + nz / length * out;
					int index = (face * nodes + i) * nodes + j;
					position[index] = new float[] {
						(float) (m[0] * x + m[1] * y + m[2] * z),
						(float) (m[3] * x + m[4] * y + m[5] * z),
						(float) (m[6] * x + m[7] * y + m[8] * z)
					};
					color[index] = 0xFF000000 | rgb;
				}
			}
		}
		int count = faces * cells * cells * 6;
		try (ByteBufferBuilder buffer = ByteBufferBuilder.exactlySized(count * DefaultVertexFormat.POSITION_COLOR.getVertexSize())) {
			BufferBuilder builder = new BufferBuilder(buffer, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
			for (int face = 0; face < faces; face++) {
				for (int i = 0; i < cells; i++) {
					for (int j = 0; j < cells; j++) {
						int a = (face * nodes + i) * nodes + j;
						int b = a + nodes;
						// Counterclockwise seen from outside: u runs along X of the face, v along Z, and the outside is +Y.
						for (int corner : new int[] {a, a + 1, b, b, a + 1, b + 1}) {
							float[] p = position[corner];
							builder.addVertex(p[0], p[1], p[2]).setColor(color[corner]);
						}
					}
				}
			}
			try (MeshData data = builder.buildOrThrow()) {
				mesh = RenderSystem.getDevice().createBuffer(() -> "Cube planet", 32, data.vertexBuffer());
				vertices = count;
			}
		}
	}
}
