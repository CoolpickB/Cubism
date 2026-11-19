package dev.cubism.client.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.Entity;

/**
 * Reads the roll of the Do a Barrel Roll mod, through the interface that it adds to every entity. While it
 * rolls the player, its camera has no level to keep, so the view does not tilt over the edges and the mouse turns the
 * look its own way. A crossing keeps the roll, so the wings stay level with the new face as they were with the last
 * one. Turned with the face, the roll left the glide banked to the new face, and the banking of the mod turned it in
 * circles. The camera eases the change of the screen up as it does at every crossing.
 */
public final class BarrelRoll {
	private static final MethodHandle IS_ROLLING;
	private static final MethodHandle GET_ROLL;

	static {
		MethodHandle isRolling = null;
		MethodHandle getRoll = null;
		if (FabricLoader.getInstance().isModLoaded("do_a_barrel_roll")) {
			try {
				Class<?> api = Class.forName("nl.enjarai.doabarrelroll.api.RollEntity");
				MethodHandles.Lookup lookup = MethodHandles.publicLookup();
				isRolling = lookup.findVirtual(api, "doABarrelRoll$isRolling", MethodType.methodType(boolean.class));
				getRoll = lookup.findVirtual(api, "doABarrelRoll$getRoll", MethodType.methodType(float.class));
			} catch (ReflectiveOperationException e) {
				isRolling = null;
			}
		}
		IS_ROLLING = isRolling;
		GET_ROLL = getRoll;
	}

	private BarrelRoll() {}

	public static boolean rolling(Entity entity) {
		try {
			return IS_ROLLING != null && (boolean) IS_ROLLING.invoke(entity);
		} catch (Throwable e) {
			return false;
		}
	}

	/** The roll in degrees. The camera rolls by its negative about the look. */
	public static float roll(Entity entity) {
		try {
			return (float) GET_ROLL.invoke(entity);
		} catch (Throwable e) {
			return 0.0F;
		}
	}
}
