package com.femboyclient;

import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link WikiDoc} laid out as the item sheet, for one panel width: the page cut into sections that fold open and closed
 * (Stats first, as small chips; then the infobox's other groups, About, the page's own sections, Used in), and the "Pop out
 * recipe" button under Stats when the page has a recipe for its item. The result is a list of ready-made drawing steps with
 * their places, measured once and drawn every frame by {@link WikiPanel} without allocating. Coordinates are GUI pixels from
 * the content area's top-left corner. Text is the menu's: Inter body text in the dim color, links in the light accent; the
 * wiki's own in-game colors are kept, lifted a little when too dark for the plum glass.
 */
final class WikiLayout {

	private static final int LINE_GAP = 6;            // like ClientMenuScreen's
	private static final int INDENT = 9;               // per list level
	private static final int TABLE_PAD = 4;
	static final int SECTION_HEIGHT = 22, CHIP_HEIGHT = 15, BUTTON_HEIGHT = 20;

	/** A run of text on a line. {@code color} 0 means the line's own color; {@code link} is a page title or null. */
	record Piece(FormattedCharSequence text, int x, int width, int color, String link) {
	}

	sealed interface Op permits TextOp, LabelOp, FillOp, DividerOp, SectionOp, ChipOp, ButtonOp {
		int top();

		int bottom();
	}

	/** One line of text; {@code y} is the top of its line box. */
	record TextOp(int x, int y, int color, List<Piece> pieces) implements Op {
		public int top() {
			return y;
		}

		public int bottom() {
			return y + Theme.BODY_LINE;
		}
	}

	record LabelOp(int x, int y, UiFont.Label label, int color) implements Op {
		public int top() {
			return y;
		}

		public int bottom() {
			return y + Theme.LABEL_LINE;
		}
	}

	record FillOp(int x0, int y0, int x1, int y1, int radius, int color) implements Op {
		public int top() {
			return y0;
		}

		public int bottom() {
			return y1;
		}
	}

	record DividerOp(int x0, int y, int x1) implements Op {
		public int top() {
			return y;
		}

		public int bottom() {
			return y + 1;
		}
	}

	/** A section's header row: its name, and ▾ (open) or ▸ (closed). Clicking it folds the section. */
	record SectionOp(int y, String key, FormattedCharSequence name, boolean open, boolean divider) implements Op {
		public int top() {
			return y;
		}

		public int bottom() {
			return y + SECTION_HEIGHT;
		}
	}

	/** A stat chip: "❁ Dmg 260", the name dim and the value white, in a small rounded pill. */
	record ChipOp(int x, int y, int width, FormattedCharSequence label, int labelWidth, FormattedCharSequence value) implements Op {
		public int top() {
			return y;
		}

		public int bottom() {
			return y + CHIP_HEIGHT;
		}
	}

	/** The pink "Pop out recipe" button. */
	record ButtonOp(int x, int y, int width, FormattedCharSequence text, int textWidth) implements Op {
		public int top() {
			return y;
		}

		public int bottom() {
			return y + BUTTON_HEIGHT;
		}
	}

	final List<Op> ops;
	final int height;

	private WikiLayout(List<Op> ops, int height) {
		this.ops = ops;
		this.height = height;
	}

	// ------------------------------------------------------------ sections

	/** A named run of blocks that folds as one. */
	private record Section(String name, List<WikiDoc.Block> blocks, boolean chips) {
	}

