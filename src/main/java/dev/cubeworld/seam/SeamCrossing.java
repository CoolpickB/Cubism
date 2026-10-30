package dev.cubeworld.seam;

import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves entities that pass the diagonal of their wedge into the region of the face that owns their position. The
 * position, velocity and look all go through the same rigid motion as the seam rendering, so the view does not
 * jump; only the direction of gravity changes.
 */
public final class SeamCrossing {
	/**
	 * How far past the diagonal the center of an entity must be before it crosses, in blocks. Under the gravity of
	 * either face, the rounded edge slopes down to the diagonal, so an entity in that valley moves across it often.
	 */
	private static final double MARGIN = 1.0;
	/**
	 * Past this distance an entity crosses without ground under it. Otherwise a fall that goes deep past the diagonal
	 * could leave the entity on a slope with no floor in the new frame, held by the gravity of the wrong face.
	 */
	private static final double FORCED_MARGIN = 3.0;
	/** Spectators fly near the corners, where three diagonals meet and each crossing turns the view. */
	private static final double SPECTATOR_MARGIN = 4.0;
	/** How far below the feet the ground must be in the new frame, in blocks. */
	private static final double GROUND_DEPTH = 0.5;

	private SeamCrossing() {}

	public static void tick(ServerLevel level) {
		SeamLayout layout = SeamLayout.of(level);
		if (layout == null) {
			return;
		}
		List<Entity> crossing = new ArrayList<>();
		List<TeleportTransition> transitions = new ArrayList<>();
		for (Entity entity : level.getAllEntities()) {
			// A passenger crosses with the vehicle that carries it.
			if (entity.isPassenger()) {
				continue;
			}
			Vec3 center = entity.getBoundingBox().getCenter();
			boolean spectator = entity.isSpectator();
			int face = layout.crossingFace(center.x, center.y, center.z, spectator ? SPECTATOR_MARGIN : MARGIN);
			if (face < 0) {
				continue;
			}
			TeleportTransition transition = transition(level, layout, entity, face);
			if (spectator || layout.crossingFace(center.x, center.y, center.z, FORCED_MARGIN) >= 0 || landsOn(level, entity, transition.position())) {
				crossing.add(entity);
				transitions.add(transition);
			}
		}
		for (int i = 0; i < crossing.size(); i++) {
			Entity entity = crossing.get(i);
			int toFace = layout.faceIndexAt(Mth.floor(transitions.get(i).position().x));
			// Vanilla moves the passengers by their unturned offset and turns only their yaw. Each passenger gets its
			// own turned transition after the vehicle, so that a rider keeps its view.
			List<Entity> passengers = new ArrayList<>();
			entity.getIndirectPassengers().forEach(passengers::add);
			List<TeleportTransition> passengerTransitions = new ArrayList<>();
			for (Entity passenger : passengers) {
				passengerTransitions.add(transition(level, layout, passenger, toFace).transitionAsPassenger());
			}
			stopPath(entity.teleport(transitions.get(i)));
			for (int j = 0; j < passengers.size(); j++) {
				TeleportTransition passengerTransition = passengerTransitions.get(j);
				Entity passenger = passengers.get(j).teleport(passengerTransition);
				stopPath(passenger);
				// The client ignores the position packet of a player who rides, so the turned look goes separately.
				if (passenger instanceof ServerPlayer player) {
					player.connection.send(new ClientboundPlayerRotationPacket(passengerTransition.yRot(), false, passengerTransition.xRot(), false));
				}
			}
		}
	}

	/** A path holds block positions of the old region. The mob plans a new one on its next tick. */
	private static void stopPath(@Nullable Entity entity) {
		if (entity instanceof Mob mob) {
			mob.getNavigation().stop();
		}
	}

	/**
	 * True when the entity at the new feet position is free of blocks and has ground under it in the new frame.
	 * Without the ground, a jump over the diagonal would cross, fall in the new gravity and cross back.
	 */
	private static boolean landsOn(ServerLevel level, Entity entity, Vec3 feet) {
		AABB box = entity.getBoundingBox().move(feet.subtract(entity.position()));
		return level.noBlockCollision(entity, box) && !level.noBlockCollision(entity, box.move(0.0, -GROUND_DEPTH, 0.0));
	}

	private static TeleportTransition transition(ServerLevel level, SeamLayout layout, Entity entity, int toFace) {
		int[] m = layout.motion(layout.faceIndexAt(Mth.floor(entity.getX())), toFace);
		// The body turns about its center, so the entity stands upright in the new frame at the same place.
		double halfHeight = entity.getBbHeight() / 2.0;
		Vec3 center = rotate(m, entity.position().add(0.0, halfHeight, 0.0)).add(m[9], m[10], m[11]);
		Vec3 look = rotate(m, Vec3.directionFromRotation(entity.getXRot(), entity.getYRot()));
		// The turned downward direction points away from the edge, the way the entity moves on.
		Vec3 away = rotate(m, new Vec3(0.0, -1.0, 0.0));
		float yRot;
		float xRot;
		if (entity instanceof Player) {
			// The camera keeps the exact turned look. A look along the edge normal turns vertical, where it gives no
			// yaw, so the away direction gives it.
			Vec3 heading = look.x * look.x + look.z * look.z < 1.0E-6 ? away : look;
			yRot = (float) Math.toDegrees(Math.atan2(-heading.x, heading.z));
			xRot = (float) Math.toDegrees(Math.asin(Mth.clamp(-look.y, -1.0, 1.0)));
		} else {
			// A body rolls over the edge: the part of its facing toward the edge, which turns up, points away from the
			// edge instead. Else a boat that comes at the edge a little to the side faces along the edge.
			Vec3 facing = rotate(m, Vec3.directionFromRotation(0.0F, entity.getYRot()));
			Vec3 heading = new Vec3(facing.x + facing.y * away.x, 0.0, facing.z + facing.y * away.z);
			yRot = (float) Math.toDegrees(Math.atan2(-heading.x, heading.z));
			xRot = entity.getXRot();
		}
		return new TeleportTransition(
			level, center.subtract(0.0, halfHeight, 0.0), rotate(m, entity.getDeltaMovement()), yRot, xRot, TeleportTransition.DO_NOTHING
		);
	}

	private static Vec3 rotate(int[] m, Vec3 v) {
		return new Vec3(
			m[0] * v.x + m[1] * v.y + m[2] * v.z,
			m[3] * v.x + m[4] * v.y + m[5] * v.z,
			m[6] * v.x + m[7] * v.y + m[8] * v.z
		);
	}
}
