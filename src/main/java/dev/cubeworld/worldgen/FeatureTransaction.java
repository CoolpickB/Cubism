package dev.cubeworld.worldgen;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * Places a feature whole or not at all. Near the diagonal of a face, the wedge rule refuses the writes that fall into the
 * space of the next face. A feature cut there, such as an ice spike that loses its base or a dungeon that loses a wall,
 * would leave a sliver, so all of its writes are undone. A cut feature is one with a refused block next to a block that
 * it kept. A feature that loses only loose blocks, such as a flower at the side of a patch, stays.
 */
public final class FeatureTransaction {
	/** Only a feature that starts this close to the side of its wedge can reach past it. */
	private static final int REACH = 32;
	private static final ThreadLocal<@Nullable FeatureTransaction> OPEN = new ThreadLocal<>();

	private final Long2ObjectLinkedOpenHashMap<BlockState> previous = new Long2ObjectLinkedOpenHashMap<>();
	private final LongArrayList refused = new LongArrayList();

	private FeatureTransaction() {
	}

	public static boolean place(SeamLayout layout, BlockPos origin, BooleanSupplier feature, WorldGenLevel level) {
		if (OPEN.get() != null || !nearSide(layout, origin)) {
			return feature.getAsBoolean();
		}
		FeatureTransaction transaction = new FeatureTransaction();
		OPEN.set(transaction);
		boolean placed;
		try {
			placed = feature.getAsBoolean();
		} finally {
			OPEN.remove();
		}
		if (!transaction.isCut()) {
			return placed;
		}
		var entries = transaction.previous.long2ObjectEntrySet().fastIterator();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		while (entries.hasNext()) {
			var entry = entries.next();
			level.setBlock(pos.set(entry.getLongKey()), entry.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
		}
		return false;
	}

	/** Called by the region before a write. Returns the open transaction, so that the region can report the write. */
	public static @Nullable FeatureTransaction open() {
		return OPEN.get();
	}

	public void accepted(BlockPos pos, BlockState before) {
		this.previous.putIfAbsent(pos.asLong(), before);
	}

	public void refused(BlockPos pos) {
		this.refused.add(pos.asLong());
	}

	private boolean isCut() {
		for (int i = 0; i < this.refused.size(); i++) {
			long pos = this.refused.getLong(i);
			for (Direction direction : Direction.values()) {
				if (this.previous.containsKey(BlockPos.offset(pos, direction))) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean nearSide(SeamLayout layout, BlockPos pos) {
		if (!layout.inRegion(pos.getX(), pos.getZ())) {
			return false;
		}
		int x = pos.getX() - layout.offsetX(layout.faceIndexAt(pos.getX()));
		return pos.getY() - layout.centerY - Math.max(Math.abs(x), Math.abs(pos.getZ())) < REACH;
	}
}