	/**
	 * Cuts the page into sections: Stats (the infobox's first rows and its Stats group, as chips), the infobox's other groups,
	 * About (the text before the page's first heading), the page's own sections, and Used in (what this item is crafted into).
	 * Recipes are left out of the sections (the button pops them out); empty sections are dropped.
	 */
	private static List<Section> sections(WikiDoc doc) {
		Section stats = new Section("Stats", new ArrayList<>(), true), about = new Section("About", new ArrayList<>(), false);
		List<Section> infobox = new ArrayList<>(), page = new ArrayList<>();
		List<String> usedIn = new ArrayList<>();
		Section current = null;
		for (int i = 0; i < doc.blocks().size(); i++) {
			WikiDoc.Block block = doc.blocks().get(i);
			boolean inInfobox = i < doc.infobox();
			if (i == doc.infobox()) {
				current = null;                                            // the page's own text starts
			}
			if (block instanceof WikiDoc.Recipe recipe) {
				if (!recipe.output().name().equalsIgnoreCase(doc.title()) && !usedIn.contains(recipe.output().name())) {
					usedIn.add(recipe.output().name());
				}
				continue;
			}
			if (block instanceof WikiDoc.Heading heading && (inInfobox || heading.level() <= 2)) {
				if (inInfobox && heading.text().equalsIgnoreCase("Stats")) {
					current = stats;
				} else {
					current = new Section(heading.text(), new ArrayList<>(), false);
					(inInfobox ? infobox : page).add(current);
				}
				continue;
			}
			if (block instanceof WikiDoc.Row row && row.label().equalsIgnoreCase("Rarity")) {
				continue;                                                  // the badge in the header says it
			}
			(current != null ? current : inInfobox ? stats : about).blocks().add(block);
		}
		List<Section> ordered = new ArrayList<>();
		ordered.add(stats);
		ordered.addAll(infobox);
		ordered.add(about);
		ordered.addAll(page);
		if (!usedIn.isEmpty()) {
			List<WikiDoc.Block> links = new ArrayList<>();
			for (String name : usedIn) {
				links.add(new WikiDoc.Text(List.of(new WikiDoc.Span(name, 0, false, name)), true, 0));
			}
			Section used = new Section("Used in", links, false);
			int history = -1;
			for (int i = 0; i < ordered.size(); i++) {
				if (ordered.get(i).name().equalsIgnoreCase("History")) {
					history = i;
				}
			}
			ordered.add(history >= 0 ? history : ordered.size(), used);
		}
		ordered.removeIf(section -> section.blocks().stream().allMatch(b -> b instanceof WikiDoc.Heading));
		return ordered;
	}

	// ------------------------------------------------------------ building

	/**
	 * Lays {@code doc} out {@code width} pixels wide. {@code open} holds the lower-case names of the open sections;
	 * {@code popOut}: show the "Pop out recipe" button.
	 */
	static WikiLayout build(Font font, WikiDoc doc, int width, Set<String> open, boolean popOut) {
		Builder b = new Builder(font, Math.max(40, width));
		boolean first = true;
		for (Section section : sections(doc)) {
			String key = section.name().toLowerCase(Locale.ROOT);
			boolean isOpen = open.contains(key);
			b.section(key, section.name(), isOpen, !first);
			if (isOpen) {
				for (WikiDoc.Block block : section.blocks()) {
					b.block(block, section.chips());
				}
				b.flushChips();
				b.y += 4;
			}
			if (first && popOut) {
				b.button();
			}
			first = false;
		}
		if (first && popOut) {
			b.button();
		}
		return new WikiLayout(List.copyOf(b.ops), b.y);
	}

	private static final class Builder {
		private final Font font;
		private final int width;
		private final List<Op> ops = new ArrayList<>();
		private int y;
		private int chipColumn;                          // 0: the next chip starts a row; 1: it goes in the right column

		Builder(Font font, int width) {
			this.font = font;
			this.width = width;
		}

		void section(String key, String name, boolean open, boolean divider) {
			flushChips();
			ops.add(new SectionOp(y, key, UiFont.sequence(UiFont.fit(font, name, UiFont.Face.BODY, width - 20), UiFont.Face.BODY), open, divider));
			y += SECTION_HEIGHT;
		}

		void button() {
			flushChips();
			y += 2;
			String text = "Pop out recipe";
			FormattedCharSequence sequence = UiFont.sequence(text, UiFont.Face.STRONG);
			int textWidth = font.width(sequence);
			ops.add(new ButtonOp(0, y, textWidth + 34, sequence, textWidth));
			y += BUTTON_HEIGHT + 6;
		}

		void block(WikiDoc.Block block, boolean chips) {
			switch (block) {
				case WikiDoc.Row row when chips -> chip(row);
				case WikiDoc.Row row -> row(row);
				case WikiDoc.Heading heading -> heading(heading);
				case WikiDoc.Text text -> text(text);
				case WikiDoc.Table table -> table(table);
				case WikiDoc.Recipe ignored -> {
				}
			}
		}

