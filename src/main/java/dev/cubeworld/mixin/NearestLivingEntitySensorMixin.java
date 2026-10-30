package dev.cubeworld.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.cubeworld.seam.SeamInteraction;
import dev.cubeworld.worldgen.SeamLayout;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.sensing.NearestLivingEntitySensor;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.function.Predicate;

/** Brain-based mobs, as piglins and villagers, sense the entities across a seam. The sort and the visibility use the distance and the line of sight across seams. */
@Mixin(NearestLivingEntitySensor.class)
public abstract class NearestLivingEntitySensorMixin {
	@WrapOperation(method = "doTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
	private <T> List<T> cubeworld$senseAcrossSeams(
		ServerLevel level, Class<T> type, AABB box, Predicate<? super T> filter, Operation<List<T>> original, @Local(argsOnly = true) LivingEntity body
	) {
		SeamLayout layout = SeamLayout.of(level);
		return layout == null ? original.call(level, type, box, filter)
			: SeamInteraction.acrossSeams(layout, body.getX(), body.getZ(), box, area -> original.call(level, type, area, filter));
	}
}
