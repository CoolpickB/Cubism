package dev.cubism.mixin.entity;

import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMixin extends Entity {
	protected ExperienceOrbMixin(EntityType<?> type, Level level) {
		super(type, level);
	}

	/**
	 * An orb finds a player of another face region by the distance across the seam, then pulls toward the stored
	 * position of the player, thousands of blocks away. The pull grows with the square of that distance, and the orb
	 * moves so far in one tick that its collision check loads chunks for seconds.
	 */
	@ModifyVariable(method = "followNearbyPlayer", at = @At(value = "STORE", ordinal = 0))
	private Vec3 cubism$pullAcrossSeam(Vec3 delta) {
		SeamLayout layout = SeamLayout.of(this.level());
		if (layout == null) {
			return delta;
		}
		Vec3 shown = SeamInteraction.intoRegionOf(layout, this.position().add(delta), this.getX(), this.getZ());
		return shown.subtract(this.position());
	}
}
