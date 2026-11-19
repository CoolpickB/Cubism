package dev.cubism.mixin.entity;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.gravity.GravityEntityAccess;
import dev.cubism.gravity.GravityLock;
import dev.cubism.seam.SeamCrossing;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.cube.SeamLayout;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(Entity.class)
public abstract class EntityMixin implements GravityEntityAccess {
	/** The same gap as for living entities: no edge pull with ground this close along it. */
	@Unique private static final double EDGE_GRAVITY_GROUND_GAP = 3.0;
	/** Room under a step down for the fall of the tick, so that a drop of exactly the step height still counts. */
	@Unique private static final double STEP_DOWN_MARGIN = 0.1;

	@Shadow private Level level;
	@Shadow private Vec3 position;
	@Shadow public boolean noPhysics;

	@Shadow public abstract AABB getBoundingBox();
	@Shadow public abstract Vec3 getDeltaMovement();
	@Shadow public abstract void setDeltaMovement(Vec3 movement);
	@Shadow public abstract boolean onGround();
	@Shadow public abstract float maxUpStep();
	@Shadow public abstract double getGravity();
	@Shadow public abstract void setPos(double x, double y, double z);
	@Shadow private Vec3 collide(Vec3 movement) { return movement; }
	@Shadow public abstract boolean isInWater();
	@Shadow public abstract boolean isPassenger();
	@Shadow public abstract void setOnGround(boolean onGround);

	/** Whether the entity stood on the ground when its current move started. */
	@Unique private boolean cubism$wasOnGround;
	/** Face region where the last fall started, as {@code LivingEntityMixin} keeps it for living entities. */
	@Unique private int cubism$airStartFace = -1;
	@Unique private int cubism$spaceFace = -1;

	@Override
	public int cubism$spaceFace() {
		return this.cubism$spaceFace;
	}

	@Override
	public void cubism$setSpaceFace(int face) {
		this.cubism$spaceFace = face;
	}

	/**
	 * Items, projectiles, falling blocks and other entities without a living body take their pull from here. Over an
	 * edge they get the same pull as living entities in {@code LivingEntityMixin}: once they pass into the region of
	 * another face in the air, they fall along {@code CubeGravity.airGravity}. Without it an arrow bends by a right
	 * angle at the diagonal.
	 */
	@Inject(method = "applyGravity", at = @At("HEAD"), cancellable = true)
	private void cubism$edgeGravity(CallbackInfo ci) {
		SeamLayout layout = SeamLayout.of(this.level);
		Vec3 space = layout == null ? null : SeamCrossing.spacePull(layout, (Entity) (Object) this);
		if (space != null) {
			this.setDeltaMovement(this.getDeltaMovement().add(space.scale(this.getGravity())));
			ci.cancel();
			return;
		}
		Vec3 pull = this.cubism$edgePull();
		double gravity = this.getGravity();
		if (pull != null && gravity != 0.0) {
			this.setDeltaMovement(this.getDeltaMovement().add(pull.scale(gravity)));
			ci.cancel();
		}
	}

	@Inject(method = "checkBelowWorld", at = @At("HEAD"), cancellable = true)
	private void cubism$lockedFallGoesOn(CallbackInfo ci) {
		SeamLayout layout = SeamLayout.of(this.level);
		// Space has no bottom: its storage Y is the cube Y, which goes far below the bottom of the world.
		if (GravityLock.fallsOn((Entity) (Object) this)
			|| layout != null && layout.regionAt(Mth.floor(this.position.x), Mth.floor(this.position.z)) == SeamLayout.SPACE) {
			ci.cancel();
		}
	}

	@Override
	public @Nullable Vec3 cubism$edgePull() {
		SeamLayout layout = SeamLayout.of(this.level);
		if (layout == null) {
			return null;
		}
		int face = SeamInteraction.regionFace(layout, this.position.x, this.position.z);
		if (this.onGround() || this.cubism$airStartFace < 0 || GravityLock.locked((Entity) (Object) this)) {
			this.cubism$airStartFace = face;
			return null;
		}
		if (face == this.cubism$airStartFace) {
			return null;
		}
		Vec3 center = this.getBoundingBox().getCenter();
		Vec3 direction = layout.gravity.airGravity(center.x, center.y, center.z);
		boolean groundClose = direction != null
			&& !this.level.noCollision((Entity) (Object) this, this.getBoundingBox().expandTowards(direction.scale(EDGE_GRAVITY_GROUND_GAP)));
		return groundClose ? null : direction;
	}

	@Unique
	private boolean cubism$onCurve() {
		SeamLayout layout = SeamLayout.of(this.level);
		return layout != null && layout.inRoundedEdgeBand(Mth.floor(this.position.x), Mth.floor(this.position.z));
	}

	/** Boats do not step in vanilla. The ice of a frozen sea over a rounded edge stands in steps of a full block. */
	@ModifyReturnValue(method = "maxUpStep", at = @At("RETURN"))
	private float cubism$boatStepOnCurve(float step) {
		return (Object) this instanceof AbstractBoat && this.cubism$onCurve() ? Math.max(step, 1.0F) : step;
	}

	@Inject(method = "move", at = @At("HEAD"))
	private void cubism$rememberGround(MoverType moverType, Vec3 delta, CallbackInfo ci) {
		this.cubism$wasOnGround = this.onGround();
	}

