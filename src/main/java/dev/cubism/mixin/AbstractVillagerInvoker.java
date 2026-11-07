package dev.cubism.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets {@link dev.cubism.loot.FiniteLoot} add a trade set to the offers of a villager or a trader. */
@Mixin(AbstractVillager.class)
public interface AbstractVillagerInvoker {
	@Invoker("addOffersFromTradeSet")
	void cubism$addOffersFromTradeSet(ServerLevel level, MerchantOffers offers, ResourceKey<TradeSet> key);
}
