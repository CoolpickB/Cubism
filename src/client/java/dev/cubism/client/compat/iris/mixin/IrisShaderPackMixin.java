package dev.cubism.client.compat.iris.mixin;

import dev.cubism.client.render.PlanetView;
import java.nio.file.Path;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Binds the columns of the planet in every program of a pack as {@code cubism_planetTex}. Iris looks up a texture of a
 * namespace each time it binds it, so the one that PlanetView registers works when it comes after the pack loads.
 */
@Mixin(value = ShaderPack.class, remap = false)
public abstract class IrisShaderPackMixin {
	@Inject(method = "readProperties", at = @At("RETURN"), cancellable = true)
	private static void cubism$planetTexture(Path root, String name, CallbackInfoReturnable<String> cir) {
		String properties = cir.getReturnValue();
		if (properties != null && name.equals("shaders.properties")) {
			cir.setReturnValue(properties + "\ncustomTexture.cubism_planetTex=" + PlanetView.TEXTURE + "\n");
		}
	}
}
