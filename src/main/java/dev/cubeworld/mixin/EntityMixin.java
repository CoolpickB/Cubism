package dev.cubeworld.mixin;

import dev.cubeworld.CubeWorldMod;
import dev.cubeworld.gravity.GravityEntityAccess;
import dev.cubeworld.math.CubePlanetMath;
import dev.cubeworld.worldgen.CubeChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

@Mixin(Entity.class)
public abstract class EntityMixin implements GravityEntityAccess {
	@Shadow private Level level;
	@Shadow private Vec3 position;
	@Shadow private EntityDimensions dimensions;
	@Shadow public double xo;
	@Shadow public double yo;
	@Shadow public double zo;
	@Shadow public float yRotO;
	@Shadow public boolean horizontalCollision;
	@Shadow public boolean verticalCollision;
	@Shadow public boolean verticalCollisionBelow;
	@Shadow public boolean noPhysics;
	@Shadow protected Optional<BlockPos> mainSupportingBlockPos;

	@Shadow public abstract double getX();
	@Shadow public abstract double getY();
	@Shadow public abstract double getZ();
	@Shadow public abstract float getYRot();
	@Shadow public abstract void setYRot(float yRot);
	@Shadow public abstract float getXRot();
	@Shadow public abstract float getViewXRot(float partialTicks);
	@Shadow public abstract float getViewYRot(float partialTicks);
	@Shadow public abstract float getEyeHeight();
	@Shadow public abstract Vec3 getDeltaMovement();
	@Shadow public abstract void setDeltaMovement(Vec3 movement);
	@Shadow public abstract double getGravity();
	@Shadow public abstract AABB getBoundingBox();
	@Shadow public abstract void setBoundingBox(AABB bb);
	@Shadow public abstract float maxUpStep();
	@Shadow public abstract boolean onGround();
	@Shadow public abstract void setOnGroundWithMovement(boolean onGround, boolean horizontalCollision, Vec3 movement);
	@Shadow public abstract void reapplyPosition();
	@Shadow protected abstract AABB makeBoundingBox(Vec3 pos);

	@Unique private Direction cubeworld$gravityDirection = Direction.DOWN;
	@Unique private boolean cubeworld$inCubeWorld = false;
	@Unique private int cubeworld$centerY = CubePlanetMath.TOP_SURFACE_Y - (CubePlanetMath.MIN_DIAMETER_CHUNKS * 8);
	@Unique private Vec3 cubeworld$preMovePos = Vec3.ZERO;

	@Override
	public Direction cubeworld$getGravityDirection() {
		return this.cubeworld$gravityDirection;
	}