		/** Stats as chips, two to a row; one too long for half the width takes the whole row. */
		void chip(WikiDoc.Row row) {
			StringBuilder value = new StringBuilder();
			row.value().forEach(span -> value.append(span.text()));
			FormattedCharSequence label = UiFont.sequence(row.label(), UiFont.Face.BODY);
			int labelWidth = font.width(label);
			int half = (width - 4) / 2;
			int needed = 14 + labelWidth + 4 + UiFont.width(font, value.toString(), UiFont.Face.STRONG);
			boolean wide = needed > half;
			if (wide && chipColumn == 1) {
				flushChips();
			}
			int chipWidth = wide ? width : half;
			String shown = UiFont.fit(font, value.toString(), UiFont.Face.STRONG, chipWidth - 14 - labelWidth - 4);
			int x = chipColumn == 1 ? half + 4 : 0;
			ops.add(new ChipOp(x, y, chipWidth, label, labelWidth, UiFont.sequence(shown, UiFont.Face.STRONG)));
			if (wide || chipColumn == 1) {
				chipColumn = 0;
				y += CHIP_HEIGHT + 4;
			} else {
				chipColumn = 1;
			}
		}

		void flushChips() {
			if (chipColumn == 1) {
				chipColumn = 0;
				y += CHIP_HEIGHT + 4;
			}
		}

		/** A smaller heading inside a section: white text in the strong face. */
		void heading(WikiDoc.Heading heading) {
			y += 3;
			paragraph(List.of(new WikiDoc.Span(heading.text(), 0, true, null)), 0, Theme.TEXT);
			y += 1;
		}

		void text(WikiDoc.Text text) {
			int indent = text.bullet() ? INDENT * (text.depth() + 1) : 0;
			if (text.bullet()) {
				FormattedCharSequence dot = UiFont.sequence("•", UiFont.Face.BODY);
				ops.add(new TextOp(indent - INDENT + 2, y, Theme.TEXT_LABEL, List.of(new Piece(dot, 0, font.width(dot), 0, null))));
			}
			paragraph(text.spans(), indent, Theme.TEXT_DIM);
			y += text.bullet() ? 1 : LINE_GAP - 2;
		}

		/** "Name ...... value" on one line when it fits; else the name, then the value wrapped under it. */
		void row(WikiDoc.Row row) {
			FormattedCharSequence label = UiFont.sequence(row.label(), UiFont.Face.BODY);
			int labelWidth = font.width(label);
			List<WikiDoc.Span> value = new ArrayList<>();
			for (WikiDoc.Span span : row.value()) {
				value.add(new WikiDoc.Span(span.text(), span.color(), true, span.link()));        // values in the strong face
			}
			List<List<Piece>> valueLines = wrap(value, Integer.MAX_VALUE);
			int valueWidth = valueLines.isEmpty() ? 0 : lineWidth(valueLines.get(0));
			ops.add(new TextOp(0, y, Theme.TEXT_DIM, List.of(new Piece(label, 0, labelWidth, 0, null))));
			if (valueLines.size() == 1 && labelWidth + Theme.GAP + valueWidth <= width) {
				ops.add(new TextOp(width - valueWidth, y, Theme.TEXT, valueLines.get(0)));
				y += Theme.BODY_LINE + 2;
			} else {
				y += Theme.BODY_LINE;
				paragraph(value, INDENT, Theme.TEXT);
				y += 2;
			}
		}

