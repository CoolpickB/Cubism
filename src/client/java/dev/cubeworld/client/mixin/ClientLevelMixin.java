package dev.cubeworld.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cubeworld.client.SeamClient;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
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
	@Unique private final int[] cubeworld$owner = new int[3];

	@WrapOperation(method = "doAnimateTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos$MutableBlockPos;set(III)Lnet/minecraft/core/BlockPos$MutableBlockPos;"))
	private BlockPos.MutableBlockPos cubeworld$animateAcrossSeam(BlockPos.MutableBlockPos pos, int x, int y, int z, Operation<BlockPos.MutableBlockPos> original) {
		SeamLayout layout = SeamClient.layout;
		int[] owner = this.cubeworld$owner;
		if (layout != null && layout.inRegion(x, z) && !layout.insideWedge(x, y, z) && layout.ownerStorage(x, y, z, owner)) {
			return original.call(pos, owner[0], owner[1], owner[2]);
		}
		return original.call(pos, x, y, z);
	}
}
