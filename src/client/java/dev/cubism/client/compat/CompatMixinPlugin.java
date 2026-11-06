package dev.cubism.client.compat;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the mixins of one integration only when its mod is loaded, as their targets do not exist otherwise. The mixin
 * config of each integration has the package {@code dev.cubism.client.compat.<mod id>.mixin}, and the id comes from it.
 */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
	private boolean modLoaded;

	@Override
	public void onLoad(String mixinPackage) {
		String mod = mixinPackage.substring(0, mixinPackage.lastIndexOf('.'));
		this.modLoaded = FabricLoader.getInstance().isModLoaded(mod.substring(mod.lastIndexOf('.') + 1));
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return this.modLoaded;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
