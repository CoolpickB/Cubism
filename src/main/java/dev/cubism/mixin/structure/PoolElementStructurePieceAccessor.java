package dev.cubism.mixin.structure;

import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PoolElementStructurePiece.class)
public interface PoolElementStructurePieceAccessor {
	@Accessor("liquidSettings")
	LiquidSettings cubism$liquidSettings();

	@Accessor("structureTemplateManager")
	StructureTemplateManager cubism$templateManager();
}
