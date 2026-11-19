package dev.cubism.mixin.block;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Map;

/**
 * An explosion next to a seam hurts and pushes the entities that show across it. Positions of those entities move
 * into the region of the explosion, and the push turns back into the region of each entity. Blocks across the seam
 * break through the server reads and writes of {@link LevelMixin}.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow @Final private ServerLevel level;
	@Shadow @Final private Vec3 center;
	@Shadow @Final private @Nullable Entity source;

	@WrapOperation(
		method = "hurtEntities",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;")
	)
	private List<Entity> cubism$entitiesAcrossSeams(ServerLevel level, @Nullable Entity except, AABB box, Operation<List<Entity>> original) {
		SeamLayout layout = SeamLayout.of(level);
		return layout == null ? original.call(level, except, box)
			: SeamInteraction.acrossSeams(layout, this.center.x, this.center.z, box, area -> original.call(level, except, area));
	}

	@ModifyExpressionValue(
		method = "hurtEntities",
		at = {
			@At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;position()Lnet/minecraft/world/phys/Vec3;"),
			@At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getEyePosition()Lnet/minecraft/world/phys/Vec3;")
		}
	)
	private Vec3 cubism$originInExplosionRegion(Vec3 origin) {
		SeamLayout layout = SeamLayout.of(this.level);
		return layout == null ? origin : SeamInteraction.intoRegionOf(layout, origin, this.center.x, this.center.z);
	}

	@WrapOperation(method = "hurtEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ServerExplosion;getSeenPercent(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/entity/Entity;)F"))
	private float cubism$exposureInEntityRegion(Vec3 center, Entity entity, Operation<Float> original) {
		SeamLayout layout = SeamLayout.of(this.level);
		return original.call(layout == null ? center : SeamInteraction.intoRegionOf(layout, center, entity.getX(), entity.getZ()), entity);
	}

	// The rays from the entity to the center pass the diagonal, where the blocks are read from their owner face.
	@WrapOperation(method = "getSeenPercent", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
	private static BlockHitResult cubism$exposureRayAcrossSeam(Level level, ClipContext context, Operation<BlockHitResult> original) {
		SeamLayout layout = SeamLayout.of(level);
		return layout == null ? original.call(level, context) : SeamInteraction.clip(level, layout, context);
	}

	@WrapOperation(method = "hurtEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;pushFromExplosion(Lnet/minecraft/world/phys/Vec3;)V"))
	private void cubism$pushInEntityRegion(Entity entity, Vec3 knockback, Operation<Void> original) {
		original.call(entity, this.cubism$turnInto(entity, knockback));
	}

	@WrapOperation(method = "hurtEntities", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object cubism$playerKnockbackInPlayerRegion(Map<Object, Object> hitPlayers, Object player, Object knockback, Operation<Object> original) {
		return original.call(hitPlayers, player, this.cubism$turnInto((Entity) player, (Vec3) knockback));
	}

	@Unique
	private Vec3 cubism$turnInto(Entity entity, Vec3 direction) {
		SeamLayout layout = SeamLayout.of(this.level);
		return layout == null ? direction : SeamInteraction.turnIntoRegionOf(layout, direction, this.center.x, this.center.z, entity.getX(), entity.getZ());
	}
}
