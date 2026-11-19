package dev.cubism.mixin.loot;

import dev.cubism.CubismMod;
import dev.cubism.loot.ExtraItemSources;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A villager adds the trade set {@code cubism:<profession>/level_<level>} after the vanilla set of its level, in a
 * world with extra item sources. Other offers keep their vanilla chance. An armorer of level 2 always adds the heavy upgrade template,
 * in every world, because the heavy boots have no other recipe.
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {
	private static final ResourceKey<TradeSet> HEAVY_UPGRADE_TRADE = ResourceKey.create(
		Registries.TRADE_SET, Identifier.fromNamespaceAndPath(CubismMod.MOD_ID, "armorer/heavy_upgrade_smithing_template")
	);

	@Inject(method = "updateTrades", at = @At("TAIL"))
	private void cubism$addExtraItemTrades(ServerLevel level, CallbackInfo ci) {
		Villager self = (Villager) (Object) this;
		VillagerData data = self.getVillagerData();
		data.profession().unwrapKey().ifPresent(profession -> ExtraItemSources.addTradeSet(
			(AbstractVillagerInvoker) self, level, self.getOffers(), profession.identifier().getPath() + "/level_" + data.level()
		));
		if (data.level() == 2 && data.profession().is(VillagerProfession.ARMORER)) {
			((AbstractVillagerInvoker) self).cubism$addOffersFromTradeSet(level, self.getOffers(), HEAVY_UPGRADE_TRADE);
		}
	}
}
