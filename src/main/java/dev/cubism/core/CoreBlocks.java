package dev.cubism.core;

import dev.cubism.CubismMod;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.util.function.Function;

/** The blocks of the core cavern, see {@link CoreCavern}. */
public final class CoreBlocks {
	/** Acts as bedrock in every way except that it grows heavy cores. */
	public static final Block BUDDING_BEDROCK = block("budding_bedrock", BlockBehaviour.Properties.ofFullCopy(Blocks.BEDROCK).randomTicks(), BuddingBedrockBlock::new);
	public static final Item BUDDING_BEDROCK_ITEM = item(BUDDING_BEDROCK);

	private CoreBlocks() {}

	public static void register() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.NATURAL_BLOCKS).register(output -> output.insertAfter(Items.BUDDING_AMETHYST, BUDDING_BEDROCK_ITEM));
	}

	private static Block block(String name, BlockBehaviour.Properties properties, Function<BlockBehaviour.Properties, Block> factory) {
		Identifier id = Identifier.fromNamespaceAndPath(CubismMod.MOD_ID, name);
		return Registry.register(BuiltInRegistries.BLOCK, id, factory.apply(properties.setId(ResourceKey.create(Registries.BLOCK, id))));
	}

	private static Item item(Block block) {
		Identifier id = BuiltInRegistries.BLOCK.getKey(block);
		return Registry.register(
			BuiltInRegistries.ITEM, id, new BlockItem(block, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id)).useBlockDescriptionPrefix())
		);
	}
}
