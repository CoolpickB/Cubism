package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.math.OctahedralGroup;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Outside its wedge, a face region holds only cleared air, so an entity near the diagonal would lose its floor
 * before it crosses to the next face. Collision there reads the block from the face that owns the position.
 *
 * <p>The collision shape comes from the owner position and turns with the face motion, so a slab on the next face
 * is a side slab here, as the seam rendering shows it.
 */
@Mixin(BlockCollisions.class)
public abstract class BlockCollisionsMixin {
	@Shadow @Final private CollisionGetter collisionGetter;

	@Unique private @Nullable SeamLayout layout;
	@Unique private boolean layoutResolved;
	@Unique private final int[] owner = new int[3];
	/** The rotation from the owner face into this region for the last block read across the seam, else null. */
	@Unique private @Nullable OctahedralGroup ownerRotation;

	@WrapOperation(
		method = "computeNext",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/BlockGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;")
	)
	private BlockState cubeworld$collideAcrossSeam(BlockGetter chunk, BlockPos pos, Operation<BlockState> original) {
		if (!this.layoutResolved) {
			this.layout = this.collisionGetter instanceof Level level ? SeamLayout.of(level) : null;
			this.layoutResolved = true;
		}
		SeamLayout layout = this.layout;
		this.ownerRotation = null;
		if (layout == null || layout.insideWedge(pos.getX(), pos.getY(), pos.getZ())
			|| !layout.ownerStorage(pos.getX(), pos.getY(), pos.getZ(), this.owner)) {
			return original.call(chunk, pos);
		}
		BlockGetter ownerChunk = this.collisionGetter.getChunkForCollisions(
			SectionPos.blockToSectionCoord(this.owner[0]), SectionPos.blockToSectionCoord(this.owner[2])
		);
		if (ownerChunk == null) {
			return original.call(chunk, pos);
		}
		this.ownerRotation = SeamInteraction.blockRotation(layout, layout.faceIndexAt(this.owner[0]), layout.faceIndexAt(pos.getX()));
		return ownerChunk.getBlockState(new BlockPos(this.owner[0], this.owner[1], this.owner[2]));
	}

	@WrapOperation(
		method = "computeNext",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/CollisionContext;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;")
	)
	private VoxelShape cubeworld$ownerShape(
		CollisionContext context, BlockState state, CollisionGetter getter, BlockPos pos, Operation<VoxelShape> original
	) {
		if (this.ownerRotation == null) {
			return original.call(context, state, getter, pos);
		}
		VoxelShape shape = original.call(context, state, getter, new BlockPos(this.owner[0], this.owner[1], this.owner[2]));
		return shape == Shapes.block() || shape.isEmpty() ? shape : Shapes.rotate(shape, this.ownerRotation);
	}
}
