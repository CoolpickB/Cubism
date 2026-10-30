package dev.cubeworld.mixin;

import dev.cubeworld.worldgen.OrientedOrePlacer;
import dev.cubeworld.worldgen.OrientedWorldGenLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla ores write into chunk sections by world coordinates, which skips the face level. */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {
	@Inject(method = "doPlace", at = @At("HEAD"), cancellable = true)
	private void cubeworld$placeInFrame(
		WorldGenLevel level, RandomSource random, double x0, double x1, double z0, double z1, double y0, double y1,
		int xStart, int yStart, int zStart, int sizeXZ, int sizeY, CallbackInfoReturnable<Boolean> cir
	) {
		if (level instanceof OrientedWorldGenLevel) {
			cir.setReturnValue(OrientedOrePlacer.doPlace(
				(OreFeature) (Object) this, level, random, x0, x1, z0, z1, y0, y1, xStart, yStart, zStart, sizeXZ, sizeY));
		}
	}
}
