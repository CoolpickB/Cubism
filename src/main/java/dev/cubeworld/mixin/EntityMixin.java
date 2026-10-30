package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.gravity.CubePlanet;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.network.CubeNetworking;
import it.unimi.dsi.fastutil.floats.FloatArraySet;
import it.unimi.dsi.fastutil.floats.FloatArrays;
import it.unimi.dsi.fastutil.floats.FloatSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Entities move in their local frame: the velocity, the rotation, and the vanilla movement code all work as if
 * the local up were world up. This mixin converts at the points where the movement meets the world:
 * collision, position updates, the hitbox, and the eye and view vectors.
 */
@Mixin(Entity.class)
public abstract class EntityMixin implements GravityEntityAccess {
	@Unique private static final String GRAVITY_KEY = "cubeworld:gravity";

	@Shadow private Level level;
	@Shadow private Vec3 position;
	@Shadow private EntityDimensions dimensions;
	@Shadow public double xo;
	@Shadow public double yo;
	@Shadow public double zo;
	@Shadow public boolean noPhysics;
	@Shadow public boolean needsSync;
	@Shadow public Optional<BlockPos> mainSupportingBlockPos;
	@Shadow private boolean onGroundNoBlocks;

	@Shadow public abstract AABB getBoundingBox();
	@Shadow public abstract void setBoundingBox(AABB bb);
	@Shadow protected abstract AABB makeBoundingBox(Vec3 pos);
	@Shadow public abstract Vec3 getDeltaMovement();
	@Shadow public abstract void setDeltaMovement(Vec3 movement);
	@Shadow public abstract boolean onGround();
	@Shadow public abstract float maxUpStep();
	@Shadow public abstract float getEyeHeight();
	@Shadow public abstract double getGravity();
	@Shadow public abstract void setPos(double x, double y, double z);
	@Shadow protected abstract void checkSupportingBlock(boolean onGround, @Nullable Vec3 movement);
	@Shadow public abstract BlockPos getOnPosLegacy();
	@Shadow protected abstract void checkFallDamage(double ya, boolean onGround, BlockState onState, BlockPos pos);
	@Shadow public abstract boolean touchingUnloadedChunk();
	@Shadow private boolean isFree(AABB box) { return false; }

	@Unique private Direction cubeworld$gravity = Direction.DOWN;
	@Unique private boolean cubeworld$gravityKnown;

	@Unique
	private boolean cubeworld$usesFrame() {
		Object self = this;
		return !(self instanceof Projectile) && !(self instanceof AbstractMinecart);
	}

	@Override
	public Direction cubeworld$getGravityDirection() {
		return this.cubeworld$gravity;
	}

	@Override
	public Direction cubeworld$getFrame() {
		return this.cubeworld$usesFrame() ? this.cubeworld$gravity : Direction.DOWN;
	}

	@Override
	public boolean cubeworld$setGravityDirection(Direction newGravity) {
		if (newGravity == this.cubeworld$gravity) {
			return true;
		}
		Direction oldGravity = this.cubeworld$gravity;
		this.cubeworld$gravity = newGravity;
		if (!this.cubeworld$usesFrame() || this.noPhysics) {
			this.setBoundingBox(this.makeBoundingBox(this.position));
			return true;
		}
		// The hitbox turns about the feet. A pivot about the center moves the feet across the plane and
		// can flip the gravity back on the next tick.
		Entity self = (Entity) (Object) this;
		Vec3 up = CubePlanetMath.up(newGravity);
		for (int step = 0; step <= 6; step++) {
			Vec3 candidate = this.position.add(up.scale(0.1 * step));
			if (this.level.noCollision(self, this.makeBoundingBox(candidate))) {
				Vec3 shift = candidate.subtract(this.position);
				this.xo += shift.x;
				this.yo += shift.y;
				this.zo += shift.z;
				this.setPos(candidate.x, candidate.y, candidate.z);
				return true;
			}
		}
		this.cubeworld$gravity = oldGravity;
		return false;
	}

	@Override
	public void cubeworld$forceGravityDirection(Direction newGravity) {
		this.cubeworld$gravityKnown = true;
		if (newGravity != this.cubeworld$gravity) {
			this.cubeworld$gravity = newGravity;
			this.setBoundingBox(this.makeBoundingBox(this.position));
		}
	}

