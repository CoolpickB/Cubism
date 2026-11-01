package dev.cubeworld.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.cubeworld.client.CubeFlight;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The camera of the local player shows the air frame of {@link CubeFlight} near an edge, in flight and on the ground.
 * The model stood upright in the face frame instead, so it tilted against the view near an edge, and at a crossing it
 * jumped from the frame of one face to the frame of the next. The model now stands in the air frame, which turns with
 * the surface and has no step at an edge. The angles of the pose are taken in the air frame, and the model turns into
 * it about its center in flight and about its feet on the ground.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	/** The view frame of each render state that shows the local player near an edge. Render thread only. */
	@Unique
	private static final Map<AvatarRenderState, Pose> AIR = new WeakHashMap<>();

	/** The frame that the model stands in, and how far the body is in flight, which puts the pivot at the center. */
	@Unique
	private record Pose(Quaternionf frame, float flight) {}

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void cubeworld$poseInAirFrame(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		if (!(entity instanceof LocalPlayer player)) {
			return;
		}
		Quaternionf air = CubeFlight.viewFrame(player, partialTicks);
		if (Math.abs(air.x) < 1.0E-6F && Math.abs(air.y) < 1.0E-6F && Math.abs(air.z) < 1.0E-6F) {
			AIR.remove(state);
			return;
		}
		AIR.put(state, new Pose(air, CubeFlight.flightWeight(partialTicks)));
		float headRot = Mth.rotLerp(partialTicks, entity.yHeadRotO, entity.yHeadRot);
		float[] head = CubeFlight.anglesIn(air, headRot, entity.getXRot(partialTicks));
		// The body keeps its yaw relative to the head. Its own direction turned into the air frame would point off the
		// look, because the horizontal of the face is not the horizontal of the air frame. In a glide, vanilla turns the
		// body toward the horizontal flight of the face frame, which next to an edge points away from the look, and
		// after a crossing the body takes seconds to turn back. The bank of the glide already shows the flight, so the
		// body follows the head there.
		float bodyRot = state.isFallFlying ? head[0] : head[0] + Mth.wrapDegrees(state.bodyRot - headRot);
		state.bodyRot = bodyRot;
		state.yRot = Mth.wrapDegrees(head[0] - bodyRot);
		state.xRot = head[1];
		// The bank of the glide compares the flight with the look across the horizontal of the air frame.
		Quaternionf toAir = new Quaternionf(air).conjugate();
		Vec3 look = cubeworld$turn(toAir, entity.getViewVector(partialTicks));
		Vec3 movement = cubeworld$turn(toAir, player.avatarState().deltaMovementOnPreviousTick().lerp(entity.getDeltaMovement(), partialTicks));
		if (movement.horizontalDistanceSqr() > 1.0E-5F && look.horizontalDistanceSqr() > 1.0E-5F) {
			double dot = movement.horizontal().normalize().dot(look.horizontal().normalize());
			double sign = movement.x * look.z - movement.z * look.x;
			state.shouldApplyFlyingYRot = true;
			state.flyingYRot = (float) (Math.signum(sign) * Math.acos(Math.min(1.0, Math.abs(dot))));
		} else {
			state.shouldApplyFlyingYRot = false;
			state.flyingYRot = 0.0F;
		}
	}

	@Inject(method = "setupRotations(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V", at = @At("HEAD"))
	private void cubeworld$turnIntoAirFrame(AvatarRenderState state, PoseStack poseStack, float bodyRot, float entityScale, CallbackInfo ci) {
		Pose pose = AIR.get(state);
		if (pose == null) {
			return;
		}
		// The pose is already scaled, so the center is in model units. In flight the body turns about its center. On
		// the ground it turns about the feet, which stand on the surface.
		float center = state.boundingBoxHeight / 2.0F / entityScale;
		poseStack.rotateAround(pose.frame(), 0.0F, center * pose.flight(), 0.0F);
	}

	@Unique
	private static Vec3 cubeworld$turn(Quaternionf q, Vec3 v) {
		Vector3f turned = q.transform(new Vector3f((float) v.x, (float) v.y, (float) v.z));
		return new Vec3(turned.x, turned.y, turned.z);
	}
}
