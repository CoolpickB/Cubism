package dev.cubeworld.client.mixin;

import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.Optional;

@Mixin(PresetEditor.class)
public interface PresetEditorAccessor {
	@Accessor("EDITORS")
	@Mutable
	static void cubeworld$setEditors(Map<Optional<ResourceKey<WorldPreset>>, PresetEditor> editors) {
		throw new AssertionError();
	}
}
