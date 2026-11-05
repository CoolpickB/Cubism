package dev.cubeworld.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.renderer.BindGroupLayouts;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.cubeworld.seam.CubeTime;
import dev.cubeworld.seam.CubeTimeLevel;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

/**
 * The air of the cube: a shell around the rounded cube, thick at the ground and thin at the build limit. The shader
 * walks each view ray through the shell. Every bit of air on the ray has the vanilla sky and fog color of the time
 * that the sun gives it there, so the sky over a neighbor face shows the day or night of that face. Where the ray
 * leaves the shell, space with its stars shows through.
 *
 * <p>Vanilla tints the fog with the sunrise and sunset glow when the camera looks toward the sun. That look changes
 * with the face the camera is on, so here each bit of air gets the glow toward the sun on its own horizon.
 *
 * <p>The colors come from a table of the vanilla timeline over one day, sampled each frame at the camera, so biome
 * and weather change them as in vanilla. All state here is used on the render thread only.
 */
public final class CubeSky {
	private static final int TABLE_SIZE = 96;

	private static final RenderPipeline.Snippet SNIPPET = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_SNIPPET)
		.withVertexShader(Identifier.fromNamespaceAndPath("cubeworld", "core/air"))
		.withFragmentShader(Identifier.fromNamespaceAndPath("cubeworld", "core/air"))
		.withBindGroupLayout(BindGroupLayouts.SAMPLER0)
		.withVertexBinding(0, DefaultVertexFormat.POSITION)
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.withCull(false)
		.buildSnippet();
	/** Black space under the stars. */
	private static final RenderPipeline SPACE = RenderPipelines.register(
		RenderPipeline.builder(SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("cubeworld", "pipeline/space"))
			.withShaderDefine("SPACE")
			.withColorTargetState(ColorTargetState.DEFAULT)
			.build()
	);
	/** The air over the stars. Its alpha is how much of the starlight the air hides. */
	private static final RenderPipeline AIR = RenderPipelines.register(
		RenderPipeline.builder(SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("cubeworld", "pipeline/air"))
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
			.build()
	);

	private static @Nullable GpuBuffer box;
	private static @Nullable DynamicTexture table;
	private static boolean active;
	private static final Matrix4f FRAME = new Matrix4f();
	private static final Vector3f SUN = new Vector3f();
	private static float radius;
	private static float starMax;
	private static float moonBrightness;

	private static @Nullable Matrix3f celestial;
	private static final Matrix3f TILT = new Matrix3f();
	private static final Vector3f SURFACE_UP = new Vector3f(0.0F, 1.0F, 0.0F);
	private static final Matrix4f CUBE_FROM_FEET = new Matrix4f();
	private static float core;
	private static float centerY;

	private CubeSky() {}

	/** The sky frame in the camera face from the last extract, whose columns are the side, the sun and the axis of the sun. */
	public static @Nullable Matrix3f celestial() {
		return celestial;
	}

	/**
	 * The sky frame for shader packs that place the sun around +Y of the face frame. That up turns a quarter turn at an
	 * edge, so the frame is tilted until the surface normal under the camera is +Y. The elevation of the sun then agrees
	 * with the local time, but the sun turns in view where the camera crosses an edge.
	 */
	public static @Nullable Matrix3f tiltedCelestial() {
		return celestial == null ? null : new Matrix3f(TILT).mul(celestial);
	}

	public static void setCelestial(@Nullable Matrix3f frame) {
		celestial = frame;
	}

	/** The unit surface normal under the camera, in the face frame of the camera. */
	public static Vector3f surfaceUp() {
		return SURFACE_UP;
	}

	/** Takes a position relative to the camera in the face frame of the camera to cube coordinates about the center. */
	public static Matrix4f cubeFromFeet() {
		return CUBE_FROM_FEET;
	}

	/** Half the size of the box whose rounded shells are the levels of equal height over the cube. */
	public static float core() {
		return core;
	}

	public static float centerY() {
		return centerY;
	}

	/** Loads the class, so that the pipelines are registered before the shaders compile. */
	public static void init() {}

	public static boolean active() {
		return active;
	}

	/** Brightness of the moon: full at night, faint on a day sky. */
	public static float moonBrightness() {
		return moonBrightness;
	}

	public static float starMax() {
		return starMax;
	}

	/**
	 * Samples the colors of the day and the position of the camera and the sun for the next frame. The local time and
	 * the up come from the body center, the same point as the attribute probe of the camera (see CameraMixin).
	 */
	public static void extract(ClientLevel level, Vec3 camera, Vec3 body, double ticks) {
		SeamLayout layout = SeamLayout.of(level);
		CubeTime.State time = ((CubeTimeLevel) level).cubeworld$cubeTime();
		active = layout != null && time != null;
		if (!active) {
			return;
		}
		DynamicTexture texture = table;
		if (texture == null) {
			texture = new DynamicTexture(() -> "Cube sky colors", TABLE_SIZE, 3, false);
			table = texture;
		}
		float maxStars = 0.0F;
		float maxSky = 1.0E-4F;
		for (int i = 0; i < TABLE_SIZE; i++) {
			long sampleTicks = (long) i * CubeTime.DAY_TICKS / TABLE_SIZE;
			Vector3fc sky = time.sampleAt(sampleTicks, () -> level.environmentAttributes().getValue(EnvironmentAttributes.SKY_COLOR, camera, null));
			Vector3fc fog = time.sampleAt(sampleTicks, () -> level.environmentAttributes().getValue(EnvironmentAttributes.FOG_COLOR, camera, null));
			float stars = time.sampleAt(sampleTicks, () -> level.environmentAttributes().getValue(EnvironmentAttributes.STAR_BRIGHTNESS, camera, null));
			Vector4fc glow = time.sampleAt(sampleTicks, () -> level.environmentAttributes().getValue(EnvironmentAttributes.SUNRISE_SUNSET_COLOR, camera, null));
			texture.getPixels().setPixel(i, 0, ARGB.colorFromFloat(stars, sky.x(), sky.y(), sky.z()));
			texture.getPixels().setPixel(i, 1, ARGB.colorFromFloat(1.0F, fog.x(), fog.y(), fog.z()));
			texture.getPixels().setPixel(i, 2, ARGB.colorFromFloat(glow.w(), glow.x(), glow.y(), glow.z()));
			maxStars = Math.max(maxStars, stars);
			maxSky = Math.max(maxSky, Math.max(sky.x(), Math.max(sky.y(), sky.z())));
		}
		texture.upload();
		starMax = Math.max(maxStars, 0.01F);
		radius = layout.radius;
		double[] up = CubeTime.surfaceNormal(layout, body.x, body.y, body.z);
		SURFACE_UP.set((float) up[0], (float) up[1], (float) up[2]).normalize();
		TILT.rotation(new org.joml.Quaternionf().rotationTo(SURFACE_UP, new Vector3f(0.0F, 1.0F, 0.0F)));
		core = layout.radius - CubeTime.EDGE_BLEND;
		centerY = layout.centerY;

		int face = layout.faceIndexAt(Mth.floor(camera.x));
		int[] m = layout.rotation(face);
		FRAME.identity();
		for (int row = 0; row < 3; row++) {
			for (int column = 0; column < 3; column++) {
				FRAME.setRowColumn(row, column, m[row * 3 + column]);
			}
		}
		// About the center of the cube, which is the center of a block (see CubeTime.surfaceNormal).
		double x = camera.x - layout.offsetX(face) - 0.5;
		double y = camera.y - layout.centerY - 0.5;
		double z = camera.z - 0.5;
		// The fog of vanilla at the camera follows the local time there, which the shader needs to match the horizon.
		long cameraTicks = Math.floorMod((long) ticks + CubeTime.offsetTicks(layout, body.x, body.y, body.z), CubeTime.DAY_TICKS);
		FRAME.setColumn(3, new Vector4f(
			(float) (m[0] * x + m[1] * y + m[2] * z),
			(float) (m[3] * x + m[4] * y + m[5] * z),
			(float) (m[6] * x + m[7] * y + m[8] * z),
			cameraTicks
		));
		CUBE_FROM_FEET.set(FRAME).m33(1.0F);
		double[] sun = CubeTime.sunDirection(ticks);
		SUN.set(sun[0], sun[1], sun[2]);

		Vector3fc cameraSky = level.environmentAttributes().getValue(EnvironmentAttributes.SKY_COLOR, camera, null);
		float daylight = Math.max(cameraSky.x(), Math.max(cameraSky.y(), cameraSky.z())) / maxSky;
		moonBrightness = 1.0F - 0.85F * Mth.clamp(daylight, 0.0F, 1.0F);
	}

	/** Draws black space, for the stars that the caller draws next. */
	public static void renderSpace(RenderPass renderPass) {
		draw(renderPass, SPACE, "Cube space");
	}

	/** Draws the air over the space and stars. */
	public static void renderAir(RenderPass renderPass) {
		draw(renderPass, AIR, "Cube air");
	}

	private static void draw(RenderPass renderPass, RenderPipeline pipeline, String label) {
		DynamicTexture texture = table;
		if (texture == null) {
			return;
		}
		GpuBuffer vertices = box;
		if (vertices == null) {
			vertices = buildBox();
			box = vertices;
		}
		var transforms = RenderSystem.getDynamicUniforms().writeTransform(
			RenderSystem.getModelViewMatrixCopy(), new Vector4f(radius, starMax, 0.0F, 0.0F), SUN, FRAME
		);
		renderPass.pushDebugGroup(() -> label);
		renderPass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
		RenderSystem.bindDefaultUniforms(renderPass);
		renderPass.setUniform("DynamicTransforms", transforms);
		renderPass.setUniform("Sampler0", texture.getTextureView(), RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
		renderPass.setVertexBuffer(0, vertices.slice());
		renderPass.draw(36, 1, 0, 0);
		renderPass.popDebugGroup();
	}

	/** A box around the camera. The shader turns each pixel of it into a view ray. */
	private static GpuBuffer buildBox() {
		try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(36 * DefaultVertexFormat.POSITION.getVertexSize())) {
			BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION);
			float s = 50.0F;
			for (int axis = 0; axis < 3; axis++) {
				for (int sign = -1; sign <= 1; sign += 2) {
					Vector3f[] corners = new Vector3f[4];
					for (int c = 0; c < 4; c++) {
						float u = (c == 1 || c == 2) ? s : -s;
						float v = c >= 2 ? s : -s;
						float[] p = new float[3];
						p[axis] = sign * s;
						p[(axis + 1) % 3] = u;
						p[(axis + 2) % 3] = v;
						corners[c] = new Vector3f(p[0], p[1], p[2]);
					}
					for (int index : new int[] {0, 1, 2, 0, 2, 3}) {
						builder.addVertex(corners[index].x, corners[index].y, corners[index].z);
					}
				}
			}
			try (MeshData mesh = builder.buildOrThrow()) {
				return RenderSystem.getDevice().createBuffer(() -> "Cube sky box", 32, mesh.vertexBuffer());
			}
		}
	}
}