	/**
	 * Over a rounded edge the ground falls away in steps. A living entity that walks off a step no higher than it can
	 * step up lands on the step below at once, as vanilla lifts it onto a step that rises. Only the side that
	 * simulates the entity does it: the server checks a player move with the client's position, which already landed.
	 */
	@Inject(method = "move", at = @At("TAIL"))
	private void cubism$stepDownOnCurve(MoverType moverType, Vec3 delta, CallbackInfo ci) {
		if (moverType != MoverType.SELF || !this.cubism$wasOnGround || this.onGround() || delta.y > 0.0 || this.noPhysics
			|| !((Object) this instanceof LivingEntity living) || living.isFallFlying() || living.onClimbable() || this.isInWater()
			|| this.isPassenger() || living instanceof Player player && player.getAbilities().flying || !this.cubism$onCurve()) {
			return;
		}
		float step = this.maxUpStep();
		double reach = step + STEP_DOWN_MARGIN;
		Vec3 down = this.collide(new Vec3(0.0, -reach, 0.0));
		if (step <= 0.0F || down.y <= -reach + 1.0E-7) {
			return;
		}
		Vec3 drop = down;
		this.setPos(this.position.x + drop.x, this.position.y + drop.y, this.position.z + drop.z);
		this.setOnGround(true);
		Vec3 motion = this.getDeltaMovement();
		this.setDeltaMovement(new Vec3(motion.x, 0.0, motion.z));
	}

	@WrapOperation(method = "pick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
	private BlockHitResult cubism$pickAcrossSeams(Level level, ClipContext context, Operation<BlockHitResult> original) {
		SeamLayout layout = SeamLayout.of(level);
		return layout == null ? original.call(level, context) : SeamInteraction.clip(level, layout, context);
	}

	/*
	 * Distances measure across seams: a point or an entity of another face region counts where it shows, next to
	 * this entity, not where it is stored hundreds of blocks away. Mob targeting, despawning and natural spawning all
	 * read distances through these methods.
	 */
	@ModifyReturnValue(method = "distanceToSqr(DDD)D", at = @At("RETURN"))
	private double cubism$pointDistanceAcrossSeams(
		double distance, @Local(argsOnly = true, ordinal = 0) double x, @Local(argsOnly = true, ordinal = 1) double y, @Local(argsOnly = true, ordinal = 2) double z
	) {
		Vec3 self = this.cubism$positionInRegionOf(x, z);
		return self == null ? distance : self.distanceToSqr(x, y, z);
	}

	@ModifyReturnValue(method = "distanceToSqr(Lnet/minecraft/world/phys/Vec3;)D", at = @At("RETURN"))
	private double cubism$vectorDistanceAcrossSeams(double distance, @Local(argsOnly = true) Vec3 pos) {
		Vec3 self = this.cubism$positionInRegionOf(pos.x, pos.z);
		return self == null ? distance : self.distanceToSqr(pos);
	}

	@ModifyReturnValue(method = "distanceTo(Lnet/minecraft/world/entity/Entity;)F", at = @At("RETURN"))
	private float cubism$entityDistanceAcrossSeams(float distance, @Local(argsOnly = true) Entity entity) {
		Vec3 self = this.cubism$positionInRegionOf(entity.getX(), entity.getZ());
		return self == null ? distance : (float) self.distanceTo(entity.position());
	}

	@ModifyReturnValue(method = "closerThan(Lnet/minecraft/world/entity/Entity;D)Z", at = @At("RETURN"))
	private boolean cubism$closerAcrossSeams(boolean closer, @Local(argsOnly = true) Entity other, @Local(argsOnly = true) double distance) {
		Vec3 self = this.cubism$positionInRegionOf(other.getX(), other.getZ());
		return self == null ? closer : self.closerThan(other.position(), distance);
	}

	/**
	 * Above the atmosphere the wedge of a face is wider than its storage strip. An entity moves into space before it
	 * gets to the strip edge (see SeamLayout.spaceHandoff), so the strip edges are walls, as the world border is, only
	 * for an entity faster than that margin per tick. The space region has walls at its own edges.
	 */
	@ModifyReturnValue(
		method = "collectCollidersIgnoringWorldBorder(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;",
		at = @At("RETURN")
	)
	private static List<VoxelShape> cubism$stripWalls(List<VoxelShape> colliders, @Local(argsOnly = true) @Nullable Entity source, @Local(argsOnly = true) Level level, @Local(argsOnly = true) AABB box) {
		SeamLayout layout = source == null ? null : SeamLayout.of(level);
		Vec3 center = box.getCenter();
		int face = layout == null ? -1 : SeamInteraction.regionFace(layout, center.x, center.z);
		if (face < 0) {
			return colliders;
		}
		double half = face == SeamLayout.SPACE ? SeamLayout.SPACE_HALF : layout.spacing / 2.0;
		double minX = layout.offsetX(face) - half;
		double maxX = layout.offsetX(face) + half;
		double reach = Math.max(Math.max(box.getXsize(), box.getZsize()), 1.0) * 2.0;
		if (box.minX - minX > reach && maxX - box.maxX > reach && box.minZ + half > reach && half - box.maxZ > reach) {
			return colliders;
		}
		List<VoxelShape> all = new ArrayList<>(colliders);
		all.add(Shapes.join(
			Shapes.INFINITY,
			Shapes.box(minX, Double.NEGATIVE_INFINITY, -half + 1.0, maxX, Double.POSITIVE_INFINITY, half - 1.0),
			BooleanOp.ONLY_FIRST
		));
		return all;
	}

	/** This entity moved into the face region of {@code (x, z)}, or null when it is already in that region. */
	@Unique
	private @Nullable Vec3 cubism$positionInRegionOf(double x, double z) {
		SeamLayout layout = SeamLayout.of(this.level);
		int[] m = layout == null ? null : SeamInteraction.regionMotion(layout, this.position.x, this.position.z, x, z);
		return m == null ? null : SeamInteraction.move(m, this.position);
	}
}