	@Override
	public void cubeworld$setGravityDirection(Direction newDir) {
		if (newDir == null || newDir == this.cubeworld$gravityDirection) {
			return;
		}
		Direction oldDir = this.cubeworld$gravityDirection;
		double halfH = this.dimensions.height() * 0.5;
		Vec3 oldUp = new Vec3(-oldDir.getStepX(), -oldDir.getStepY(), -oldDir.getStepZ());
		Vec3 newUp = new Vec3(-newDir.getStepX(), -newDir.getStepY(), -newDir.getStepZ());
		Vec3 oldPos = this.position;
		Vec3 bodyCenter = oldPos.add(oldUp.scale(halfH));
		Vec3 newFeetPos = bodyCenter.subtract(newUp.scale(halfH));

		// Check collision clearance for the newly oriented AABB; nudge up to 0.6 blocks outward, or defer the swap if in a tight space
		if (this.level != null && this.cubeworld$inCubeWorld && !this.noPhysics) {
			Entity self = (Entity) (Object) this;
			this.cubeworld$gravityDirection = newDir;
			boolean foundRoom = false;
			Vec3 testPos = newFeetPos;
			for (int step = 0; step <= 6; step++) {
				if (this.level.noCollision(self, this.makeBoundingBox(testPos))) {
					newFeetPos = testPos;
					foundRoom = true;
					break;
				}
				testPos = testPos.add(newUp.scale(0.1));
			}
			if (!foundRoom) {
				this.cubeworld$gravityDirection = oldDir;
				return;
			}
		} else {
			this.cubeworld$gravityDirection = newDir;
		}

		// Compute holonomy yaw compensation at bodyCenter so the continuous camera frame Q_after * R_local(yRot + deltaYaw)
		// equals Q_before * R_local(yRot) with zero discontinuity
		Quaternionf qBefore = CubePlanetMath.getContinuousCameraRotation(
			bodyCenter.x, bodyCenter.y, bodyCenter.z, this.cubeworld$centerY, oldDir
		);
		Quaternionf qAfter = CubePlanetMath.getContinuousCameraRotation(
			bodyCenter.x, bodyCenter.y, bodyCenter.z, this.cubeworld$centerY, newDir
		);
		Quaternionf qDiff = new Quaternionf(qAfter).conjugate().mul(qBefore);
		Vector3f localX = new Vector3f(1.0f, 0.0f, 0.0f);
		qDiff.transform(localX);
		float deltaYawDeg = (float) Math.atan2(localX.z, localX.x) * (180.0f / (float) Math.PI);

		if (Math.abs(deltaYawDeg) > 0.01f) {
			this.setYRot(this.getYRot() + deltaYawDeg);
			this.yRotO += deltaYawDeg;
			if ((Object) this instanceof LivingEntity living) {
				living.setYHeadRot(living.getYHeadRot() + deltaYawDeg);
				living.yHeadRotO += deltaYawDeg;
				living.yBodyRot += deltaYawDeg;
				living.yBodyRotO += deltaYawDeg;
			}
		}

		Vec3 posDelta = newFeetPos.subtract(oldPos);
		this.xo += posDelta.x;
		this.yo += posDelta.y;
		this.zo += posDelta.z;
		this.position = newFeetPos;
		this.reapplyPosition();
	}

	@Override
	public Quaternionf cubeworld$getRenderCameraRotation(float partialTicks) {
		if (!this.cubeworld$inCubeWorld) {
			return new Quaternionf();
		}
		Direction dir = this.cubeworld$gravityDirection;
		double halfH = this.dimensions.height() * 0.5;
		double lx = Mth.lerp(partialTicks, this.xo, this.getX()) - dir.getStepX() * halfH;
		double ly = Mth.lerp(partialTicks, this.yo, this.getY()) - dir.getStepY() * halfH;
		double lz = Mth.lerp(partialTicks, this.zo, this.getZ()) - dir.getStepZ() * halfH;
		return CubePlanetMath.getContinuousCameraRotation(lx, ly, lz, this.cubeworld$centerY, dir);
	}

	@Inject(method = "baseTick", at = @At("HEAD"))
	private void cubeworld$updateGravityOnTick(CallbackInfo ci) {
		if (this.level == null) {
			return;
		}

		boolean isCubeWorld = false;
		int centerY = CubePlanetMath.getClientCenterY();

		if (this.level instanceof ServerLevel serverLevel) {
			if (serverLevel.getChunkSource().getGenerator() instanceof CubeChunkGenerator cubeGen) {
				isCubeWorld = true;
				centerY = cubeGen.centerY();
				CubePlanetMath.setClientCubeState(true, cubeGen.diameterChunks());
			}
		} else if (this.level.dimensionTypeRegistration().is(CubeWorldMod.CUBE_OVERWORLD_TYPE)) {
			isCubeWorld = true;
		} else if (CubePlanetMath.isClientCubeActive() && this.level.dimension() == Level.OVERWORLD) {
			isCubeWorld = true;
		}

		this.cubeworld$inCubeWorld = isCubeWorld;
		this.cubeworld$centerY = centerY;

		if (!isCubeWorld) {
			if (this.cubeworld$gravityDirection != Direction.DOWN) {
				this.cubeworld$setGravityDirection(Direction.DOWN);
			}
			return;
		}

		double halfH = this.dimensions.height() * 0.5;
		Direction cur = this.cubeworld$gravityDirection;
		double cx = this.getX() - cur.getStepX() * halfH;
		double cy = this.getY() - cur.getStepY() * halfH;
		double cz = this.getZ() - cur.getStepZ() * halfH;

		Direction target = CubePlanetMath.getGravityDirection(cx, cy, cz, centerY, cur);
		if (target != cur) {
			this.cubeworld$setGravityDirection(target);
		}
	}

