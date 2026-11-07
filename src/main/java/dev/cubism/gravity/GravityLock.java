package dev.cubism.gravity;

import dev.cubism.CubismMod;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAssets;

import java.util.Map;

/**
 * Steadfast keeps an entity in the region of the face it stands on: it does not cross a diagonal, so its down stays
 * the down of that face. Past the diagonal the collision of the region reads the turned blocks of the next face (see
 * {@code BlockCollisionsMixin}), so the entity walks on that rock as on a wall, under the vanilla pull. A player who
 * sneaks on the ground crosses as usual, which sets the lock to the face it then stands on.
 */
public final class GravityLock {
	public static final Holder<MobEffect> STEADFAST = Registry.registerForHolder(
		BuiltInRegistries.MOB_EFFECT, id("steadfast"), new MobEffect(MobEffectCategory.BENEFICIAL, 0x9A5CC6) {}
	);
	public static final Holder<Potion> STEADFAST_POTION = Registry.registerForHolder(
		BuiltInRegistries.POTION, id("steadfast"), new Potion("steadfast", new MobEffectInstance(STEADFAST, 3600))
	);
	public static final Holder<Potion> LONG_STEADFAST_POTION = Registry.registerForHolder(
		BuiltInRegistries.POTION, id("long_steadfast"), new Potion("steadfast", new MobEffectInstance(STEADFAST, 9600))
	);

	// Diamond values with the knockback resistance of netherite. The worn texture is the diamond one for now.
	private static final ArmorMaterial SHULKER = new ArmorMaterial(
		33, Map.of(ArmorType.BOOTS, 3), 10, SoundEvents.ARMOR_EQUIP_NETHERITE, 2.0F, 0.1F,
		TagKey.create(Registries.ITEM, id("repairs_shulker_treads")), EquipmentAssets.DIAMOND
	);
	public static final Item SHULKER_TREADS = Registry.register(
		BuiltInRegistries.ITEM, id("shulker_treads"),
		new Item(new Item.Properties().humanoidArmor(SHULKER, ArmorType.BOOTS).setId(ResourceKey.create(Registries.ITEM, id("shulker_treads"))))
	);

	private GravityLock() {}

	public static void register() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> output.insertAfter(Items.NETHERITE_BOOTS, SHULKER_TREADS));
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(CubismMod.MOD_ID, path);
	}

	/** True while the down of the entity stays with its face region. */
	public static boolean locked(Entity entity) {
		return entity instanceof LivingEntity living && living.hasEffect(STEADFAST);
	}

	/** True when a locked entity must not cross a diagonal now. A player that sneaks on the ground crosses. */
	public static boolean holdsFace(Entity entity) {
		return locked(entity) && !(entity instanceof Player player && player.isShiftKeyDown() && player.onGround());
	}

	/** Worn treads give the effect as the turtle shell gives water breathing: on both sides, every tick. */
	public static void tickTreads(Player player) {
		if (player.getItemBySlot(EquipmentSlot.FEET).is(SHULKER_TREADS)) {
			player.addEffect(new MobEffectInstance(STEADFAST, 40, 0, false, false, true));
		}
	}
}
