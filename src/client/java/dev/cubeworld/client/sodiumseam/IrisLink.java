package dev.cubeworld.client.sodiumseam;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.irisshaders.iris.mixinterface.ShadowRenderListAccess;
import net.irisshaders.iris.shadows.ShadowRenderingState;

/**
 * Iris swaps the render lists and the uniform buffer of the manager that Sodium holds into a shadow set while it renders
 * shadows. The managers of the other faces need the same swap, or the shadow pass would overwrite their lists.
 * Only load this class when Iris is present.
 */
final class IrisLink {
	private IrisLink() {}

	static boolean shadowsActive() {
		return ShadowRenderingState.areShadowsCurrentlyBeingRendered();
	}

	/** Both calls of Iris do nothing when the manager is already in the wanted state. */
	static void setShadowScope(RenderSectionManager[] managers, UniformBufferManager[] uniforms, boolean shadow) {
		for (int f = 0; f < managers.length; f++) {
			if (managers[f] == null) {
				continue;
			}
			ShadowRenderListAccess lists = (ShadowRenderListAccess) managers[f];
			ShadowRenderListAccess buffer = (ShadowRenderListAccess) uniforms[f];
			if (shadow) {
				lists.iris$beginShadowRenderListScope();
				buffer.iris$beginShadowRenderListScope();
			} else {
				lists.iris$endShadowRenderListScope();
				buffer.iris$endShadowRenderListScope();
			}
		}
	}
}
