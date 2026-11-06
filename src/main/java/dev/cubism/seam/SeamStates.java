package dev.cubism.seam;

import com.mojang.math.OctahedralGroup;
import dev.cubism.worldgen.SeamLayout;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Turns block states from the frame of one face into the frame of a neighbor face, so that blocks next to a seam
 * see the blocks across it. Two neighbor faces are turned 90 degrees about their shared edge, and vanilla states
 * can only turn about the vertical axis. Thus a state turns through its direction, axis and side properties, and
 * the turn counts only when the turned state has the turned shape.
 *
 * <p>A state that cannot turn, such as a floor torch that would lie on a wall, stays as it is when it is a full
 * block, and shows as air when it is not. As air, the block does not hold up or connect to the blocks across the
 * seam, and they do not pop it off by mistake.
 */
public final class SeamStates {
	private static final int FACES = SeamLayout.FACES.length;
	@SuppressWarnings("unchecked")
	private static final Map<BlockState, BlockState>[] TURNED = new Map[FACES * FACES];

	static {
		for (int i = 0; i < TURNED.length; i++) {
			TURNED[i] = new ConcurrentHashMap<>();
		}
	}

	private SeamStates() {}

	public static BlockState turn(SeamLayout layout, BlockState state, int fromFace, int toFace) {
		if (fromFace == toFace) {
			return state;
		}
		return TURNED[fromFace * FACES + toFace].computeIfAbsent(state, s -> compute(layout, s, fromFace, toFace));
	}

	/**
	 * As {@link #turn}, for the meshers. A fluid block that cannot turn stays as it is, so that water still meets the
	 * water across the seam. Connected textures, such as those of Continuity, read the turned blocks too.
	 */
	public static BlockState turnForMesh(SeamLayout layout, BlockState state, int fromFace, int toFace) {
		BlockState turned = turn(layout, state, fromFace, toFace);
		return turned.isAir() && !state.getFluidState().isEmpty() ? state : turned;
	}

	/** True when a state that is not air turns into the air stand-in. */
	public static boolean unturnable(SeamLayout layout, BlockState state, int fromFace, int toFace) {
		return !state.isAir() && turn(layout, state, fromFace, toFace).isAir();
	}

	private static BlockState compute(SeamLayout layout, BlockState state, int fromFace, int toFace) {
		int[] m = layout.motion(fromFace, toFace);
		BlockState turned = turnProperties(state, m);
		if (turned != null) {
			OctahedralGroup rotation = SeamInteraction.blockRotation(layout, fromFace, toFace);
			VoxelShape expected = Shapes.rotate(shape(state), rotation);
			if (!Shapes.joinIsNotEmpty(expected, shape(turned), BooleanOp.NOT_SAME)) {
				return turned;
			}
		}
		return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) ? state : Blocks.AIR.defaultBlockState();
	}

	private static VoxelShape shape(BlockState state) {
		return state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}

	private static @Nullable BlockState turnProperties(BlockState state, int[] m) {
		BlockState out = state;
		for (Property<?> property : state.getProperties()) {
			Object value = state.getValue(property);
			Object turned;
			if (value instanceof Direction direction) {
				turned = SeamInteraction.rotate(m, direction);
			} else if (value instanceof Direction.Axis axis) {
				turned = SeamInteraction.rotate(m, Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE)).getAxis();
			} else {
				continue;
			}
			if (!property.getPossibleValues().contains(turned)) {
				return null;
			}
			out = with(out, property, turned);
		}
		// Side flags, as on vines and mushroom blocks, move with their sides. A set flag that has no side to move to
		// cannot turn.
		BlockState sides = out;
		for (BooleanProperty property : PipeBlock.PROPERTY_BY_DIRECTION.values()) {
			if (state.hasProperty(property)) {
				sides = sides.setValue(property, false);
			}
		}
		for (Map.Entry<Direction, BooleanProperty> side : PipeBlock.PROPERTY_BY_DIRECTION.entrySet()) {
			if (!state.hasProperty(side.getValue()) || !state.getValue(side.getValue())) {
				continue;
			}
			BooleanProperty target = PipeBlock.PROPERTY_BY_DIRECTION.get(SeamInteraction.rotate(m, side.getKey()));
			if (!state.hasProperty(target)) {
				return null;
			}
			sides = sides.setValue(target, true);
		}
		out = sides;
		return out;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, Object value) {
		return state.setValue(property, property.getValueClass().cast(value));
	}
}
