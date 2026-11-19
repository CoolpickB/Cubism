package dev.cubism.mixin.loot;

import dev.cubism.loot.ExtraItemSources;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A wandering trader in a world with extra item sources has an echo shard, one more common and one more uncommon trade, from sets of their own. */
@Mixin(WanderingTrader.class)
public abstract class WanderingTraderMixin {
	@Inject(method = "updateTrades", at = @At("TAIL"))
	private void cubism$addExtraItemTrades(ServerLevel level, CallbackInfo ci) {
		WanderingTrader self = (WanderingTrader) (Object) this;
		ExtraItemSources.addTradeSet((AbstractVillagerInvoker) self, level, self.getOffers(), "wandering_trader/common");
		ExtraItemSources.addTradeSet((AbstractVillagerInvoker) self, level, self.getOffers(), "wandering_trader/uncommon");
		ExtraItemSources.addTradeSet((AbstractVillagerInvoker) self, level, self.getOffers(), "wandering_trader/echo_shard");
	}
}
