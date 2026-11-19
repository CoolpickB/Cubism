package dev.cubism.seam;

import net.minecraft.world.phys.shapes.VoxelShape;
import dev.cubism.gravity.GravityEntityAccess;
import dev.cubism.gravity.GravityLock;
import dev.cubism.cube.SeamLayout;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
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
	public static final double FORCED_MARGIN = 3.0;
	/** Spectators fly near the corners, where three diagonals meet and each crossing turns the view. */
	private static final double SPECTATOR_MARGIN = 4.0;
	/**
	 * A player crosses on its own client, at {@link #MARGIN}, and the server follows with the first movement in the new
	 * region. The server crosses a player itself only when the client did not, so that its late teleport does not pull
	 * back a client that crossed already. The same goes for a vehicle that a player steers.
	 */
	private static final double PLAYER_BACKUP_MARGIN = 4.0;
	/** The client moves its player into and out of space, and the server does it only this many blocks later. */
	private static final double PLAYER_SPACE_SLACK = 4.0;
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
			Vec3 middle = entity.getBoundingBox().getCenter();
			int region = SeamInteraction.regionFace(layout, middle.x, middle.z);
			int toSpace = spaceTarget(layout, entity, region, entity instanceof ServerPlayer || ridden(entity) != null ? PLAYER_SPACE_SLACK : 0.0);
			if (toSpace >= 0) {
				crossing.add(entity);
				transitions.add(transition(level, layout, entity, toSpace));
				continue;
			}
			// In space no diagonal is near, and a lock pulls along the down of its face (see GravityLock.lockFace).
			if (region == SeamLayout.SPACE) {
				continue;
			}
			// A forced face moves the entity there at once. A player moves on its own client, as for a crossing.
			int forced = GravityLock.forcedFace(entity);
			if (forced >= 0 && !(entity instanceof Player) && forced != SeamInteraction.regionFace(layout, entity.getX(), entity.getZ())) {
				crossing.add(entity);
				transitions.add(transition(level, layout, entity, forced));
				continue;
			}
			if (GravityLock.holdsFace(entity)) {
				continue;
			}
			Vec3 center = entity.getBoundingBox().getCenter();
			if (dev.cubism.debug.LoadTrace.active() && !(entity instanceof Player) && SeamInteraction.regionFace(layout, center.x, center.z) < 0) {
				dev.cubism.debug.LoadTrace.event("entity outside every face: " + entity);
			}
			boolean spectator = entity.isSpectator();
			int face = layout.crossingFace(center.x, center.y, center.z, spectator ? SPECTATOR_MARGIN : entity instanceof ServerPlayer || ridden(entity) != null ? PLAYER_BACKUP_MARGIN : MARGIN);
			if (face < 0) {
				continue;
			}
			face = layout.crossingTarget(region, face, center.x, center.y, center.z);
			TeleportTransition transition = transition(level, layout, entity, face);
			if (spectator || face == SeamLayout.SPACE || layout.crossingFace(center.x, center.y, center.z, FORCED_MARGIN) >= 0 || landsOn(level, entity, transition.position())) {
				crossing.add(entity);
				transitions.add(transition);
			}
		}
		for (int i = 0; i < crossing.size(); i++) {
			Entity entity = crossing.get(i);
			Vec3 to = transitions.get(i).position();
			int toFace = SeamInteraction.regionFace(layout, to.x, to.z);
			if (toFace == SeamLayout.SPACE) {
				((GravityEntityAccess) entity).cubism$setSpaceFace(SeamInteraction.regionFace(layout, entity.getX(), entity.getZ()));
			}
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

	/**
	 * The region that an entity in {@code region} moves to for space, see {@link SeamLayout#spaceHandoff}, or -1. A
	 * Steadfast lock brings it back only to the face of the lock.
	 */
	public static int spaceTarget(SeamLayout layout, Entity entity, int region, double slack) {
		Vec3 center = entity.getBoundingBox().getCenter();
		return layout.spaceHandoff(region, center.x, center.y, center.z, GravityLock.lockFace(layout, entity), slack);
	}

	/**
	 * The pull on an entity in space, in the frame of the space region, or null when the entity is not in space. A
	 * Steadfast lock pulls along the down of its face at full strength, without end. Else the pull is
	 * {@code CubeGravity.spacePull}, which is zero past the exosphere.
	 */
	public static @Nullable Vec3 spacePull(SeamLayout layout, Entity entity) {
		if (layout.regionAt(Mth.floor(entity.getX()), Mth.floor(entity.getZ())) != SeamLayout.SPACE) {
			return null;
		}
		int lock = GravityLock.lockFace(layout, entity);
		if (lock >= 0) {
			return layout.faceDown(lock);
		}
		Vec3 center = entity.getBoundingBox().getCenter();
		Vec3 p = layout.cubeOffset(SeamLayout.SPACE, center.x, center.y, center.z);
		Vec3 pull = layout.gravity.spacePull(p.x, p.y, p.z);
		return pull == null ? Vec3.ZERO : pull;
	}

	/** False on the server while a chunk under the box is not loaded, where a block read would wait for it. */
	private static boolean loaded(Level level, AABB box) {
		if (level instanceof ServerLevel server) {
			for (int chunkX = SectionPos.blockToSectionCoord(box.minX); chunkX <= SectionPos.blockToSectionCoord(box.maxX); chunkX++) {
				for (int chunkZ = SectionPos.blockToSectionCoord(box.minZ); chunkZ <= SectionPos.blockToSectionCoord(box.maxZ); chunkZ++) {
					if (!server.getChunkSource().hasChunk(chunkX, chunkZ)) {
						return false;
					}
				}
			}
		}
		return true;
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
	public static boolean landsOn(Level level, Entity entity, Vec3 feet) {
		AABB box = entity.getBoundingBox().move(feet.subtract(entity.position()));
		// A block read in a chunk that is not loaded makes the server thread wait for it, and a chunk that has to
		// generate first held a tick for 8 s. The entity crosses once the chunk is there.
		if (!loaded(level, box)) {
			return false;
		}
		return level.noBlockCollision(entity, box)
			&& (!level.noBlockCollision(entity, box.move(0.0, -GROUND_DEPTH, 0.0)) || level.containsAnyLiquid(box));
	}

	/** The player that steers a vehicle, whose client moves the vehicle and so makes its crossing, else null. */
	public static @Nullable Player ridden(Entity vehicle) {
		return vehicle.getControllingPassenger() instanceof Player player ? player : null;
	}

	/** True when the player flies with an elytra or in creative or spectator mode, which uses the frame of the air. */
	public static boolean flies(Player player) {
		return !player.isPassenger() && (player.isFallFlying() || player.getAbilities().flying);
	}

	/**
	 * The eye of a player as the client camera shows it: along the up of the air, about the center of the body.
	 * The crosshair starts there, so rays of the server that must hit the same block start there too.
	 */
	public static Vec3 shownEye(Player player, Vec3 vanillaEye) {
		SeamLayout layout = SeamLayout.of(player.level());
		if (layout == null || GravityLock.locked(player)) {
			return vanillaEye;
		}
		Vec3 center = player.position().add(0.0, player.getBbHeight() / 2.0, 0.0);
		Vec3 gravity = layout.gravity.airGravity(center.x, center.y, center.z);
		return gravity == null ? vanillaEye : clearEye(player, vanillaEye, center.subtract(gravity.scale(player.getEyeHeight() - player.getBbHeight() / 2.0)));
	}

	/**
	 * Room kept between the eye and a block, so that the corners of the near plane of the view (0.05 ahead) do not
	 * reach into it either. With a field of view of 110 (Quake Pro) and the sprint widening on a wide screen they lie
	 * about 0.21 from the eye. The vanilla eye keeps 0.3 to the side of the body box.
	 */
	private static final double EYE_MARGIN = 0.22;

	/**
	 * The eye along the up of the air frame, held out of blocks. That eye leaves the upright box of the body over an
	 * edge, by up to 0.2 blocks at a seam, and against a wall it showed and picked through the wall. It goes back on the
	 * way to the vanilla eye, which stays inside the box, to the last point where it is clear. The first step is
	 * found walking, so that a thin block on the way still stops it.
	 */
	public static Vec3 clearEye(Entity entity, Vec3 vanillaEye, Vec3 wanted) {
		if (vanillaEye.distanceToSqr(wanted) < 1.0E-8) {
			return wanted;
		}
		Level level = entity.level();
		// A block within the margin of the vanilla eye, such as a ceiling at the top of a jump, only stops the eye where
		// it comes closer than the vanilla eye is. Vanilla shows it well there, and with the full margin the eye snapped
		// back to the vanilla eye on every jump.
		List<AABB> near = boxes(level, entity, vanillaEye);
		int steps = 8;
		for (int i = 1; i <= steps; i++) {
			if (!eyeClear(level, entity, vanillaEye, vanillaEye.lerp(wanted, (double) i / steps), near)) {
				double low = (i - 1.0) / steps;
				double high = (double) i / steps;
				for (int j = 0; j < 4; j++) {
					double mid = (low + high) * 0.5;
					if (eyeClear(level, entity, vanillaEye, vanillaEye.lerp(wanted, mid), near)) {
						low = mid;
					} else {
						high = mid;
					}
				}
				return vanillaEye.lerp(wanted, low);
			}
		}
		return wanted;
	}

	private static boolean eyeClear(Level level, Entity entity, Vec3 vanillaEye, Vec3 eye, List<AABB> near) {
		for (AABB box : boxes(level, entity, eye)) {
			double allowed = near.contains(box) ? gap(vanillaEye, box) : EYE_MARGIN;
			if (gap(eye, box) < allowed - 1.0E-6) {
				return false;
			}
		}
		return true;
	}

	/** The distance from a point to a box along the axis where it is largest, as the margin cube measures it. */
	private static double gap(Vec3 p, AABB box) {
		double x = Math.max(Math.max(box.minX - p.x, p.x - box.maxX), 0.0);
		double y = Math.max(Math.max(box.minY - p.y, p.y - box.maxY), 0.0);
		double z = Math.max(Math.max(box.minZ - p.z, p.z - box.maxZ), 0.0);
		return Math.max(x, Math.max(y, z));
	}

	private static List<AABB> boxes(Level level, Entity entity, Vec3 eye) {
		AABB margin = AABB.ofSize(eye, 2.0 * EYE_MARGIN, 2.0 * EYE_MARGIN, 2.0 * EYE_MARGIN);
		List<AABB> boxes = new ArrayList<>();
		for (VoxelShape shape : level.getBlockCollisions(entity, margin)) {
			for (AABB box : shape.toAabbs()) {
				if (box.intersects(margin)) {
					boxes.add(box);
				}
			}
		}
		return boxes;
	}

	/** Where a crossing to another face puts an entity: its position, velocity and look in the region of that face. */
	public record Turned(Vec3 position, Vec3 velocity, float yRot, float xRot) {}

	private static TeleportTransition transition(ServerLevel level, SeamLayout layout, Entity entity, int toFace) {
		Turned turned = turned(layout, entity, toFace);
		return new TeleportTransition(level, turned.position(), turned.velocity(), turned.yRot(), turned.xRot(), TeleportTransition.DO_NOTHING);
	}

	public static Turned turned(SeamLayout layout, Entity entity, int toFace) {
		int[] m = layout.motion(SeamInteraction.regionFace(layout, entity.getX(), entity.getZ()), toFace);
		// The body turns about its center, so the entity stands upright in the new frame at the same place.
		double halfHeight = entity.getBbHeight() / 2.0;
		Vec3 center = SeamInteraction.rotate(m, entity.position().add(0.0, halfHeight, 0.0)).add(m[9], m[10], m[11]);
		Vec3 look = SeamInteraction.rotate(m, Vec3.directionFromRotation(entity.getXRot(), entity.getYRot()));
		// The turned downward direction points away from the edge, the way the entity moves on.
		Vec3 away = SeamInteraction.rotate(m, new Vec3(0.0, -1.0, 0.0));
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
			Vec3 flight = SeamInteraction.rotate(m, entity.getDeltaMovement());
			yRot = (float) Math.toDegrees(Math.atan2(flight.x, flight.z));
			xRot = (float) Math.toDegrees(Math.atan2(flight.y, flight.horizontalDistance()));
		} else {
			// A body rolls over the edge: the part of its facing toward the edge, which turns up, points away from the
			// edge instead. Else a boat that comes at the edge a little to the side faces along the edge.
			Vec3 facing = SeamInteraction.rotate(m, Vec3.directionFromRotation(0.0F, entity.getYRot()));
			Vec3 heading = new Vec3(facing.x + facing.y * away.x, 0.0, facing.z + facing.y * away.z);
			yRot = (float) Math.toDegrees(Math.atan2(-heading.x, heading.z));
			xRot = entity.getXRot();
		}
		return new Turned(free(entity, center.subtract(0.0, halfHeight, 0.0)), SeamInteraction.rotate(m, entity.getDeltaMovement()), yRot, xRot);
	}

	/**
	 * The nearest place to the turned feet where the body box is free of blocks, up to {@link #FREE_SEARCH} away. The box
	 * stands upright in the new frame, and before the crossing it lay along the old one, so a tall box can stick into
	 * a block beside it. On the client the server then refused the move and set the player back, and the camera looked
	 * through the world for a frame. A box that stays stuck keeps the turned feet.
	 */
	private static Vec3 free(Entity entity, Vec3 feet) {
		Level level = entity.level();
		AABB box = entity.getBoundingBox().move(feet.subtract(entity.position()));
		if (!loaded(level, box.inflate(FREE_SEARCH)) || level.noBlockCollision(entity, box)) {
			return feet;
		}
		for (double d = FREE_STEP; d <= FREE_SEARCH + 1.0E-9; d += FREE_STEP) {
			for (Direction direction : FREE_ORDER) {
				Vec3 offset = new Vec3(direction.getStepX() * d, direction.getStepY() * d, direction.getStepZ() * d);
				if (level.noBlockCollision(entity, box.move(offset))) {
					return feet.add(offset);
				}
			}
		}
		return feet;
	}

	private static final double FREE_STEP = 0.05;
	private static final double FREE_SEARCH = 1.0;
	private static final Direction[] FREE_ORDER = {Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
}
