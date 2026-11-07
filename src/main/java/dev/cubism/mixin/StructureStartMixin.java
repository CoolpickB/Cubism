package dev.cubism.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cubism.worldgen.SeamLayout;
import dev.cubism.worldgen.StartClip;
import dev.cubism.worldgen.WholeRooms;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A start lays out its pieces on the flat plane of the face it started on. It writes only the square of that face,
 * and the flat plane past an edge is written by the copy of the start that the fold made on the next face (see
 * {@link dev.cubism.worldgen.FoldedStarts}). The clip of a copy is saved with it.
 */
@Mixin(StructureStart.class)
public abstract class StructureStartMixin implements StartClip {
	private static final String CLIP_TAG = "CubeworldClip";
	private static final BoundingBox NOTHING = new BoundingBox(1 << 28, 0, 1 << 28, 1 << 28, 0, 1 << 28);

	@Shadow
	@Final
	private ChunkPos chunkPos;

	@Unique
	private @Nullable BoundingBox cubism$clip;

	@Override
	public @Nullable BoundingBox cubism$clip() {
		return this.cubism$clip;
	}

	@Override
	public void cubism$setClip(@Nullable BoundingBox clip) {
		this.cubism$clip = clip;
	}

	@WrapMethod(method = "placeInChunk")
	private void cubism$writeOnlyClip(
		WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
		BoundingBox chunkBB, ChunkPos chunkPos, Operation<Void> original
	) {
		SeamLayout layout = SeamLayout.of(level.getLevel());
		if (layout == null) {
			original.call(level, structureManager, generator, random, chunkBB, chunkPos);
			return;
		}
		BoundingBox clip = this.cubism$clip;
		if (clip == null) {
			int face = layout.faceIndexAt(this.chunkPos.getMiddleBlockX());
			int x = layout.offsetX(face);
			// The cave of the Nether is above its floor, where the wedge reaches past the square of the face. The
			// wedge, not the square, ends a structure there.
			int half = layout.nether ? layout.radius + layout.openHeight : layout.radius;
			clip = new BoundingBox(x - half, chunkBB.minY(), -half, x + half, chunkBB.maxY(), half);
		}
		int minX = Math.max(chunkBB.minX(), clip.minX());
		int minZ = Math.max(chunkBB.minZ(), clip.minZ());
		int maxX = Math.min(chunkBB.maxX(), clip.maxX());
		int maxZ = Math.min(chunkBB.maxZ(), clip.maxZ());
		BoundingBox inside = minX > maxX || minZ > maxZ ? NOTHING : new BoundingBox(minX, chunkBB.minY(), minZ, maxX, chunkBB.maxY(), maxZ);
		original.call(level, structureManager, generator, random, inside, chunkPos);
	}

	/** A room of an underground structure is drawn only by the face that holds all of it (see {@link WholeRooms}). */
	@WrapOperation(
		method = "placeInChunk",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/structure/StructurePiece;postProcess(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/core/BlockPos;)V")
	)
	private void cubism$wholeRooms(
		StructurePiece piece, WorldGenLevel level, StructureManager structureManager, ChunkGenerator generator, RandomSource random,
		BoundingBox chunkBB, ChunkPos chunkPos, BlockPos reference, Operation<Void> original
	) {
		SeamLayout layout = SeamLayout.of(level.getLevel());
		if (layout != null && layout.hingeDepth(piece.getBoundingBox().getCenter().getY()) != 0 && WholeRooms.isRoom(piece)
			&& !WholeRooms.inWedge(layout, piece.getBoundingBox())) {
			return;
		}
		original.call(piece, level, structureManager, generator, random, chunkBB, chunkPos, reference);
	}

	@ModifyReturnValue(method = "createTag", at = @At("RETURN"))
	private CompoundTag cubism$saveClip(CompoundTag tag) {
		BoundingBox clip = this.cubism$clip;
		if (clip != null) {
			tag.putIntArray(CLIP_TAG, new int[] {clip.minX(), clip.minY(), clip.minZ(), clip.maxX(), clip.maxY(), clip.maxZ()});
		}
		return tag;
	}

	@ModifyReturnValue(method = "loadStaticStart", at = @At("RETURN"))
	private static @Nullable StructureStart cubism$loadClip(
		@Nullable StructureStart start, StructurePieceSerializationContext context, CompoundTag tag, long seed
	) {
		if (start != null) {
			tag.getIntArray(CLIP_TAG).filter(box -> box.length == 6).ifPresent(box ->
				((StartClip) (Object) start).cubism$setClip(new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5])));
		}
		return start;
	}
}
