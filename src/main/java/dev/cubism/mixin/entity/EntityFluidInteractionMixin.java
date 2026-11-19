package dev.cubism.mixin.entity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.cube.SeamLayout;
import net.minecraft.world.entity.EntityFluidInteraction;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla skips the fluid test when no section under the entity holds a fluid. Outside its wedge, a section holds
 * only cleared air, but the fluid reads go across the seam. The test runs whenever the box reaches out of the wedge.
 */
@Mixin(EntityFluidInteraction.class)
public abstract class EntityFluidInteractionMixin {
	@WrapOperation(
		method = "update",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/EntityFluidInteraction;hasFluidAndLoaded(Lnet/minecraft/world/level/Level;IIIIII)Z")
	)
	private boolean cubism$fluidAcrossSeam(Level level, int x0, int y0, int z0, int x1, int y1, int z1, Operation<Boolean> original) {
		if (original.call(level, x0, y0, z0, x1, y1, z1)) {
			return true;
		}
		SeamLayout layout = SeamLayout.of(level);
		if (layout == null) {
			return false;
		}
		// A wedge is convex, so the box is in it when all its corners are.
		for (int x : new int[] {x0, x1}) {
			for (int y : new int[] {y0, y1}) {
				for (int z : new int[] {z0, z1}) {
					if (!layout.insideWedge(x, y, z)) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
