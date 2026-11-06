package dev.cubism.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubism.client.SeamClient;
import dev.cubism.seam.SeamInteraction;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The ambient block tick plays the sounds and particles of blocks near the camera, such as flowing water, lava, fire
 * and torches. It picks positions around the camera in its own region, where the blocks of the next face are cleared
 * air outside the wedge. A position there now goes to the block that it stands for, and the sounds and particles of
 * that block move to where it shows, see {@link SoundEngineMixin} and {@link ParticleEngineMixin}.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Unique private final int[] cubism$owner = new int[3];

	@WrapOperation(method = "doAnimateTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos$MutableBlockPos;set(III)Lnet/minecraft/core/BlockPos$MutableBlockPos;"))
	private BlockPos.MutableBlockPos cubism$animateAcrossSeam(BlockPos.MutableBlockPos pos, int x, int y, int z, Operation<BlockPos.MutableBlockPos> original) {
		SeamLayout layout = SeamClient.layout;
		int[] owner = this.cubism$owner;
		if (layout != null && layout.inRegion(x, z) && !layout.insideWedge(x, y, z) && layout.ownerStorage(x, y, z, owner)) {
			return original.call(pos, owner[0], owner[1], owner[2]);
		}
		return original.call(pos, x, y, z);
	}

	@Shadow private int serverSimulationDistance;

	/** An entity that dies on another face is in range where it shows, not where it is stored, so it falls over and turns red. */
	@ModifyReturnValue(method = "shouldTickDeath", at = @At("RETURN"))
	private boolean cubism$deathAcrossSeam(boolean inRange, @Local(argsOnly = true) Entity entity) {
		SeamLayout layout = SeamClient.layout;
		Entity player = Minecraft.getInstance().player;
		if (inRange || layout == null || player == null) {
			return inRange;
		}
		Vec3 shown = SeamInteraction.intoRegionOf(layout, entity.position(), player.getX(), player.getZ());
		return ChunkPos.containing(BlockPos.containing(shown)).getChessboardDistance(player.chunkPosition()) <= this.serverSimulationDistance;
	}
}
