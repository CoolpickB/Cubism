package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.client.CubeFlight;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.player.LocalPlayer;
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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends LivingEntity {
	protected LocalPlayerMixin(EntityType<? extends LivingEntity> type, Level level) {
		super(type, level);
	}

	/** Inside the air frame of {@link CubeFlight}, the move of vanilla still collides in the face frame. */
	@WrapMethod(method = "move")
	private void cubeworld$moveFromAirFrame(MoverType moverType, Vec3 delta, Operation<Void> original) {
		if (CubeFlight.inFrame()) {
			CubeFlight.moveInFaceFrame((LocalPlayer) (Object) this, delta, turned -> original.call(moverType, turned));
		} else {
			original.call(moverType, delta);
		}
	}

	// Creative flight goes up and down along the up of the air frame.
	@WrapOperation(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 cubeworld$flyUpInAirFrame(Vec3 velocity, double x, double y, double z, Operation<Vec3> original) {
		Vec3 change = CubeFlight.upInFrame((LocalPlayer) (Object) this, new Vec3(x, y, z));
		return original.call(velocity, change.x, change.y, change.z);
	}

	@Inject(method = "aiStep", at = @At("TAIL"))
	private void cubeworld$tickFlight(CallbackInfo ci) {
		CubeFlight.tick((LocalPlayer) (Object) this);
	}

	// The pick starts at the eye that the camera shows, which stands along the air frame in flight.
	@WrapOperation(
		method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;")
	)
	private static Vec3 cubeworld$pickFromShownEye(Entity camera, float partialTicks, Operation<Vec3> original) {
		return CubeFlight.eyePosition(camera, partialTicks, original.call(camera, partialTicks));
	}

	@Override
	public void turn(double xo, double yo) {
		if (!CubeFlight.turn((LocalPlayer) (Object) this, xo, yo)) {
			super.turn(xo, yo);
		}
	}

	@Override
	public void moveRelative(float speed, Vec3 input) {
		this.setDeltaMovement(this.getDeltaMovement().add(getInputVector(input, speed, CubeFlight.moveYaw((LocalPlayer) (Object) this))));
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
