package dev.cubism.client.gui;

import net.minecraft.core.Holder;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.biome.Biome;

import java.awt.Color;
import java.util.Map;

/** Map colors and names of biomes for the Customize screen. */
final class BiomeColors {
	private static final Map<String, Integer> COLORS = Map.ofEntries(
		Map.entry("ocean", 0x000070), Map.entry("deep_ocean", 0x000038),
		Map.entry("warm_ocean", 0x0000AC), Map.entry("lukewarm_ocean", 0x000090), Map.entry("deep_lukewarm_ocean", 0x000048),
		Map.entry("cold_ocean", 0x202070), Map.entry("deep_cold_ocean", 0x202038),
		Map.entry("frozen_ocean", 0x7070D6), Map.entry("deep_frozen_ocean", 0x404090),
		Map.entry("river", 0x0000FF), Map.entry("frozen_river", 0xA0A0FF),
		Map.entry("beach", 0xFADE55), Map.entry("snowy_beach", 0xFAF0C0), Map.entry("stony_shore", 0xA2A284),
		Map.entry("plains", 0x8DB360), Map.entry("sunflower_plains", 0xB5DB88), Map.entry("meadow", 0x83BB6D),
		Map.entry("snowy_plains", 0xFFFFFF), Map.entry("ice_spikes", 0xB4DCDC),
		Map.entry("desert", 0xFA9418),
		Map.entry("swamp", 0x07F9B2), Map.entry("mangrove_swamp", 0x2CCC8E),
		Map.entry("forest", 0x056621), Map.entry("flower_forest", 0x2D8E49), Map.entry("dappled_forest", 0x3F7F2A),
		Map.entry("birch_forest", 0x307444), Map.entry("old_growth_birch_forest", 0x589C6C),
		Map.entry("dark_forest", 0x40511A), Map.entry("pale_garden", 0x8A9488),
		Map.entry("cherry_grove", 0xE8A0C0),
		Map.entry("taiga", 0x0B6659), Map.entry("snowy_taiga", 0x31554A),
		Map.entry("old_growth_pine_taiga", 0x596651), Map.entry("old_growth_spruce_taiga", 0x818E79),
		Map.entry("savanna", 0xBDB25F), Map.entry("savanna_plateau", 0xA79D64), Map.entry("windswept_savanna", 0xE5DA87),
		Map.entry("windswept_hills", 0x606060), Map.entry("windswept_gravelly_hills", 0x888888), Map.entry("windswept_forest", 0x507050),
		Map.entry("jungle", 0x537B09), Map.entry("sparse_jungle", 0x628B17), Map.entry("bamboo_jungle", 0x768E14),
		Map.entry("badlands", 0xD94515), Map.entry("eroded_badlands", 0xFF6D3D), Map.entry("wooded_badlands", 0xB09765),
		Map.entry("grove", 0xA8C8B8), Map.entry("snowy_slopes", 0xD8E0F0),
		Map.entry("frozen_peaks", 0xA0B8FF), Map.entry("jagged_peaks", 0xDCDCF0), Map.entry("stony_peaks", 0x9A9A9A),
		Map.entry("mushroom_fields", 0xFF00FF),
		Map.entry("dripstone_caves", 0x7B5A3E), Map.entry("lush_caves", 0x6FA82A), Map.entry("deep_dark", 0x0F2A33),
		Map.entry("sulfur_caves", 0xC8C83C)
	);

	private BiomeColors() {
	}

	/** An opaque color for the biome. A biome with no color in the table gets one from a hash of its id. */
	static int color(Holder<Biome> biome) {
		String id = biome.getRegisteredName();
		Integer color = COLORS.get(id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id);
		if (color != null) {
			return ARGB.opaque(color);
		}
		int hash = id.hashCode();
		return ARGB.opaque(Color.HSBtoRGB((hash & 0xFFFF) / 65536.0F, 0.5F, 0.6F + (hash >>> 16 & 0xFF) / 640.0F));
	}

	static Component name(Holder<Biome> biome) {
		return biome.unwrapKey().map(key -> {
			Identifier id = key.identifier();
			String translationKey = id.toLanguageKey("biome");
			return Language.getInstance().has(translationKey) ? Component.translatable(translationKey) : Component.literal(id.toString());
		}).orElse(Component.literal(biome.getRegisteredName()));
	}
}
