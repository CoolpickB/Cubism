package dev.cubism.mixin.mob;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.cubism.seam.SeamInteraction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.breeze.Breeze;
import net.minecraft.world.entity.monster.breeze.Shoot;
import org.spongepowered.asm.mixin.Mixin;

/** A breeze looks and shoots at a target across a seam where the target shows. The wind charge crosses the seam in flight. */
@Mixin(Shoot.class)
public abstract class BreezeShootMixin {
	@WrapMethod(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/breeze/Breeze;J)V")
	private void cubism$targetShown(ServerLevel level, Breeze breeze, long timestamp, Operation<Void> original) {
		SeamInteraction.withTargetShown(breeze, breeze.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null), () -> original.call(level, breeze, timestamp));
	}
}