	@Inject(method = "makeBoundingBox(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/AABB;", at = @At("HEAD"), cancellable = true)
	private void cubeworld$makeOrientedBoundingBox(Vec3 pos, CallbackInfoReturnable<AABB> cir) {
		Direction dir = this.cubeworld$gravityDirection;
		if (dir == Direction.DOWN) {
			return;
		}
		float w = this.dimensions.width() * 0.5f;
		float h = this.dimensions.height();

		AABB box = switch (dir) {
			case DOWN -> new AABB(pos.x - w, pos.y, pos.z - w, pos.x + w, pos.y + h, pos.z + w);
			case UP -> new AABB(pos.x - w, pos.y - h, pos.z - w, pos.x + w, pos.y, pos.z + w);
			case NORTH -> new AABB(pos.x - w, pos.y - w, pos.z, pos.x + w, pos.y + w, pos.z + h);
			case SOUTH -> new AABB(pos.x - w, pos.y - w, pos.z - h, pos.x + w, pos.y + w, pos.z);
			case WEST -> new AABB(pos.x, pos.y - w, pos.z - w, pos.x + h, pos.y + w, pos.z + w);
			case EAST -> new AABB(pos.x - h, pos.y - w, pos.z - w, pos.x, pos.y + w, pos.z + w);
		};
		cir.setReturnValue(box);
	}

