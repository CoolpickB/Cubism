package dev.cubeworld.client.gui;

import com.ibm.icu.text.Collator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.biome.Biome;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

/**
 * The biomes that the world can have, each with a check box. A checked biome is required: a seed search goes on until
 * it finds a seed with all of them. A biome that the shown seed has is in white with a green mark, one that it lacks is in gray.
 */
final class BiomeFilterList extends ObjectSelectionList<BiomeFilterList.Entry> {
	private static final Identifier CHECKBOX = Identifier.withDefaultNamespace("widget/checkbox");
	private static final Identifier CHECKBOX_SELECTED = Identifier.withDefaultNamespace("widget/checkbox_selected");
	private static final int PRESENT_COLOR = 0xFFFFFFFF;
	private static final int ABSENT_COLOR = 0xFF707070;
	private static final int HAS_MARK_COLOR = 0xFF55AA55;
	private static final Component IN_SEED = Component.translatable("cubeworld.customize.biome.present");
	private static final Component NOT_IN_SEED = Component.translatable("cubeworld.customize.biome.absent");

	private final Set<Holder<Biome>> required;
	private final Runnable onChange;
	private @Nullable Set<Holder<Biome>> present;

	/** The list adds and removes biomes of {@code required} as the player checks them, then calls {@code onChange}. */
	BiomeFilterList(Minecraft minecraft, Collection<Holder<Biome>> biomes, Set<Holder<Biome>> required, Runnable onChange) {
		super(minecraft, 0, 0, 0, Checkbox.getBoxSize(minecraft.font) + 4);
		this.required = required;
		this.onChange = onChange;
		Collator collator = Collator.getInstance(Locale.getDefault());
		biomes.stream().map(Entry::new).sorted(Comparator.comparing(entry -> entry.name.getString(), collator)).forEach(this::addEntry);
	}

	/** The biomes of the shown seed, or null while there is none. */
	void setPresent(@Nullable Set<Holder<Biome>> present) {
		this.present = present;
	}

	@Override
	public int getRowWidth() {
		return this.width - 16;
	}

	final class Entry extends ObjectSelectionList.Entry<Entry> {
		private final Holder<Biome> biome;
		private final Component name;

		Entry(Holder<Biome> biome) {
			this.biome = biome;
			this.name = BiomeColors.name(biome);
		}

		private void toggle() {
			if (!BiomeFilterList.this.required.remove(this.biome)) {
				BiomeFilterList.this.required.add(this.biome);
			}
			BiomeFilterList.this.onChange.run();
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			Font font = BiomeFilterList.this.minecraft.font;
			int box = Checkbox.getBoxSize(font);
			int x = this.getContentX();
			int y = this.getContentYMiddle();
			graphics.blitSprite(
				RenderPipelines.GUI_TEXTURED, BiomeFilterList.this.required.contains(this.biome) ? CHECKBOX_SELECTED : CHECKBOX, x, y - box / 2, box, box
			);
			x += box + 4;
			graphics.fill(x, y - 4, x + 8, y + 4, 0xFF000000);
			graphics.fill(x + 1, y - 3, x + 7, y + 3, BiomeColors.color(this.biome));
			x += 12;
			Set<Holder<Biome>> present = BiomeFilterList.this.present;
			boolean has = present != null && present.contains(this.biome);
			graphics.text(font, this.name, x, y - 4, present == null || has ? PRESENT_COLOR : ABSENT_COLOR);
			if (has) {
				int right = this.getContentRight() - 6;
				graphics.fill(right - 4, y - 2, right, y + 2, HAS_MARK_COLOR);
			}
			if (hovered && present != null) {
				graphics.setTooltipForNextFrame(has ? IN_SEED : NOT_IN_SEED, mouseX, mouseY);
			}
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			BiomeFilterList.this.setSelected(this);
			this.toggle();
			return true;
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (event.isSelection()) {
				this.toggle();
				return true;
			}
			return super.keyPressed(event);
		}

		@Override
		public Component getNarration() {
			return this.name;
		}
	}
}
