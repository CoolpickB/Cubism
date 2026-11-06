package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.CubismMod;
import dev.cubism.worldgen.SeamLayout;
import dev.cubism.worldgen.SeamPaths;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * "Nearest" structure for an eye of ender or a locate: the distance over the surface of the cube, not the distance
 * between storage positions, which put the faces side by side. Only the stronghold rings and the final comparison of
 * the random spread search are ranked this way. The spread search still scans in rings around the storage position.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorDistanceMixin {
	@WrapOperation(
		method = "findNearestMapStructure",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;distSqr(Lnet/minecraft/core/Vec3i;)D")
	)
	private double cubism$surfaceDistance(net.minecraft.core.BlockPos self, Vec3i other, Operation<Double> original) {
		return this.cubism$distance(self, other, original);
	}

	@WrapOperation(
		method = "getNearestGeneratedStructure",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos$MutableBlockPos;distSqr(Lnet/minecraft/core/Vec3i;)D"),
		require = 0
	)
	private double cubism$surfaceDistanceOfRing(net.minecraft.core.BlockPos.MutableBlockPos self, Vec3i other, Operation<Double> original) {
		return this.cubism$distance(self, other, original);
	}

	private double cubism$distance(Vec3i self, Vec3i other, Operation<Double> original) {
		SeamLayout layout = SeamLayout.active();
		if (layout != null && (Object) this instanceof NoiseBasedChunkGenerator generator && generator.generatorSettings().is(CubismMod.SEAM_SETTINGS)) {
			double length = SeamPaths.distance(layout, Vec3.atCenterOf(self), Vec3.atCenterOf(other));
			if (length >= 0.0) {
				return length * length;
			}
		}
		return original.call(self, other);
	}
}
