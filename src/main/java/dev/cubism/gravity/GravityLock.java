package dev.cubism.gravity;

import dev.cubism.CubismMod;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import dev.cubism.worldgen.SeamLayout;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SmithingTemplateItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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

	// Diamond values with the knockback resistance of netherite.
	private static final ArmorMaterial HEAVY = new ArmorMaterial(
		33, Map.of(ArmorType.BOOTS, 3), 10, SoundEvents.ARMOR_EQUIP_NETHERITE, 2.0F, 0.1F,
		TagKey.create(Registries.ITEM, id("repairs_heavy_boots")), ResourceKey.create(EquipmentAssets.ROOT_ID, id("heavy_boots"))
	);
	public static final Item HEAVY_BOOTS = Registry.register(
		BuiltInRegistries.ITEM, id("heavy_boots"),
		new Item(new Item.Properties().humanoidArmor(HEAVY, ArmorType.BOOTS).setId(ResourceKey.create(Registries.ITEM, id("heavy_boots"))))
	);
	public static final Item HEAVY_UPGRADE_TEMPLATE = Registry.register(
		BuiltInRegistries.ITEM, id("heavy_upgrade_smithing_template"),
		new SmithingTemplateItem(
			templateText("applies_to"), templateText("ingredients"), templateText("base_slot_description"), templateText("additions_slot_description"),
			List.of(Identifier.withDefaultNamespace("container/slot/boots")), List.of(Identifier.withDefaultNamespace("container/slot/ingot")),
			new Item.Properties().rarity(Rarity.UNCOMMON).setId(ResourceKey.create(Registries.ITEM, id("heavy_upgrade_smithing_template")))
		)
	);

	private static final int BOOTS_TICKS = 200;

	private GravityLock() {}

	public static void register() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.COMBAT).register(output -> output.insertAfter(Items.TURTLE_HELMET, HEAVY_BOOTS));
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.INGREDIENTS).register(output -> output.insertAfter(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, HEAVY_UPGRADE_TEMPLATE));
	}

	private static Component templateText(String part) {
		MutableComponent text = Component.translatable(Util.makeDescriptionId("item", id("smithing_template.heavy_upgrade." + part)));
		return part.endsWith("slot_description") ? text : text.withStyle(ChatFormatting.BLUE);
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(CubismMod.MOD_ID, path);
	}

	/** True while the down of the entity stays with its face region. Elytra flight ignores the lock. */
	public static boolean locked(Entity entity) {
		return entity instanceof LivingEntity living && living.hasEffect(STEADFAST) && !living.isFallFlying();
	}

	/**
	 * The face that a level 1 to 6 of the effect forces, or -1. The level names the face in the order of
	 * {@link Direction#from3DDataValue}: down, up, north, south, west, east. So {@code /effect give @s cubism:steadfast
	 * 30 2} keeps a player on the up face, wherever it is. Level 0 keeps the face the entity is on.
	 */
	public static int forcedFace(Entity entity) {
		if (!locked(entity)) {
			return -1;
		}
		int amplifier = ((LivingEntity) entity).getEffect(STEADFAST).getAmplifier();
		return amplifier < 1 || amplifier > 6 ? -1 : SeamLayout.indexOf(Direction.from3DDataValue(amplifier - 1));
	}

	/**
	 * The face whose down a locked entity keeps, or -1 without a lock: the forced face, else the face region it is in.
	 * In space it is the face that the entity left for space, or for a lock that started in space, the face under it.
	 */
	public static int lockFace(SeamLayout layout, Entity entity) {
		if (!locked(entity)) {
			return -1;
		}
		int forced = forcedFace(entity);
		if (forced >= 0) {
			return forced;
		}
		int region = layout.regionAt(Mth.floor(entity.getX()), Mth.floor(entity.getZ()));
		if (region != SeamLayout.SPACE) {
			return region;
		}
		int left = ((GravityEntityAccess) entity).cubism$spaceFace();
		if (left < 0) {
			Vec3 p = layout.cubeOffset(region, entity.getX(), entity.getY(), entity.getZ());
			left = layout.ownerFace(Mth.floor(p.x), Mth.floor(p.y) + layout.centerY, Mth.floor(p.z));
			((GravityEntityAccess) entity).cubism$setSpaceFace(left);
		}
		return left;
	}

	/**
	 * True when a locked entity must not cross a diagonal now. A player that sneaks on the ground crosses, unless a
	 * level of the effect forces the face. The sneak must start on the ground: in creative flight sneak flies down, and
	 * a landing with it held crossed to the face the player was over.
	 */
	public static boolean holdsFace(Entity entity) {
		return locked(entity) && (forcedFace(entity) >= 0 || !(entity instanceof Player player && player.onGround()
			&& SNEAK_FROM_GROUND.getOrDefault(player, false)));
	}

	/** For each sneaking player, whether the sneak started on the ground. Players of the client and the server thread. */
	private static final Map<Player, Boolean> SNEAK_FROM_GROUND = Collections.synchronizedMap(new WeakHashMap<>());

	public static void tickSneak(Player player) {
		if (!player.isShiftKeyDown()) {
			SNEAK_FROM_GROUND.remove(player);
		} else {
			SNEAK_FROM_GROUND.putIfAbsent(player, player.onGround() && !player.getAbilities().flying);
		}
	}

	/**
	 * True when a locked entity below the bottom of the world must not fall out of it. Past a diagonal a locked fall runs
	 * along the side of the next face, and the storage ends at the top of the inner bedrock, so the fall leaves the
	 * storage halfway down that face. It goes on there, and collision still reads the blocks of the faces it passes
	 * (see {@code BlockCollisionsMixin}). Only inside the inner bedrock cube, the core, does the void still kill.
	 */
	public static boolean fallsOn(Entity entity) {
		SeamLayout layout = locked(entity) ? SeamLayout.of(entity.level()) : null;
		if (layout == null) {
			return false;
		}
		// The strip of the position, not its region: a fall that drifts out past the region in z is still the same face.
		int face = layout.faceIndexAt(Mth.floor(entity.getX()));
		double cx = Math.abs(entity.getX() - layout.offsetX(face) - 0.5);
		double cy = Math.abs(entity.getY() - layout.centerY - 0.5);
		double cz = Math.abs(entity.getZ() - 0.5);
		return Math.max(cx, Math.max(cy, cz)) > layout.innerBedrockRadius;
	}

	/**
	 * Worn heavy boots give the effect as the turtle shell gives water breathing: on both sides, every tick, for 10 s, so the
	 * icon has the normal frame and does not blink. Unlike the shell, the boots take their effect away on the tick that
	 * they come off. Their effect has no particles, which a potion or a command effect has, so a running potion stays.
	 */
	public static void tickBoots(Player player) {
		if (player.getItemBySlot(EquipmentSlot.FEET).is(HEAVY_BOOTS)) {
			player.addEffect(new MobEffectInstance(STEADFAST, BOOTS_TICKS, 0, false, false, true));
			return;
		}
		MobEffectInstance effect = player.getEffect(STEADFAST);
		if (effect != null && effect.getAmplifier() == 0 && !effect.isVisible() && !effect.isInfiniteDuration()
			&& effect.getDuration() <= BOOTS_TICKS) {
			player.removeEffect(STEADFAST);
		}
	}
}
