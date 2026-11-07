package dev.cubism.client;

import dev.cubism.debug.LoadTrace;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Extra lines for the trace that {@code /cubetrace} starts: slow frames, the fall of the player near an edge, and
 * entities that are drawn somewhere odd. Nothing is written while no trace runs.
 */
public final class DebugTrace {
	/** A frame this much longer than the average of the last frames is slow. */
	private static final double SLOW_FACTOR = 2.0;
	private static final long SLOW_MIN_NANOS = 25_000_000L;
	/** An entity whose old position is this far from its position is drawn between the two for one frame. */
	private static final double ENTITY_JUMP = 6.0;
	/** An entity this far past the diagonal of its own region stands in cleared air. */
	private static final double PAST_DIAGONAL = 3.0;

	private static double averageNanos;

	private DebugTrace() {}

	public static void frame(long nanos) {
		if (averageNanos > 0.0 && nanos > SLOW_MIN_NANOS && nanos > averageNanos * SLOW_FACTOR) {
			LocalPlayer player = Minecraft.getInstance().player;
			LoadTrace.event(String.format(Locale.ROOT, "slow frame %.1f ms (avg %.1f) player=%s face=%d", nanos / 1e6, averageNanos / 1e6,
				player == null ? "-" : vec(player.position()), player == null ? -1 : face(player.getX(), player.getZ())));
		}
		averageNanos = averageNanos == 0.0 ? nanos : averageNanos * 0.95 + nanos * 0.05;
	}

	/** One line per tick while the player falls or jumps. */
	public static void fall(LocalPlayer player) {
		SeamLayout layout = SeamClient.layout;
		if (!LoadTrace.active() || layout == null || player.onGround() || player.getAbilities().flying) {
			return;
		}
		Vec3 center = player.getBoundingBox().getCenter();
		@Nullable Vec3 pull = layout.airGravity(center.x, center.y, center.z);
		Vec3 velocity = player.getDeltaMovement();
		LoadTrace.event(String.format(Locale.ROOT, "fall pos=%s face=%d vel=%s speed=%.3f pull=%s fallFlying=%b",
			vec(player.position()), face(player.getX(), player.getZ()), vec(velocity), velocity.length(), pull == null ? "down" : vec(pull), player.isFallFlying()));
	}

	/** Logs an entity that is drawn between two far places, outside every region, or in the cleared air of its region. */
	public static void entity(Entity entity, float partialTicks) {
		SeamLayout layout = SeamClient.layout;
		if (!LoadTrace.active() || layout == null) {
			return;
		}
		Vec3 pos = entity.position();
		// The renderer lerps from xo. oldPosition() is xOld, which a new living entity leaves at the origin until it ticks.
		Vec3 old = new Vec3(entity.xo, entity.yo, entity.zo);
		int face = face(pos.x, pos.z);
		int oldFace = face(old.x, old.z);
		Vec3 center = entity.getBoundingBox().getCenter();
		String why = old.distanceTo(pos) > ENTITY_JUMP ? "jump"
			: face != oldFace ? "oldInOtherRegion"
			: face < 0 ? "noRegion"
			: layout.crossingFace(center.x, center.y, center.z, PAST_DIAGONAL) >= 0 ? "pastDiagonal"
			: null;
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		if (why == null && face >= 0 && face != face(camera.x, camera.z) && entity.onGround() && entity.tickCount % 20 == 0) {
			why = groundProblem(entity);
		}
		if (why == null) {
			return;
		}
		LoadTrace.event(String.format(Locale.ROOT, "entity %s %s id=%d pos=%s old=%s face=%d oldFace=%d age=%d partial=%.2f camera=%s cameraFace=%d",
			why, net.minecraft.world.entity.EntityType.getKey(entity.getType()), entity.getId(), vec(pos), vec(old), face, oldFace,
			entity.tickCount, partialTicks, vec(camera), face(camera.x, camera.z)));
	}

	/**
	 * An entity of another face that stands on ground the client has no blocks for, or does not draw, floats in the
	 * sky where its face should show. Returns null when the ground under it is there and drawn.
	 */
	private static @Nullable String groundProblem(Entity entity) {
		net.minecraft.world.level.Level level = entity.level();
		net.minecraft.core.BlockPos below = entity.blockPosition().below();
		boolean solid = false;
		for (int i = 0; i < 3 && !solid; i++) {
			solid = !level.getBlockState(below.below(i)).getCollisionShape(level, below.below(i)).isEmpty();
		}
		int chunkX = below.getX() >> 4;
		int chunkZ = below.getZ() >> 4;
		boolean drawn = !SeamClient.SODIUM || !(level instanceof net.minecraft.client.multiplayer.ClientLevel client)
			|| dev.cubism.client.compat.sodium.SodiumLink.isReady(client, chunkX, chunkZ);
		return solid && drawn ? null : solid ? "groundNotDrawn" : "noGroundBlocks";
	}

	private static int face(double x, double z) {
		SeamLayout layout = SeamClient.layout;
		return layout == null ? -1 : SeamInteraction.regionFace(layout, x, z);
	}

	private static String vec(Vec3 v) {
		return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
	}
}
