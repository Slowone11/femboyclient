package com.femboyclient;

import java.util.List;

/**
 * A wiki page made simple enough to draw in the panel: a title, the item picture, its rarity, and a list of blocks (headings,
 * text, infobox rows, small tables, crafting recipes). Made by {@link WikiHtml}, drawn by {@link WikiPanel}. Plain data, no Minecraft classes.
 *
 * @param imageUrl the infobox picture, or null
 * @param rarity   "LEGENDARY" etc. from the infobox, or null
 * @param infobox  how many of the first blocks came from the infobox (the rest are the page's own text)
 */
public record WikiDoc(String title, String imageUrl, String rarity, List<Block> blocks, int infobox) {

	/** A run of text in one style. {@code color} is ARGB, 0 for the normal text color; {@code link} is a page title or null. */
	public record Span(String text, int color, boolean bold, String link) {
		public static Span plain(String text) {
			return new Span(text, 0, false, null);
		}
	}

	public sealed interface Block permits Heading, Text, Row, Table, Recipe {
	}

	/** A heading. Level 1 is an infobox group (Stats, Properties), 2 a page section, 3 and deeper smaller. */
	public record Heading(int level, String text) implements Block {
	}

	/** A paragraph, or a list item when {@code bullet} (indented {@code depth} levels, from 0). */
	public record Text(List<Span> spans, boolean bullet, int depth) implements Block {
	}

	/** One infobox line: "Rarity ...... LEGENDARY". */
	public record Row(String label, List<Span> value) implements Block {
	}

	/** One slot of a recipe: what is in it (its name, picture, wiki page) and how many. */
	public record Slot(String name, String image, String link, int count) {
	}

	/** A crafting table recipe: nine grid slots (row by row, null where empty) and what it makes. */
	public record Recipe(List<Slot> grid, Slot output) implements Block {

		/** Each ingredient once, with how many of it the whole grid takes, in grid order: "8× L.A.S.R.'s Eye". */
		public java.util.Map<String, Integer> totals() {
			java.util.Map<String, Integer> totals = new java.util.LinkedHashMap<>();
			for (Slot slot : grid) {
				if (slot != null) {
					totals.merge(slot.name(), slot.count(), Integer::sum);
				}
			}
			return totals;
		}

		/**
		 * Where things go, in words, for the common shapes: "All 9: Diamond"; "Center: Necron's Blade" and "Ring: 8 L.A.S.R.'s
		 * Eye"; "Any slot: Plasma". Empty for other shapes (the grid shows them).
		 */
		public List<String> placementHint() {
			Slot center = grid.get(4);
			Slot ring = grid.get(0);
			boolean sameRing = ring != null;
			for (int i : new int[] {1, 2, 3, 5, 6, 7, 8}) {
				sameRing = sameRing && grid.get(i) != null && grid.get(i).name().equals(ring.name());
			}
			if (sameRing && center != null && center.name().equals(ring.name())) {
				return List.of("All 9: " + ring.name());
			}
			if (sameRing) {
				return center == null ? List.of("Ring: 8 " + ring.name()) : List.of("Center: " + center.name(), "Ring: 8 " + ring.name());
			}
			Slot only = null;
			int used = 0;
			for (Slot slot : grid) {
				if (slot != null) {
					used++;
					only = slot;
				}
			}
			return used == 1 ? List.of("Any slot: " + only.name()) : List.of();
		}
	}

	/** A small table as plain cells; the first row is a header row when {@code header}. */
	public record Table(List<List<String>> rows, boolean header) implements Block {
	}
}