		/** Even columns, header row in the label color, every other row striped like the menu's chat log. */
		void table(WikiDoc.Table table) {
			int columns = 0;
			for (List<String> row : table.rows()) {
				columns = Math.max(columns, row.size());
			}
			if (columns == 0) {
				return;
			}
			y += 2;
			int columnWidth = width / columns;
			for (int r = 0; r < table.rows().size(); r++) {
				List<String> row = table.rows().get(r);
				boolean header = r == 0 && table.header();
				int cellWidth = row.size() == columns ? columnWidth : width / Math.max(1, row.size());
				List<List<FormattedCharSequence>> cells = new ArrayList<>();
				int lines = 1;
				for (String cell : row) {
					List<FormattedCharSequence> split = font.split(UiFont.component(cell, header ? UiFont.Face.STRONG : UiFont.Face.BODY),
							Math.max(10, cellWidth - 2 * TABLE_PAD));
					cells.add(split);
					lines = Math.max(lines, split.size());
				}
				int rowHeight = lines * Theme.BODY_LINE + 4;
				ops.add(new FillOp(0, y, width, y + rowHeight, 4, header ? Theme.CARD_HOVER_FILL : r % 2 == 0 ? Theme.CHAT_ROW_EVEN
						: Theme.CHAT_ROW_ODD));
				for (int c = 0; c < cells.size(); c++) {
					int ly = y + 2;
					for (FormattedCharSequence line : cells.get(c)) {
						ops.add(new TextOp(c * cellWidth + TABLE_PAD, ly, header ? Theme.TEXT : Theme.TEXT_DIM,
								List.of(new Piece(line, 0, font.width(line), 0, null))));
						ly += Theme.BODY_LINE;
					}
				}
				y += rowHeight + 1;
			}
			y += LINE_GAP;
		}

		/** Wrapped styled text, {@code indent} from the left, in {@code color} where a span has none of its own. */
		private void paragraph(List<WikiDoc.Span> spans, int indent, int color) {
			for (List<Piece> line : wrap(spans, width - indent)) {
				ops.add(new TextOp(indent, y, color, line));
				y += Theme.BODY_LINE;
			}
		}

		/** Splits styled spans into lines no wider than {@code maxWidth}, each line a list of pieces with their x. */
		private List<List<Piece>> wrap(List<WikiDoc.Span> spans, int maxWidth) {
			MutableComponent all = Component.empty();
			for (WikiDoc.Span span : spans) {
				Style style = UiFont.style(span.bold() ? UiFont.Face.STRONG : UiFont.Face.BODY);
				if (span.link() != null) {
					style = style.withInsertion(span.link());
				} else if (span.color() != 0) {
					style = style.withColor(span.color() & 0xFFFFFF);
				}
				all.append(Component.literal(span.text()).withStyle(style));
			}
			List<List<Piece>> lines = new ArrayList<>();
			for (FormattedText line : font.getSplitter().splitLines(all, maxWidth, Style.EMPTY)) {
				List<Piece> pieces = new ArrayList<>();
				int[] x = {0};
				line.visit((style, text) -> {
					if (!text.isEmpty()) {
						Style plain = style.withColor((TextColor) null);
						FormattedCharSequence sequence = Language.getInstance().getVisualOrder(FormattedText.of(text, plain));
						int w = font.width(sequence);
						String link = style.getInsertion();
						int color = link != null ? Theme.TEXT_LINK : style.getColor() != null ? readable(style.getColor().getValue()) : 0;
						pieces.add(new Piece(sequence, x[0], w, color, link));
						x[0] += w;
					}
					return Optional.empty();
				}, Style.EMPTY);
				if (!pieces.isEmpty()) {
					lines.add(List.copyOf(pieces));
				}
			}
			return lines;
		}

		private static int lineWidth(List<Piece> line) {
			Piece last = line.get(line.size() - 1);
			return last.x() + last.width();
		}

		/** A wiki color, lifted towards white when it is too dark to read on the plum glass (dark blue, black, dark gray). */
		private static int readable(int rgb) {
			int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
			float light = (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
			int color = 0xFF000000 | rgb;
			return light < 0.4f ? Colors.lerp(color, 0xFFFFFFFF, 0.45f) : color;
		}
	}

	/** The index into {@link Theme#RARITIES} of a rarity, or -1. */
	static int rarityIndex(String rarity) {
		if (rarity == null) {
			return -1;
		}
		String clean = rarity.toUpperCase(Locale.ROOT).replaceAll("[^A-Z ]", "").strip();
		for (int i = Theme.RARITIES.length - 1; i >= 0; i--) {              // VERY SPECIAL before SPECIAL
			if (clean.startsWith(Theme.RARITIES[i])) {
				return i;
			}
		}
		return -1;
	}
}
