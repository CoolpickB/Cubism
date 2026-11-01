package dev.cubeworld.seam;

import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
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
	public static final double MARGIN = 1.0;
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
		// landsOn can wait for a chunk to load, and the tasks that run meanwhile add entities to the level.
		List<Entity> entities = new ArrayList<>();
		level.getAllEntities().forEach(entities::add);
		for (Entity entity : entities) {
			// A passenger crosses with the vehicle that carries it. A player in flight crosses on its own client, which
			// the server follows when the movement arrives: a crossing from here would pull a fast glider back.
			if (entity.isPassenger() || entity instanceof Player player && flies(player)) {
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
	 * True when the entity at the new feet position is free of blocks and has ground under it or a fluid around it in
	 * the new frame. Without the ground, a jump over the diagonal would cross, fall in the new gravity and cross back.
	 * A swimmer has no ground, and before the fluid counted it crossed only at the forced margin.
	 */
	private static boolean landsOn(ServerLevel level, Entity entity, Vec3 feet) {
		AABB box = entity.getBoundingBox().move(feet.subtract(entity.position()));
		// A block read in a chunk that is not loaded makes the server thread wait for it, and a chunk that has to
		// generate first held a tick for 8 s. The entity crosses once the chunk is there.
		for (int chunkX = SectionPos.blockToSectionCoord(box.minX); chunkX <= SectionPos.blockToSectionCoord(box.maxX); chunkX++) {
			for (int chunkZ = SectionPos.blockToSectionCoord(box.minZ); chunkZ <= SectionPos.blockToSectionCoord(box.maxZ); chunkZ++) {
				if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
					return false;
				}
			}
		}
		return level.noBlockCollision(entity, box)
			&& (!level.noBlockCollision(entity, box.move(0.0, -GROUND_DEPTH, 0.0)) || level.containsAnyLiquid(box));
	}

	/** True when the player flies with an elytra or in creative or spectator mode, which uses the frame of the air. */
	public static boolean flies(Player player) {
		return !player.isPassenger() && (player.isFallFlying() || player.getAbilities().flying);
	}

	/**
	 * The eye of a flying player as the client camera shows it: along the up of the air, about the center of the body.
	 * The crosshair starts there, so rays of the server that must hit the same block start there too.
	 */
	public static Vec3 flightEye(Player player, Vec3 vanillaEye) {
		SeamLayout layout = SeamLayout.of(player.level());
		if (layout == null || !flies(player)) {
			return vanillaEye;
		}
		Vec3 center = player.position().add(0.0, player.getBbHeight() / 2.0, 0.0);
		Vec3 gravity = layout.airGravity(center.x, center.y, center.z);
		return gravity == null ? vanillaEye : center.subtract(gravity.scale(player.getEyeHeight() - player.getBbHeight() / 2.0));
	}

	/** Where a crossing to another face puts an entity: its position, velocity and look in the region of that face. */
	public record Turned(Vec3 position, Vec3 velocity, float yRot, float xRot) {}

	private static TeleportTransition transition(ServerLevel level, SeamLayout layout, Entity entity, int toFace) {
		Turned turned = turned(layout, entity, toFace);
		return new TeleportTransition(level, turned.position(), turned.velocity(), turned.yRot(), turned.xRot(), TeleportTransition.DO_NOTHING);
	}

	public static Turned turned(SeamLayout layout, Entity entity, int toFace) {
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
		} else if (entity instanceof Projectile && entity.getDeltaMovement().lengthSqr() > 1.0E-7) {
			// A projectile points along its flight, with its own sign convention, and eases to that direction each
			// tick. Any other rotation here shows as a swing after the crossing.
			Vec3 flight = rotate(m, entity.getDeltaMovement());
			yRot = (float) Math.toDegrees(Math.atan2(flight.x, flight.z));
			xRot = (float) Math.toDegrees(Math.atan2(flight.y, flight.horizontalDistance()));
		} else {
			// A body rolls over the edge: the part of its facing toward the edge, which turns up, points away from the
			// edge instead. Else a boat that comes at the edge a little to the side faces along the edge.
			Vec3 facing = rotate(m, Vec3.directionFromRotation(0.0F, entity.getYRot()));
			Vec3 heading = new Vec3(facing.x + facing.y * away.x, 0.0, facing.z + facing.y * away.z);
			yRot = (float) Math.toDegrees(Math.atan2(-heading.x, heading.z));
			xRot = entity.getXRot();
		}
		return new Turned(center.subtract(0.0, halfHeight, 0.0), rotate(m, entity.getDeltaMovement()), yRot, xRot);
	}

	private static Vec3 rotate(int[] m, Vec3 v) {
		return new Vec3(
			m[0] * v.x + m[1] * v.y + m[2] * v.z,
			m[3] * v.x + m[4] * v.y + m[5] * v.z,
			m[6] * v.x + m[7] * v.y + m[8] * v.z
		);
	}
}
