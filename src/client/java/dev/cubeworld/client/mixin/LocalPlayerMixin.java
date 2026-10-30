package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends LivingEntity {
	@Unique private Vec3 cubeworld$positionBeforeMove = Vec3.ZERO;

	protected LocalPlayerMixin(EntityType<? extends LivingEntity> type, Level level) {
		super(type, level);
	}

	@Inject(method = "move", at = @At("HEAD"))
	private void cubeworld$rememberPosition(MoverType moverType, Vec3 delta, CallbackInfo ci) {
		this.cubeworld$positionBeforeMove = this.position();
	}

	// View bobbing and step sounds use the walked distance, which must be horizontal in the local frame.
	@ModifyExpressionValue(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;length(FF)F"))
	private float cubeworld$localWalkedDistance(float worldLength) {
		Direction frame = ((GravityEntityAccess) this).cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			return worldLength;
		}
		Vec3 moved = CubePlanetMath.worldToLocal(this.position().subtract(this.cubeworld$positionBeforeMove), frame);
		return (float) moved.horizontalDistance();
	}

	// Auto-jump probes the world with Y-up boxes.
	@Inject(method = "canAutoJump", at = @At("HEAD"), cancellable = true)
	private void cubeworld$noAutoJumpWhenTurned(CallbackInfoReturnable<Boolean> cir) {
		if (((GravityEntityAccess) this).cubeworld$getFrame() != Direction.DOWN) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * The crosshair also finds entities stored in a neighbor face region where the seam shows them. The ray moves into
	 * each neighbor region, and a hit there moves back to where it shows.
	 */
	@WrapOperation(
		method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;")
	)
	private static @Nullable EntityHitResult cubeworld$pickAcrossSeams(
		Entity camera, Vec3 from, Vec3 to, AABB box, Predicate<Entity> matching, double maxDistanceSq, Operation<EntityHitResult> original
	) {
		EntityHitResult nearest = original.call(camera, from, to, box, matching, maxDistanceSq);
		SeamLayout layout = SeamLayout.of(camera.level());
		int face = layout == null ? -1 : SeamInteraction.regionFace(layout, from.x, from.z);
		if (face < 0) {
			return nearest;
		}
		double nearestSq = nearest == null ? maxDistanceSq : nearest.getLocation().distanceToSqr(from);
		for (int other = 0; other < SeamLayout.FACES.length; other++) {
			int[] there = layout.motion(face, other);
			// The opposite face shares no seam.
			if (other == face || there[4] == -1) {
				continue;
			}
			Vec3 movedFrom = SeamInteraction.move(there, from);
			Vec3 movedTo = SeamInteraction.move(there, to);
			EntityHitResult hit = original.call(camera, movedFrom, movedTo, new AABB(movedFrom, movedTo).inflate(1.0), matching, nearestSq);
			if (hit != null) {
				Vec3 shown = SeamInteraction.move(layout.motion(other, face), hit.getLocation());
				double distanceSq = shown.distanceToSqr(from);
				if (distanceSq < nearestSq) {
					nearest = new EntityHitResult(hit.getEntity(), shown);
					nearestSq = distanceSq;
				}
			}
		}
		return nearest;
	}
}
