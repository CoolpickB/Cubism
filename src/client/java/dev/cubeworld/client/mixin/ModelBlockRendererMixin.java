package dev.cubeworld.client.mixin;

import dev.cubeworld.client.render.CubeQuadRotator;
import dev.cubeworld.math.CubePlanetMath;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererMixin {
	@Shadow @Final private boolean cull;
	@Shadow @Final private BlockPos.MutableBlockPos scratchPos;

	@Unique private Direction cubeworld$currentGravity = Direction.DOWN;
	@Unique private BlockPos cubeworld$currentPos = BlockPos.ZERO;

	@Inject(method = "tesselateBlock", at = @At("HEAD"))
	private void cubeworld$captureBlockOrientation(
		BlockQuadOutput output,
		float x,
		float y,
		float z,
		BlockAndTintGetter level,
		BlockPos pos,
		BlockState state,
		BlockStateModel model,
		long seed,
		CallbackInfo ci
	) {
		this.cubeworld$currentPos = pos;
		if (CubePlanetMath.isClientCubeActive()) {
			Direction outward = CubePlanetMath.getPyramidOutwardFace(
				pos.getX(),
				pos.getY(),
				pos.getZ(),
				CubePlanetMath.getClientCenterY()
			);
			this.cubeworld$currentGravity = outward.getOpposite();
		} else {
			this.cubeworld$currentGravity = Direction.DOWN;
		}
	}

	@Redirect(
		method = "tesselateBlock",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getOffset(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"
		)
	)
	private Vec3 cubeworld$rotateBlockModelOffset(BlockState state, BlockPos pos) {
		Vec3 rawOffset = state.getOffset(pos);
		Direction grav = this.cubeworld$currentGravity;
		if (grav == Direction.DOWN || rawOffset == Vec3.ZERO) {
			return rawOffset;
		}
		return CubePlanetMath.localToWorld(rawOffset, grav);
	}

	@Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
	private void cubeworld$shouldRenderRotatedFace(
		BlockAndTintGetter level,
		BlockState state,
		Direction modelDirection,
		BlockPos neighborPos,
		CallbackInfoReturnable<Boolean> cir
	) {
		Direction grav = this.cubeworld$currentGravity;
		if (grav == Direction.DOWN) {
			return;
		}
		Direction worldDir = CubePlanetMath.rotateDirection(modelDirection, grav);
		this.scratchPos.setWithOffset(this.cubeworld$currentPos, worldDir);
		if (!this.cull) {
			cir.setReturnValue(true);
			return;
		}
		BlockState worldNeighbor = level.getBlockState(this.scratchPos);
		cir.setReturnValue(Block.shouldRenderFace(state, worldNeighbor, worldDir));
	}

	@ModifyArg(
		method = "tesselateAmbientOcclusion",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/BlockModelLighter;prepareQuadAmbientOcclusion(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"
		),
		index = 3
	)
	private BakedQuad cubeworld$rotateQuadForAoLighter(BakedQuad quad) {
		return CubeQuadRotator.rotateQuad(quad, this.cubeworld$currentGravity);
	}

	@ModifyArg(
		method = "tesselateFlat",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/BlockModelLighter;prepareQuadFlat(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;ILnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"
		),
		index = 4
	)
	private BakedQuad cubeworld$rotateQuadForFlatLighter(BakedQuad quad) {
		return CubeQuadRotator.rotateQuad(quad, this.cubeworld$currentGravity);
	}

	@ModifyVariable(method = "putQuadWithTint", at = @At("HEAD"), argsOnly = true)
	private BakedQuad cubeworld$rotateQuadForOutput(BakedQuad quad) {
		return CubeQuadRotator.rotateQuad(quad, this.cubeworld$currentGravity);
	}
}
