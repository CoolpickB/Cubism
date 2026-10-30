package dev.cubeworld.mixin;

import dev.cubeworld.worldgen.SeamDensity;
import dev.cubeworld.worldgen.SeamLayout;
import dev.cubeworld.worldgen.SeamRandomState;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctionCompiler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every density sampler of a level goes through {@link RandomState#getSampler}: terrain, aquifers, surface rules
 * and biomes. In a seam level, this compiles the seam version of the function instead.
 */
@Mixin(RandomState.class)
public abstract class RandomStateMixin implements SeamRandomState {
	@Shadow @Final private DensityFunctionCompiler densityFunctionCompiler;

	@Unique private @Nullable SeamDensity seamDensity;
	@Unique private final Map<DensityFunction, DensitySampler> seamSamplers = new ConcurrentHashMap<>();

	@Override
	public void cubeworld$enableSeams(SeamLayout layout) {
		this.seamDensity = new SeamDensity(layout);
		this.seamSamplers.clear();
	}

	@Inject(method = "getSampler", at = @At("HEAD"), cancellable = true)
	private void cubeworld$seamSampler(DensityFunction function, CallbackInfoReturnable<DensitySampler> cir) {
		SeamDensity seams = this.seamDensity;
		if (seams != null) {
			cir.setReturnValue(this.seamSamplers.computeIfAbsent(function, f -> this.densityFunctionCompiler.getSampler(seams.rewrite(f))));
		}
	}
}
