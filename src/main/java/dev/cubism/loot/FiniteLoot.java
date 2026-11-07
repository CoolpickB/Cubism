package dev.cubism.loot;

import com.mojang.serialization.MapCodec;
import dev.cubism.CubismMod;
import dev.cubism.mixin.AbstractVillagerInvoker;
import dev.cubism.worldgen.CubeSettings;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

/**
 * The trades, gifts and drops that give a finite cube the items that its structures may not generate. The setting
 * belongs to the overworld generator, so every dimension reads it there.
 */
public final class FiniteLoot {
	/** True in a cube world that has the setting on. Other worlds keep vanilla loot. */
	public static boolean enabled(ServerLevel level) {
		return CubeSettings.of(level.getServer().overworld().getChunkSource().getGenerator()).finiteLoot();
	}

	/** Adds the trade set {@code cubism:<path>} to the offers of a merchant, if the setting is on and the set exists. */
	public static void addTradeSet(AbstractVillagerInvoker merchant, ServerLevel level, MerchantOffers offers, String path) {
		if (!enabled(level)) {
			return;
		}
		ResourceKey<TradeSet> key = ResourceKey.create(Registries.TRADE_SET, Identifier.fromNamespaceAndPath(CubismMod.MOD_ID, path));
		if (level.registryAccess().lookupOrThrow(Registries.TRADE_SET).get(key).isPresent()) {
			merchant.cubism$addOffersFromTradeSet(level, offers, key);
		}
	}

	/** Loot tables use this as the condition {@code cubism:finite_loot}. */
	public record Condition() implements LootItemCondition {
		public static final MapCodec<Condition> CODEC = MapCodec.unit(new Condition());

		@Override
		public MapCodec<Condition> codec() {
			return CODEC;
		}

		@Override
		public boolean test(LootContext context) {
			return enabled(context.getLevel());
		}
	}

	public static void register() {
		Registry.register(BuiltInRegistries.LOOT_CONDITION_TYPE, Identifier.fromNamespaceAndPath(CubismMod.MOD_ID, "finite_loot"), Condition.CODEC);
	}

	private FiniteLoot() {
	}
}
