package dev.cubism.client.compat.sodium;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/**
 * The terrain vertex shader of Sodium reads its color from one lightmap. This reads it from the lightmap of the face and
 * from the four faces across its edges, with a weight that grows toward the diagonal to each of them and is half at the
 * diagonal, so both faces of an edge get the same light there. The camera in the frame of the face is in
 * {@code TextureMat[0].xyz} and the blend width in {@code TextureMat[0].w} of the CubismLightBlend block (see {@link FaceLightBlend}). Added to the
 * vertex position it gives the distance to each diagonal, as the wedge of a face is where the height over the center is at
 * least the distance from the center in x and in z.
 */
public final class TerrainShaderPatch {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String SAMPLER = "uniform sampler2D u_LightTex; // The light map texture sampler";
	private static final String LIGHT = "    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);";

	private TerrainShaderPatch() {}

	public static String patch(Identifier location, String source) {
		if (source == null || !location.getNamespace().equals("sodium") || !location.getPath().equals("shaders/blocks/block_layer_opaque.vsh")) {
			return source;
		}
		String text = source.replace("\r\n", "\n");
		if (!text.contains(SAMPLER) || !text.contains(LIGHT)) {
			LOGGER.warn("Cubism could not patch the terrain shader of Sodium: the light will step at the edges of the cube");
			return source;
		}
		text = text.replace(SAMPLER, SAMPLER + "\n"
			+ "uniform sampler2D u_LightTex1;\n"
			+ "uniform sampler2D u_LightTex2;\n"
			+ "uniform sampler2D u_LightTex3;\n"
			+ "uniform sampler2D u_LightTex4;\n"
			// The layout of the dynamic transforms under a name of its own. A shader pack draws the terrain with a program
			// that reads the real dynamic transforms, which this binding must not replace.
			+ "layout(std140) uniform CubismLightBlend {\n"
			+ "    mat4 CubismModelView;\n"
			+ "    mat4 TextureMat;\n"
			+ "    vec4 ColorModulator;\n"
			+ "    vec3 CubismModelOffset;\n"
			+ "};");
		return text.replace(LIGHT, String.join("\n",
			"    vec4 cubismLight = texture(u_LightTex, _vert_tex_light_coord);",
			"    if (ColorModulator.x > 0.0) {",
			"        vec3 cubismLocal = TextureMat[0].xyz + position;",
			"        vec4 cubismWeights = 0.5 * (1.0 - clamp(vec4(",
			"            cubismLocal.y - cubismLocal.x, cubismLocal.y + cubismLocal.x,",
			"            cubismLocal.y - cubismLocal.z, cubismLocal.y + cubismLocal.z) / TextureMat[0].w, 0.0, 1.0));",
			"        float cubismTotal = cubismWeights.x + cubismWeights.y + cubismWeights.z + cubismWeights.w;",
			"        if (cubismTotal > 0.0) {",
			"            vec4 cubismOther = texture(u_LightTex1, _vert_tex_light_coord) * cubismWeights.x",
			"                + texture(u_LightTex2, _vert_tex_light_coord) * cubismWeights.y",
			"                + texture(u_LightTex3, _vert_tex_light_coord) * cubismWeights.z",
			"                + texture(u_LightTex4, _vert_tex_light_coord) * cubismWeights.w;",
			"            cubismLight = mix(cubismLight, cubismOther / cubismTotal, min(cubismTotal, 1.0));",
			"        }",
			"    }",
			"    v_Color = _vert_color * cubismLight;"));
	}
}