	@Inject(method = "baseTick", at = @At("HEAD"))
	private void cubeworld$updateGravity(CallbackInfo ci) {
		CubePlanet planet = CubePlanet.of(this.level);
		if (planet == null) {
			if (this.cubeworld$gravity != Direction.DOWN) {
				this.cubeworld$forceGravityDirection(Direction.DOWN);
			}
			this.cubeworld$gravityKnown = false;
			return;
		}
		Entity self = (Entity) (Object) this;
		boolean isPlayer = self instanceof Player;
		if (this.level.isClientSide()) {
			// The client simulates only its own player. The server sends the gravity of every other entity.
			if (!isPlayer || !((Player) self).isLocalPlayer() || !this.cubeworld$gravityKnown) {
				return;
			}
		} else if (!this.cubeworld$gravityKnown) {
			this.cubeworld$forceGravityDirection(CubePlanetMath.sectorGravity(this.position, planet.centerY()));
			CubeNetworking.broadcastGravity(self, true);
			return;
		} else if (isPlayer) {
			return;
		}

		Direction target = CubePlanetMath.stickyGravity(this.level, this.position, planet.centerY(), planet.radius(), this.cubeworld$gravity);
		if (target != this.cubeworld$gravity && this.cubeworld$setGravityDirection(target)) {
			if (this.level.isClientSide()) {
				CubeNetworking.clientGravitySender.accept(target);
			} else {
				CubeNetworking.broadcastGravity(self, false);
			}
		}
	}

	@Inject(method = "saveWithoutId", at = @At("TAIL"))
	private void cubeworld$saveGravity(ValueOutput output, CallbackInfo ci) {
		if (this.cubeworld$gravityKnown) {
			output.putString(GRAVITY_KEY, this.cubeworld$gravity.getSerializedName());
		}
	}

	@Inject(method = "load", at = @At("TAIL"))
	private void cubeworld$loadGravity(ValueInput input, CallbackInfo ci) {
		input.getString(GRAVITY_KEY).map(Direction::byName).ifPresent(this::cubeworld$forceGravityDirection);
	}

	@Inject(method = "makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;", at = @At("HEAD"), cancellable = true)
	private void cubeworld$makeOrientedBoundingBox(Vec3 pos, CallbackInfoReturnable<AABB> cir) {
		Direction frame = this.cubeworld$getFrame();
		if (frame != Direction.DOWN) {
			cir.setReturnValue(CubePlanetMath.localToWorld(this.dimensions.makeBoundingBox(Vec3.ZERO), frame).move(pos));
		}
	}