	@Inject(method = "getEyePosition()Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
	private void cubeworld$getEyePosition(CallbackInfoReturnable<Vec3> cir) {
		if (!this.cubeworld$inCubeWorld) {
			return;
		}
		Direction dir = this.cubeworld$gravityDirection;
		double halfH = this.dimensions.height() * 0.5;
		double cx = this.getX() - dir.getStepX() * halfH;
		double cy = this.getY() - dir.getStepY() * halfH;
		double cz = this.getZ() - dir.getStepZ() * halfH;
		Vec3 contUp = CubePlanetMath.getContinuousUpVector(cx, cy, cz, this.cubeworld$centerY);
		double eyeFromCenter = this.getEyeHeight() - halfH;
		cir.setReturnValue(new Vec3(
			cx + contUp.x * eyeFromCenter,
			cy + contUp.y * eyeFromCenter,
			cz + contUp.z * eyeFromCenter
		));
	}

	@Inject(method = "getEyePosition(F)Lnet/minecraft/world/phys/Vec3;", at = @At("HEAD"), cancellable = true)
	private void cubeworld$getEyePositionPartial(float partialTicks, CallbackInfoReturnable<Vec3> cir) {
		if (!this.cubeworld$inCubeWorld) {
			return;
		}
		Direction dir = this.cubeworld$gravityDirection;
		double halfH = this.dimensions.height() * 0.5;
		double cx = Mth.lerp(partialTicks, this.xo, this.getX()) - dir.getStepX() * halfH;
		double cy = Mth.lerp(partialTicks, this.yo, this.getY()) - dir.getStepY() * halfH;
		double cz = Mth.lerp(partialTicks, this.zo, this.getZ()) - dir.getStepZ() * halfH;
		Vec3 contUp = CubePlanetMath.getContinuousUpVector(cx, cy, cz, this.cubeworld$centerY);
		double eyeFromCenter = this.getEyeHeight() - halfH;
		cir.setReturnValue(new Vec3(
			cx + contUp.x * eyeFromCenter,
			cy + contUp.y * eyeFromCenter,
			cz + contUp.z * eyeFromCenter
		));
	}

	@Inject(method = "getViewVector", at = @At("RETURN"), cancellable = true)
	private void cubeworld$rotateViewVector(float partialTicks, CallbackInfoReturnable<Vec3> cir) {
		if (!this.cubeworld$inCubeWorld) {
			return;
		}
		Quaternionf q = this.cubeworld$getRenderCameraRotation(partialTicks);
		if (Math.abs(q.x) < 1.0E-5f && Math.abs(q.y) < 1.0E-5f && Math.abs(q.z) < 1.0E-5f) {
			return;
		}
		Vec3 local = cir.getReturnValue();
		Vector3f v = new Vector3f((float) local.x, (float) local.y, (float) local.z);
		q.transform(v);
		cir.setReturnValue(new Vec3(v.x, v.y, v.z));
	}

	@Inject(method = "getUpVector", at = @At("RETURN"), cancellable = true)
	private void cubeworld$rotateUpVector(float partialTicks, CallbackInfoReturnable<Vec3> cir) {
		if (!this.cubeworld$inCubeWorld) {
			return;
		}
		Quaternionf q = this.cubeworld$getRenderCameraRotation(partialTicks);
		if (Math.abs(q.x) < 1.0E-5f && Math.abs(q.y) < 1.0E-5f && Math.abs(q.z) < 1.0E-5f) {
			return;
		}
		Vec3 local = cir.getReturnValue();
		Vector3f v = new Vector3f((float) local.x, (float) local.y, (float) local.z);
		q.transform(v);
		cir.setReturnValue(new Vec3(v.x, v.y, v.z));
	}

	@Inject(method = "applyGravity", at = @At("HEAD"), cancellable = true)
	private void cubeworld$applyDirectionalGravity(CallbackInfo ci) {
		if (!this.cubeworld$inCubeWorld) {
			return;
		}
		Direction dir = this.cubeworld$gravityDirection;
		double g = this.getGravity();
		if (g != 0.0) {
			// Grounded entities use discrete face gravity so they don't slide toward the face center near an edge;
			// airborne entities and dropped items use the continuous closest-point gravity vector!
			if (this.onGround() && !((Object) this instanceof ItemEntity)) {
				this.setDeltaMovement(this.getDeltaMovement().add(
					dir.getStepX() * g,
					dir.getStepY() * g,
					dir.getStepZ() * g
				));
			} else {
				double halfH = this.dimensions.height() * 0.5;
				Vec3 contUp = CubePlanetMath.getContinuousUpVector(
					this.getX() - dir.getStepX() * halfH,
					this.getY() - dir.getStepY() * halfH,
					this.getZ() - dir.getStepZ() * halfH,
					this.cubeworld$centerY
				);
				this.setDeltaMovement(this.getDeltaMovement().subtract(contUp.scale(g)));
			}
		}
		ci.cancel();
	}

	@Inject(method = "moveRelative", at = @At("HEAD"), cancellable = true)
	private void cubeworld$moveRelativeOriented(float speed, Vec3 input, CallbackInfo ci) {
		if (!this.cubeworld$inCubeWorld) {
			return;
		}
		Direction dir = this.cubeworld$gravityDirection;
		double d = input.lengthSqr();
		if (d < 1.0E-7) {
			ci.cancel();
			return;
		}
		Vec3 norm = (d > 1.0 ? input.normalize() : input).scale(speed);
		float sin = Mth.sin(this.getYRot() * ((float) Math.PI / 180F));
		float cos = Mth.cos(this.getYRot() * ((float) Math.PI / 180F));
		Vec3 localMove = new Vec3(
			norm.x * cos - norm.z * sin,
			norm.y,
			norm.z * cos + norm.x * sin
		);
		boolean isFlyingPlayer = (Object) this instanceof Player p && p.getAbilities().flying;
		Vec3 worldMove;
		if (this.noPhysics || isFlyingPlayer || !this.onGround()) {
			Quaternionf q = this.cubeworld$getRenderCameraRotation(1.0f);
			Vector3f mv = new Vector3f((float) localMove.x, (float) localMove.y, (float) localMove.z);
			q.transform(mv);
			worldMove = new Vec3(mv.x, mv.y, mv.z);
		} else {
			worldMove = CubePlanetMath.localToWorld(localMove, dir);
		}

		// Grounded edge-bowl speed modifier: walking toward an edge (against the tilted continuous gravity pull)
		// feels slightly uphill, and walking back toward the face center feels slightly downhill
		if (this.onGround() && !isFlyingPlayer && !this.noPhysics) {
			double halfH = this.dimensions.height() * 0.5;
			Vec3 contUp = CubePlanetMath.getContinuousUpVector(
				this.getX() - dir.getStepX() * halfH,
				this.getY() - dir.getStepY() * halfH,
				this.getZ() - dir.getStepZ() * halfH,
				this.cubeworld$centerY
			);
			double len = worldMove.length();
			if (len > 1.0E-6) {
				double uphillDot = worldMove.dot(contUp) / len;
				double slopeFactor = Mth.clamp(1.0 - uphillDot * 0.25, 0.78, 1.18);
				worldMove = worldMove.scale(slopeFactor);
			}
		}

		this.setDeltaMovement(this.getDeltaMovement().add(worldMove));
		ci.cancel();
	}

	@Inject(method = "getOnPos(F)Lnet/minecraft/core/BlockPos;", at = @At("HEAD"), cancellable = true)
	private void cubeworld$getOnPosOriented(float offsetBelow, CallbackInfoReturnable<BlockPos> cir) {
		Direction dir = this.cubeworld$gravityDirection;
		if (dir == Direction.DOWN) {
			return;
		}
		double ox = this.position.x + dir.getStepX() * offsetBelow;
		double oy = this.position.y + dir.getStepY() * offsetBelow;
		double oz = this.position.z + dir.getStepZ() * offsetBelow;
		cir.setReturnValue(new BlockPos(Mth.floor(ox), Mth.floor(oy), Mth.floor(oz)));
	}

	@Inject(method = "checkSupportingBlock", at = @At("HEAD"), cancellable = true)
	private void cubeworld$checkSupportingBlockOriented(boolean onGround, Vec3 movement, CallbackInfo ci) {
		Direction dir = this.cubeworld$gravityDirection;
		if (dir == Direction.DOWN) {
			return;
		}
		if (onGround) {
			AABB bb = this.getBoundingBox();
			double eps = 1.0E-6;
			AABB supportBox = switch (dir) {
				case DOWN -> new AABB(bb.minX, bb.minY - eps, bb.minZ, bb.maxX, bb.minY, bb.maxZ);
				case UP -> new AABB(bb.minX, bb.maxY, bb.minZ, bb.maxX, bb.maxY + eps, bb.maxZ);
				case NORTH -> new AABB(bb.minX, bb.minY, bb.minZ - eps, bb.maxX, bb.maxY, bb.minZ);
				case SOUTH -> new AABB(bb.minX, bb.minY, bb.maxZ, bb.maxX, bb.maxY, bb.maxZ + eps);
				case WEST -> new AABB(bb.minX - eps, bb.minY, bb.minZ, bb.minX, bb.maxY, bb.maxZ);
				case EAST -> new AABB(bb.maxX, bb.minY, bb.minZ, bb.maxX + eps, bb.maxY, bb.maxZ);
			};
			this.mainSupportingBlockPos = this.level.findSupportingBlock((Entity) (Object) this, supportBox);
		} else {
			this.mainSupportingBlockPos = Optional.empty();
		}
		ci.cancel();
	}

	@Inject(method = "move", at = @At("HEAD"))
	private void cubeworld$capturePreMovePosition(MoverType type, Vec3 requestedMovement, CallbackInfo ci) {
		this.cubeworld$preMovePos = this.position;
	}

	@Inject(
		method = "move",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;setOnGroundWithMovement(ZZLnet/minecraft/world/phys/Vec3;)V",
			shift = At.Shift.AFTER
		)
	)
	private void cubeworld$fixCollisionFlagsForGravity(MoverType type, Vec3 requestedMovement, CallbackInfo ci) {
		Direction dir = this.cubeworld$gravityDirection;
		if (dir == Direction.DOWN) {
			return;
		}
		Vec3 actualMovement = this.position.subtract(this.cubeworld$preMovePos);
		Vec3 localReq = CubePlanetMath.worldToLocal(requestedMovement, dir);
		Vec3 localAct = CubePlanetMath.worldToLocal(actualMovement, dir);

		boolean xCol = !Mth.equal(localReq.x, localAct.x);
		boolean yCol = !Mth.equal(localReq.y, localAct.y);
		boolean zCol = !Mth.equal(localReq.z, localAct.z);

		this.horizontalCollision = xCol || zCol;
		this.verticalCollision = yCol;
		this.verticalCollisionBelow = yCol && localReq.y < 0.0;
		this.setOnGroundWithMovement(this.verticalCollisionBelow, this.horizontalCollision, actualMovement);
	}

