package dev.cubism.client.compat.sodium;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cubism.client.SeamClient;
import dev.cubism.client.render.FaceLightmaps;
import dev.cubism.worldgen.SeamLayout;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Binds what the patched terrain shader needs for one draw (see {@link TerrainShaderPatch}): the lightmaps of the four
 * neighbor faces, and in the dynamic transforms the camera in the frame of the face and the blend width. The width is
 * the one the local time uses at an edge (see CubeTime.EDGE_BLEND).
 */
public final class FaceLightBlend {
	private static final float WIDTH = 32.0F;

	private FaceLightBlend() {}

	public static void bind(RenderPass pass, GpuTextureView own, GpuSampler sampler, double cameraX, double cameraY, double cameraZ, boolean blend) {
		SeamLayout layout = SeamClient.layout;
		int face = SeamFaces.currentFace();
		boolean on = blend && layout != null && FaceLightmaps.ready() && face >= 0 && face < SeamLayout.FACES.length;
		GpuTextureView[] others = on ? FaceLightmaps.neighbors(face, own) : new GpuTextureView[] {own, own, own, own};
		for (int side = 0; side < 4; side++) {
			pass.setUniform("u_LightTex" + (side + 1), others[side], sampler);
		}
		Matrix4f frame = new Matrix4f();
		if (on) {
			frame.m00((float) (cameraX - layout.offsetX(face))).m01((float) (cameraY - layout.centerY)).m02((float) cameraZ).m03(WIDTH);
		}
		pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(), new Vector4f(on ? 1.0F : 0.0F, 0.0F, 0.0F, 0.0F), new Vector3f(), frame));
	}
}
