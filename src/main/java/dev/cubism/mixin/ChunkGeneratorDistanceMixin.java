package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jspecify.annotations.Nullable;
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
	/** The random spread search picks its chunks in a static method without the generator, which reads the cube of this scope. */
	@WrapMethod(method = "findNearestMapStructure")
	private @Nullable Pair<BlockPos, Holder<Structure>> cubism$searchInCube(
		ServerLevel level, HolderSet<Structure> wanted, BlockPos pos, int maxSearchRadius, boolean createReference,
		Operation<Pair<BlockPos, Holder<Structure>>> original
	) {
		return SeamLayout.withActive(SeamLayout.of((ChunkGenerator) (Object) this), () -> original.call(level, wanted, pos, maxSearchRadius, createReference));
	}

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
		SeamLayout layout = SeamLayout.of((ChunkGenerator) (Object) this);
		if (layout != null) {
			double length = SeamPaths.distance(layout, Vec3.atCenterOf(self), Vec3.atCenterOf(other));
			if (length >= 0.0) {
				return length * length;
			}
		}
		return original.call(self, other);
	}
}
