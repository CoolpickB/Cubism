package dev.cubeworld.client.gui;

import com.ibm.icu.text.Collator;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Items with a check box, under a heading. A checked item is required: a seed search goes on until it finds a seed
 * with all of them. An item that the shown seed has is in white with a green mark, one that it lacks is in gray.
 */
final class FilterList<T> extends ObjectSelectionList<FilterList<T>.Row> {
	private static final Identifier CHECKBOX = Identifier.withDefaultNamespace("widget/checkbox");
	private static final Identifier CHECKBOX_SELECTED = Identifier.withDefaultNamespace("widget/checkbox_selected");
	private static final int PRESENT_COLOR = 0xFFFFFFFF;
	private static final int ABSENT_COLOR = 0xFF707070;
	private static final int HAS_MARK_COLOR = 0xFF55AA55;
	private static final int RATE_COLOR = 0xFFB0A060;
	private static final int NO_HITS_COLOR = 0xFFCC5555;

	private final Set<T> required;
	private final Runnable onChange;
	private final Function<T, Component> names;
	private final @Nullable ToIntFunction<T> swatches;
	private Component title = Component.empty();
	private @Nullable Set<T> present;
	private @Nullable ToDoubleFunction<T> rates;

	/**
	 * The list adds and removes items of {@code required} as the player checks them, then calls {@code onChange}.
	 * With {@code swatches}, each row shows a small square in the color that the function gives.
	 */
	FilterList(
		Minecraft minecraft, Collection<T> items, Set<T> required, Function<T, Component> names, @Nullable ToIntFunction<T> swatches, Runnable onChange
	) {
		super(minecraft, 0, 0, 0, Checkbox.getBoxSize(minecraft.font) + 4);
		this.required = required;
		this.names = names;
		this.swatches = swatches;
		this.onChange = onChange;
		this.addEntry(new Heading(), minecraft.font.lineHeight + 8);
		Collator collator = Collator.getInstance(Locale.getDefault());
		items.stream().map(Row::new).sorted(Comparator.comparing(row -> row.name.getString(), collator)).forEach(this::addEntry);
	}

	/** The heading names the list and counts the items of the seed. */
	void setTitle(Component title) {
		this.title = title;
	}

	/** The items of the shown seed, or null while there is none. */
	void setPresent(@Nullable Set<T> present) {
		this.present = present;
	}

	/**
	 * How often a search saw each checked item, as a share from 0 to 1, or below 0 for too few seeds. Null shows nothing.
	 * A rate of 0 shows in red, as the item that holds a search back.
	 */
	void setRates(@Nullable ToDoubleFunction<T> rates) {
		this.rates = rates;
	}

	/** The scrollbar sits inside the right edge of the list, and the rows stop before it. */
	@Override
	protected int scrollBarX() {
		return this.getRight() - this.scrollbarWidth() - 1;
	}

	@Override
	public int getRowLeft() {
		return this.getX() + 4;
	}

	@Override
	public int getRowWidth() {
		return this.width - 4 - this.scrollbarWidth() - 1 - 4;
	}

	private final class Heading extends Row {
		Heading() {
			super(null);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			Font font = FilterList.this.minecraft.font;
			graphics.text(font, FilterList.this.title.copy().withStyle(ChatFormatting.UNDERLINE), this.getContentX(), this.getContentY() + 4, PRESENT_COLOR);
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			return false;
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			return false;
		}

		@Override
		public Component getNarration() {
			return FilterList.this.title;
		}
	}

	class Row extends ObjectSelectionList.Entry<Row> {
		private final @Nullable T item;
		private final Component name;

		Row(@Nullable T item) {
			this.item = item;
			this.name = item == null ? Component.empty() : FilterList.this.names.apply(item);
		}

		private void toggle() {
			if (!FilterList.this.required.remove(this.item)) {
				FilterList.this.required.add(this.item);
			}
			FilterList.this.onChange.run();
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
			Font font = FilterList.this.minecraft.font;
			int box = Checkbox.getBoxSize(font);
			int x = this.getContentX();
			int y = this.getContentYMiddle();
			graphics.blitSprite(
				RenderPipelines.GUI_TEXTURED, FilterList.this.required.contains(this.item) ? CHECKBOX_SELECTED : CHECKBOX, x, y - box / 2, box, box
			);
			x += box + 4;
			ToIntFunction<T> swatches = FilterList.this.swatches;
			if (swatches != null) {
				graphics.fill(x, y - 4, x + 8, y + 4, 0xFF000000);
				graphics.fill(x + 1, y - 3, x + 7, y + 3, swatches.applyAsInt(this.item));
				x += 12;
			}
			Set<T> present = FilterList.this.present;
			boolean has = present != null && present.contains(this.item);
			int right = this.getContentRight() - 10;
			String rate = null;
			double share = -1.0;
			ToDoubleFunction<T> rates = FilterList.this.rates;
			if (rates != null && FilterList.this.required.contains(this.item)) {
				share = rates.applyAsDouble(this.item);
				if (share >= 0.0) {
					rate = share == 0.0 ? "0%" : share < 0.01 ? String.format(Locale.ROOT, "%.1f%%", share * 100.0) : String.format(Locale.ROOT, "%.0f%%", share * 100.0);
					right -= font.width(rate) + 4;
				}
			}
			String shown = fit(font, this.name.getString(), right - x);
			graphics.text(font, shown, x, y - 4, present == null || has ? PRESENT_COLOR : ABSENT_COLOR);
			if (rate != null) {
				graphics.text(font, rate, right + 4, y - 4, share == 0.0 ? NO_HITS_COLOR : RATE_COLOR);
				right += font.width(rate) + 4;
			}
			if (has) {
				graphics.fill(right + 2, y - 2, right + 6, y + 2, HAS_MARK_COLOR);
			}
			// A name cut short to fit the column shows in full on hover.
			if (hovered && !shown.equals(this.name.getString())) {
				graphics.setTooltipForNextFrame(this.name, mouseX, mouseY);
			}
		}

		@Override
		public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
			FilterList.this.setSelected(this);
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

	/** The text, cut short with dots when it is wider than the space. */
	private static String fit(Font font, String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
	}
}
