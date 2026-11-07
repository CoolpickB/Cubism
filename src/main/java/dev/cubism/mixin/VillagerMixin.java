package dev.cubism.mixin;

import dev.cubism.mixin.AbstractVillagerInvoker;
import dev.cubism.loot.FiniteLoot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A villager adds the trade set {@code cubism:<profession>/level_<level>} after the vanilla set of its level, in a
 * finite-loot world. Other offers keep their vanilla chance.
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {
	@Inject(method = "updateTrades", at = @At("TAIL"))
	private void cubism$addFiniteLootTrades(ServerLevel level, CallbackInfo ci) {
		Villager self = (Villager) (Object) this;
		VillagerData data = self.getVillagerData();
		data.profession().unwrapKey().ifPresent(profession -> FiniteLoot.addTradeSet(
			(AbstractVillagerInvoker) self, level, self.getOffers(), profession.identifier().getPath() + "/level_" + data.level()
		));
	}
}
