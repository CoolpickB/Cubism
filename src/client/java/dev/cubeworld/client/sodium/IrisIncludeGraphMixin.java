package dev.cubeworld.client.sodium;

import com.google.common.collect.ImmutableList;
import dev.cubeworld.client.sodiumseam.CubeShaderPatch;
import java.nio.file.Path;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hands every file of a shader pack to {@link CubeShaderPatch} as Iris reads it. */
@Mixin(value = IncludeGraph.class, remap = false)
public abstract class IrisIncludeGraphMixin {
	@Inject(method = "readFile", at = @At("RETURN"), cancellable = true)
	private static void cubeworld$patch(Path path, CallbackInfoReturnable<String> cir) {
		cir.setReturnValue(CubeShaderPatch.patch(path, cir.getReturnValue()));
	}

	@Inject(method = "<init>(Ljava/nio/file/Path;Lcom/google/common/collect/ImmutableList;Z)V", at = @At("RETURN"))
	private void cubeworld$finish(Path root, ImmutableList<AbsolutePackPath> startingPaths, boolean isZip, CallbackInfo ci) {
		CubeShaderPatch.finishPack();
	}
}