	@Inject(method = "getEyePosition()Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
	private void cubeworld$getEyePosition(CallbackInfoReturnable<Vec3> cir) {
		Direction frame = this.cubeworld$getFrame();
		if (frame != Direction.DOWN) {
			cir.setReturnValue(this.position.add(CubePlanetMath.up(frame).scale(this.getEyeHeight())));
		}
	}

	@Inject(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
	private void cubeworld$getEyePositionPartial(float partialTicks, CallbackInfoReturnable<Vec3> cir) {
		Direction frame = this.cubeworld$getFrame();
		if (frame != Direction.DOWN) {
			Vec3 feet = new Vec3(
				Mth.lerp(partialTicks, this.xo, this.position.x),
				Mth.lerp(partialTicks, this.yo, this.position.y),
				Mth.lerp(partialTicks, this.zo, this.position.z)
			);
			cir.setReturnValue(feet.add(CubePlanetMath.up(frame).scale(this.getEyeHeight())));
		}
	}

	@Inject(method = {"getViewVector", "getUpVector"}, at = @At("RETURN"), cancellable = true)
	private void cubeworld$viewToWorld(float partialTicks, CallbackInfoReturnable<Vec3> cir) {
		Direction frame = this.cubeworld$getFrame();
		if (frame != Direction.DOWN) {
			cir.setReturnValue(CubePlanetMath.localToWorld(cir.getReturnValue(), frame));
		}
	}

	// Entities with a frame get gravity as local -Y from vanilla. Projectiles keep world velocity.
	@Inject(method = "applyGravity", at = @At("HEAD"), cancellable = true)
	private void cubeworld$applyWorldGravity(CallbackInfo ci) {
		Direction gravity = this.cubeworld$gravity;
		if (this.cubeworld$usesFrame() || gravity == Direction.DOWN) {
			return;
		}
		double g = this.getGravity();
		if (g != 0.0) {
			this.setDeltaMovement(this.getDeltaMovement().add(gravity.getStepX() * g, gravity.getStepY() * g, gravity.getStepZ() * g));
		}
		ci.cancel();
	}

	@Inject(method = "getOnPos(F)Lnet/minecraft/core/BlockPos;", at = @At("HEAD"), cancellable = true)
	private void cubeworld$getOnPosOriented(float offset, CallbackInfoReturnable<BlockPos> cir) {
		Direction frame = this.cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			return;
		}
		Vec3 below = this.position.add(frame.getStepX() * offset, frame.getStepY() * offset, frame.getStepZ() * offset);
		if (this.mainSupportingBlockPos.isEmpty()) {
			cir.setReturnValue(BlockPos.containing(below));
			return;
		}
		BlockPos support = this.mainSupportingBlockPos.get();
		if (!(offset > 1.0E-5F)) {
			cir.setReturnValue(support);
			return;
		}
		BlockState state = this.level.getBlockState(support);
		if (offset <= 0.5 && state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.getBlock() instanceof FenceGateBlock) {
			cir.setReturnValue(support);
			return;
		}
		int level = Mth.floor(below.get(frame.getAxis()));
		cir.setReturnValue(switch (frame.getAxis()) {
			case X -> new BlockPos(level, support.getY(), support.getZ());
			case Y -> support.atY(level);
			case Z -> new BlockPos(support.getX(), support.getY(), level);
		});
	}

	@Inject(method = "checkSupportingBlock", at = @At("HEAD"), cancellable = true)
	private void cubeworld$checkSupportingBlockOriented(boolean onGround, @Nullable Vec3 movement, CallbackInfo ci) {
		Direction frame = this.cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			return;
		}
		ci.cancel();
		if (!onGround) {
			this.onGroundNoBlocks = false;
			this.mainSupportingBlockPos = Optional.empty();
			return;
		}
		Entity self = (Entity) (Object) this;
		AABB box = CubePlanetMath.worldToLocal(this.getBoundingBox(), frame);
		AABB testArea = new AABB(box.minX, box.minY - 1.0E-6, box.minZ, box.maxX, box.minY, box.maxZ);
		Optional<BlockPos> support = this.level.findSupportingBlock(self, CubePlanetMath.localToWorld(testArea, frame));
		if (support.isPresent() || this.onGroundNoBlocks) {
			this.mainSupportingBlockPos = support;
		} else if (movement != null) {
			AABB previousArea = testArea.move(-movement.x, 0.0, -movement.z);
			support = this.level.findSupportingBlock(self, CubePlanetMath.localToWorld(previousArea, frame));
			this.mainSupportingBlockPos = support;
		}
		this.onGroundNoBlocks = support.isEmpty();
	}