	@Inject(method = "collide", at = @At("RETURN"), cancellable = true)
	private void cubeworld$stepUpAlongGravityAxis(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
		Direction dir = this.cubeworld$gravityDirection;
		if (dir == Direction.DOWN) {
			return;
		}
		Vec3 collided = cir.getReturnValue();
		float stepHeight = this.maxUpStep();
		if (stepHeight <= 0.0f) {
			return;
		}

		Vec3 localReq = CubePlanetMath.worldToLocal(movement, dir);
		Vec3 localCol = CubePlanetMath.worldToLocal(collided, dir);

		boolean horizBlocked = !Mth.equal(localReq.x, localCol.x) || !Mth.equal(localReq.z, localCol.z);
		boolean groundedOrHitFloor = this.onGround() || (!Mth.equal(localReq.y, localCol.y) && localReq.y < 0.0);

		if (horizBlocked && groundedOrHitFloor) {
			Entity self = (Entity) (Object) this;
			AABB bb = this.getBoundingBox();
			Vec3 upStepVec = CubePlanetMath.localToWorld(new Vec3(0.0, stepHeight, 0.0), dir);
			AABB steppedBox = bb.move(upStepVec);
			Vec3 horizTarget = CubePlanetMath.localToWorld(new Vec3(localReq.x, 0.0, localReq.z), dir);
			List<net.minecraft.world.phys.shapes.VoxelShape> entityShapes = this.level.getEntityCollisions(self, steppedBox.expandTowards(horizTarget));
			Vec3 steppedHoriz = Entity.collideBoundingBox(self, horizTarget, steppedBox, this.level, entityShapes);

			Vec3 localStepped = CubePlanetMath.worldToLocal(steppedHoriz, dir);
			if (localStepped.x * localStepped.x + localStepped.z * localStepped.z > localCol.x * localCol.x + localCol.z * localCol.z) {
				AABB afterHorizBox = steppedBox.move(steppedHoriz);
				Vec3 downPush = CubePlanetMath.localToWorld(new Vec3(0.0, -stepHeight, 0.0), dir);
				Vec3 settle = Entity.collideBoundingBox(self, downPush, afterHorizBox, this.level, entityShapes);
				cir.setReturnValue(upStepVec.add(steppedHoriz).add(settle));
			}
		}
	}
}