	// Self movement is already local. The other mover types push the entity by a world vector.
	@ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true)
	private Vec3 cubeworld$worldPushToLocal(Vec3 delta, @Local(argsOnly = true) MoverType moverType) {
		Direction frame = this.cubeworld$getFrame();
		return frame == Direction.DOWN || moverType == MoverType.SELF ? delta : CubePlanetMath.worldToLocal(delta, frame);
	}

	@WrapOperation(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setPos(DDD)V"))
	private void cubeworld$noPhysicsMoveInWorld(Entity self, double x, double y, double z, Operation<Void> original,
		@Local(argsOnly = true) Vec3 delta) {
		Direction frame = this.cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			original.call(self, x, y, z);
			return;
		}
		Vec3 target = this.position.add(CubePlanetMath.localToWorld(delta, frame));
		original.call(self, target.x, target.y, target.z);
	}

	@ModifyArg(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;recordMovement(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V"), index = 1)
	private Vec3 cubeworld$recordWorldMovement(Vec3 movement) {
		return CubePlanetMath.localToWorld(movement, this.cubeworld$getFrame());
	}

	@ModifyExpressionValue(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;normalize()Lnet/minecraft/world/phys/Vec3;"))
	private Vec3 cubeworld$fallClipInWorld(Vec3 direction) {
		return CubePlanetMath.localToWorld(direction, this.cubeworld$getFrame());
	}

	@WrapOperation(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;add(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;", ordinal = 1))
	private Vec3 cubeworld$applyMovementInWorld(Vec3 pos, Vec3 movement, Operation<Vec3> original) {
		return original.call(pos, CubePlanetMath.localToWorld(movement, this.cubeworld$getFrame()));
	}

	@ModifyArg(method = "move", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity$Movement;<init>(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V"), index = 2)
	private Vec3 cubeworld$originalMovementInWorld(Vec3 delta) {
		return CubePlanetMath.localToWorld(delta, this.cubeworld$getFrame());
	}

	@Inject(method = "collide", at = @At("HEAD"), cancellable = true)
	private void cubeworld$collideOriented(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
		Direction frame = this.cubeworld$getFrame();
		if (frame != Direction.DOWN) {
			cir.setReturnValue(this.cubeworld$collideLocal(movement, frame));
		}
	}

	/** Vanilla {@code Entity.collide} with every axis taken from the local frame. Input and output are local. */
	@Unique
	private Vec3 cubeworld$collideLocal(Vec3 movement, Direction frame) {
		Entity self = (Entity) (Object) this;
		AABB aabb = this.getBoundingBox();
		float maxUpStep = this.maxUpStep();
		Vec3 worldMovement = CubePlanetMath.localToWorld(movement, frame);
		List<VoxelShape> entityColliders = this.level.getEntityCollisions(
			self, aabb.expandTowards(worldMovement).expandTowards(CubePlanetMath.up(frame).scale(maxUpStep))
		);
		Vec3 movementStep = movement.lengthSqr() == 0.0
			? movement
			: cubeworld$collideWithShapes(movement, aabb, this.cubeworld$colliders(entityColliders, aabb.expandTowards(worldMovement)), frame);
		boolean xCollision = movement.x != movementStep.x;
		boolean yCollision = movement.y != movementStep.y;
		boolean zCollision = movement.z != movementStep.z;
		boolean onGroundAfterCollision = yCollision && movement.y < 0.0;
		if (maxUpStep > 0.0F && (onGroundAfterCollision || this.onGround()) && (xCollision || zCollision)) {
			AABB groundedAABB = onGroundAfterCollision ? aabb.move(CubePlanetMath.localToWorld(new Vec3(0.0, movementStep.y, 0.0), frame)) : aabb;
			AABB stepUpAABB = groundedAABB.expandTowards(CubePlanetMath.localToWorld(new Vec3(movement.x, maxUpStep, movement.z), frame));
			if (!onGroundAfterCollision) {
				stepUpAABB = stepUpAABB.expandTowards(CubePlanetMath.localToWorld(new Vec3(0.0, -1.0E-5F, 0.0), frame));
			}
			List<VoxelShape> colliders = this.cubeworld$colliders(entityColliders, stepUpAABB);
			for (float height : cubeworld$candidateStepUpHeights(groundedAABB, colliders, maxUpStep, (float) movementStep.y, frame)) {
				Vec3 stepFromGround = cubeworld$collideWithShapes(new Vec3(movement.x, height, movement.z), groundedAABB, colliders, frame);
				if (stepFromGround.horizontalDistanceSqr() > movementStep.horizontalDistanceSqr()) {
					double distanceToGround = onGroundAfterCollision ? -movementStep.y : 0.0;
					return stepFromGround.subtract(0.0, distanceToGround, 0.0);
				}
			}
		}
		return movementStep;
	}

	@Unique
	private List<VoxelShape> cubeworld$colliders(List<VoxelShape> entityColliders, AABB area) {
		List<VoxelShape> colliders = new ArrayList<>(entityColliders);
		this.level.getBlockCollisions((Entity) (Object) this, area).forEach(colliders::add);
		return colliders;
	}

	/** Resolves the local up axis first, then the larger horizontal axis, as {@code Direction.axisStepOrder} does. */
	@Unique
	private static Vec3 cubeworld$collideWithShapes(Vec3 movement, AABB box, List<VoxelShape> shapes, Direction frame) {
		if (shapes.isEmpty()) {
			return movement;
		}
		Direction.Axis[] order = Math.abs(movement.x) < Math.abs(movement.z)
			? new Direction.Axis[] {Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X}
			: new Direction.Axis[] {Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z};
		Vec3 resolved = Vec3.ZERO;
		for (Direction.Axis axis : order) {
			double amount = movement.get(axis);
			if (amount != 0.0) {
				Direction worldDir = CubePlanetMath.rotateDirection(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE), frame);
				int sign = worldDir.getAxisDirection().getStep();
				AABB moved = box.move(CubePlanetMath.localToWorld(resolved, frame));
				double collision = Shapes.collide(worldDir.getAxis(), moved, shapes, amount * sign) * sign;
				resolved = resolved.with(axis, collision);
			}
		}
		return resolved;
	}

	@Unique
	private static float[] cubeworld$candidateStepUpHeights(AABB box, List<VoxelShape> colliders, float maxStepHeight, float stepHeightToSkip, Direction frame) {
		Direction up = frame.getOpposite();
		Direction.Axis axis = up.getAxis();
		boolean positive = up.getAxisDirection() == Direction.AxisDirection.POSITIVE;
		double floor = positive ? box.min(axis) : box.max(axis);
		FloatSet candidates = new FloatArraySet(4);
		for (VoxelShape collider : colliders) {
			for (double coord : collider.getCoords(axis)) {
				float relative = (float) (positive ? coord - floor : floor - coord);
				if (relative >= 0.0F && relative != stepHeightToSkip && relative <= maxStepHeight) {
					candidates.add(relative);
				}
			}
		}
		float[] sorted = candidates.toFloatArray();
		FloatArrays.unstableSort(sorted);
		return sorted;
	}

	@Inject(method = "isFree(DDD)Z", at = @At("HEAD"), cancellable = true)
	private void cubeworld$isFreeLocal(double xa, double ya, double za, CallbackInfoReturnable<Boolean> cir) {
		Direction frame = this.cubeworld$getFrame();
		if (frame != Direction.DOWN) {
			cir.setReturnValue(this.isFree(this.getBoundingBox().move(CubePlanetMath.localToWorld(new Vec3(xa, ya, za), frame))));
		}
	}

	@Inject(method = "getAvailableSpaceBelow", at = @At("HEAD"), cancellable = true)
	private void cubeworld$availableSpaceBelowOriented(double maxDistance, CallbackInfoReturnable<Double> cir) {
		Direction frame = this.cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			return;
		}
		AABB box = this.getBoundingBox();
		AABB local = CubePlanetMath.worldToLocal(box, frame);
		AABB below = CubePlanetMath.localToWorld(local.setMinY(local.minY - maxDistance).setMaxY(local.minY), frame);
		List<VoxelShape> colliders = Entity.collectAllColliders((Entity) (Object) this, this.level, below);
		if (colliders.isEmpty()) {
			cir.setReturnValue(maxDistance);
			return;
		}
		int sign = frame.getAxisDirection().getStep();
		cir.setReturnValue(Math.abs(Shapes.collide(frame.getAxis(), box, colliders, maxDistance * sign)));
	}

	// Pushes from other entities and explosions come as world vectors.
	@Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true)
	private void cubeworld$pushInLocalFrame(double xa, double ya, double za, CallbackInfo ci) {
		Direction frame = this.cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			return;
		}
		ci.cancel();
		if (Double.isFinite(xa) && Double.isFinite(ya) && Double.isFinite(za)) {
			this.setDeltaMovement(this.getDeltaMovement().add(CubePlanetMath.worldToLocal(new Vec3(xa, ya, za), frame)));
			this.needsSync = true;
		}
	}

	// The server calls this with the world movement of a player.
	@Inject(method = "doCheckFallDamage", at = @At("HEAD"), cancellable = true)
	private void cubeworld$checkFallDamageLocal(double xa, double ya, double za, boolean onGround, CallbackInfo ci) {
		Direction frame = this.cubeworld$getFrame();
		if (frame == Direction.DOWN) {
			return;
		}
		ci.cancel();
		if (!this.touchingUnloadedChunk()) {
			Vec3 local = CubePlanetMath.worldToLocal(new Vec3(xa, ya, za), frame);
			this.checkSupportingBlock(onGround, local);
			BlockPos pos = this.getOnPosLegacy();
			this.checkFallDamage(local.y, onGround, this.level.getBlockState(pos), pos);
		}
	}

	@WrapOperation(method = "isInWall", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/AABB;ofSize(Lnet/minecraft/world/phys/Vec3;DDD)Lnet/minecraft/world/phys/AABB;"))
	private AABB cubeworld$orientedEyeSlab(Vec3 center, double x, double y, double z, Operation<AABB> original) {
		Vec3 size = CubePlanetMath.localToWorld(new Vec3(x, y, z), this.cubeworld$getFrame());
		return original.call(center, Math.abs(size.x), Math.abs(size.y), Math.abs(size.z));
	}

	// The vanilla search for free space assumes a Y-up hitbox.
	@Inject(method = "fudgePositionAfterSizeChange", at = @At("HEAD"), cancellable = true)
	private void cubeworld$skipFudgeWhenTurned(EntityDimensions previousDimensions, CallbackInfoReturnable<Boolean> cir) {
		if (this.cubeworld$getFrame() != Direction.DOWN) {
			cir.setReturnValue(false);
		}
	}
}
