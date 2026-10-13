package com.femboyclient;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a wiki page's HTML into a {@link WikiDoc}: the infobox becomes label/value rows (and gives the picture and rarity), the
 * body becomes headings, paragraphs, list items and small tables. Edit links, navboxes, references, the table of contents,
 * scripts, styles, crafting grids and inline icons are dropped. The wiki's in-game colors ({@code class="color-gold"}) are kept.
 * Kept free of Minecraft classes so it can be tested on its own.
 */
public final class WikiHtml {

	/** Bigger tables than this are left out: they would not fit the panel. */
	static final int MAX_TABLE_COLUMNS = 6, MAX_TABLE_ROWS = 30;

	/** The wiki's in-game color classes, as the game's chat colors. */
	private static final Map<String, Integer> COLORS = Map.ofEntries(
			Map.entry("black", 0xFF000000), Map.entry("dark-blue", 0xFF0000AA), Map.entry("dark-green", 0xFF00AA00),
			Map.entry("dark-aqua", 0xFF00AAAA), Map.entry("dark-red", 0xFFAA0000), Map.entry("dark-purple", 0xFFAA00AA),
			Map.entry("gold", 0xFFFFAA00), Map.entry("gray", 0xFFAAAAAA), Map.entry("dark-gray", 0xFF555555),
			Map.entry("blue", 0xFF5555FF), Map.entry("green", 0xFF55FF55), Map.entry("aqua", 0xFF55FFFF),
			Map.entry("red", 0xFFFF5555), Map.entry("light-purple", 0xFFFF55FF), Map.entry("yellow", 0xFFFFFF55),
			Map.entry("white", 0xFFFFFFFF));

	/** The game's color codes ("format-6" is gold), in code order. */
	private static final String FORMAT_CODES = "0123456789abcdef";
	private static final int[] COLOR_BY_CODE = {0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA, 0xFFAA0000, 0xFFAA00AA, 0xFFFFAA00,
			0xFFAAAAAA, 0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF, 0xFFFF5555, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF};

	private static final String REMOVED = "script, style, noscript, .mw-editsection, .navbox, table.navbox, .reference, "
			+ "sup.reference, .references, .reflist, .toc, #toc, .printfooter, .noprint, .mw-empty-elt, .gallery, "
			+ ".mw-references-wrap, .hidden, [style*='display:none'], [style*='display: none'], figure, .thumb, .wds-tabs__wrapper";

	private WikiHtml() {
	}

	public static WikiDoc parse(String title, String html) {
		Document doc = Jsoup.parseBodyFragment(html);
		doc.select(REMOVED).remove();
		doc.select(".mcui").not(".mcui-Crafting_Table").remove();          // furnaces, forges and the like: not drawn
		List<WikiDoc.Block> blocks = new ArrayList<>();

		String imageUrl = null, rarity = null;
		Element infobox = doc.selectFirst("aside.portable-infobox");
		if (infobox != null) {
			Element img = infobox.selectFirst(".pi-image img, .pi-data-value img");
			imageUrl = img == null ? null : imageSource(img);
			for (Element item : infobox.select(".pi-header, .pi-data, .pi-smart-group")) {
				if (item.hasClass("pi-header")) {
					String text = clean(item.text());
					if (!text.isEmpty()) {
						blocks.add(new WikiDoc.Heading(1, text));
					}
				} else if (item.hasClass("pi-smart-group")) {
					// Side-by-side cells: the labels in one row, the values in the next, paired by position.
					List<Element> labels = item.select(".pi-smart-data-label"), values = item.select(".pi-smart-data-value");
					for (int i = 0; i < Math.min(labels.size(), values.size()); i++) {
						rarity = row(clean(labels.get(i).text()), values.get(i), rarity, blocks);
					}
				} else if (!item.hasClass("pi-smart-data-value")) {
					Element label = item.selectFirst(".pi-data-label");
					Element value = item.selectFirst(".pi-data-value");
					if (value == null || value.selectFirst("img") != null && clean(value.text()).isEmpty()) {
						continue;                           // the picture rows
					}
					if (label == null) {
						addText(spans(value), false, 0, blocks);
					} else {
						rarity = row(clean(label.text()), value, rarity, blocks);
					}
				}
			}
			infobox.remove();
		}
		doc.select("aside").remove();

		List<WikiDoc.Block> infoboxBlocks = withoutEmptyHeadings(blocks);
		blocks.clear();
		Element root = doc.selectFirst(".mw-parser-output");
		walk(root != null ? root : doc.body(), blocks);
		List<WikiDoc.Block> all = new ArrayList<>(infoboxBlocks);
		all.addAll(withoutEmptyHeadings(blocks));
		return new WikiDoc(title, imageUrl, rarity, List.copyOf(all), infoboxBlocks.size());
	}

	/** Adds an infobox row; returns the rarity, from this row when it is the "Rarity" one. */
	private static String row(String name, Element value, String rarity, List<WikiDoc.Block> blocks) {
		List<WikiDoc.Span> spans = spans(value);
		if (name.isEmpty() || spans.isEmpty()) {
			return rarity;
		}
		blocks.add(new WikiDoc.Row(name, spans));
		return name.equalsIgnoreCase("Rarity") ? clean(value.text()).toUpperCase(Locale.ROOT) : rarity;
	}

	/** Drops headings with nothing under them before the next heading of the same or a higher level. */
	private static List<WikiDoc.Block> withoutEmptyHeadings(List<WikiDoc.Block> blocks) {
		List<WikiDoc.Block> kept = new ArrayList<>();
		for (int i = 0; i < blocks.size(); i++) {
			if (blocks.get(i) instanceof WikiDoc.Heading heading) {
				int next = i + 1;
				boolean empty = next >= blocks.size()
						|| blocks.get(next) instanceof WikiDoc.Heading after && after.level() <= heading.level();
				if (empty) {
					continue;
				}
			}
			kept.add(blocks.get(i));
		}
		return List.copyOf(kept);
	}

	// ------------------------------------------------------------ the body

	/** Goes through a container's children in order. Loose text between blocks is gathered into paragraphs. */
	private static void walk(Element container, List<WikiDoc.Block> blocks) {
		List<Node> loose = new ArrayList<>();
		for (Node node : container.childNodes()) {
			if (node instanceof Element element && isBlock(element)) {
				flush(loose, blocks);
				block(element, blocks);
			} else {
				loose.add(node);
			}
		}
		flush(loose, blocks);
	}

	/** Every crafting grid inside an element, as recipe blocks. */
	private static void recipes(Element container, List<WikiDoc.Block> blocks) {
		for (Element mcui : container.select(".mcui-Crafting_Table")) {
			WikiDoc.Recipe recipe = recipe(mcui);
			if (recipe != null) {
				blocks.add(recipe);
			}
		}
	}

	/** A crafting grid: three rows of three slots, and the output slot. Null when it is not shaped like one. */
	static WikiDoc.Recipe recipe(Element mcui) {
		List<WikiDoc.Slot> grid = new ArrayList<>();
		for (Element row : mcui.select(".mcui-input .mcui-row")) {
			for (Element slot : row.select("> .invslot")) {
				grid.add(slot(slot));
			}
		}
		Element output = mcui.selectFirst(".mcui-output .invslot");
		if (grid.size() != 9 || output == null || slot(output) == null) {
			return null;
		}
		return new WikiDoc.Recipe(java.util.Collections.unmodifiableList(grid), slot(output));
	}

	/** One slot; when it cycles through several items (any of a group), the first one. Null when empty. */
	private static WikiDoc.Slot slot(Element invslot) {
		Element item = invslot.selectFirst(".invslot-item[data-minetip-title]");
		if (item == null) {
			return null;
		}
		String name = item.attr("data-minetip-title").replaceAll("[&§][0-9a-fk-orA-FK-OR]", "").strip();
		Element img = item.selectFirst("img");
		Element a = item.selectFirst("a[href]");
		Element size = invslot.selectFirst(".invslot-stacksize");
		int count = 1;
		if (size != null) {
			try {
				count = Math.max(1, Integer.parseInt(size.text().strip()));
			} catch (NumberFormatException ignored) {
			}
		}
		return name.isEmpty() ? null : new WikiDoc.Slot(name, img == null ? null : imageSource(img),
				a == null ? name : java.util.Objects.requireNonNullElse(pageTitle(a.attr("href")), name), count);
	}

	private static boolean isBlock(Element e) {
		return switch (e.normalName()) {
			case "h1", "h2", "h3", "h4", "h5", "h6", "p", "ul", "ol", "dl", "table", "div", "section", "center", "blockquote", "hr" -> true;
			default -> false;
		};
	}

	private static void block(Element e, List<WikiDoc.Block> blocks) {
		switch (e.normalName()) {
			case "h1", "h2", "h3", "h4", "h5", "h6" -> {
				String text = clean(e.text());
				if (!text.isEmpty()) {
					blocks.add(new WikiDoc.Heading(Math.max(2, e.normalName().charAt(1) - '0'), text));
				}
			}
			case "p", "blockquote" -> addText(spans(e), false, 0, blocks);
			case "ul", "ol" -> list(e, 0, blocks);
			case "dl" -> {
				for (Element item : e.children()) {
					addText(spans(item), false, 0, blocks);
				}
			}
			case "table" -> {
				if (e.selectFirst(".mcui-Crafting_Table") != null) {
					recipes(e, blocks);                 // the recipe table: its grids say it all, the text cells only repeat them
				} else {
					table(e, blocks);
				}
			}
			case "hr" -> {
			}
			default -> {
				if (e.hasClass("mcui-Crafting_Table") || e.selectFirst(".mcui-Crafting_Table") != null
						&& e.select("p, ul, ol, table, h2, h3, h4").isEmpty()) {
					recipes(e, blocks);                 // a grid on its own, outside a table
				} else {
					walk(e, blocks);                    // div, section, center: whatever is inside
				}
			}
		}
	}

	private static void flush(List<Node> loose, List<WikiDoc.Block> blocks) {
		if (loose.isEmpty()) {
			return;
		}
		List<WikiDoc.Span> spans = new ArrayList<>();
		for (Node node : loose) {
			collect(node, 0, false, null, spans);
		}
		loose.clear();
		addText(spans, false, 0, blocks);
	}

	private static void list(Element list, int depth, List<WikiDoc.Block> blocks) {
		for (Element item : list.children()) {
			if (!item.normalName().equals("li")) {
				continue;
			}
			List<Element> nested = new ArrayList<>(item.select("> ul, > ol"));
			nested.forEach(Element::remove);
			addText(spans(item), true, depth, blocks);
			for (Element inner : nested) {
				list(inner, depth + 1, blocks);
			}
		}
	}

	private static void addText(List<WikiDoc.Span> spans, boolean bullet, int depth, List<WikiDoc.Block> blocks) {
		List<WikiDoc.Span> trimmed = trim(spans);
		if (!trimmed.isEmpty()) {
			blocks.add(new WikiDoc.Text(trimmed, bullet, depth));
		}
	}

	/** A simple table as plain text cells. Tables inside tables, and tables too big for the panel, are left out. */
	private static void table(Element table, List<WikiDoc.Block> blocks) {
		if (!table.select("table table").isEmpty()) {
			return;
		}
		List<List<String>> rows = new ArrayList<>();
		boolean header = false;
		for (Element tr : table.select("tr")) {
			List<String> cells = new ArrayList<>();
			for (Element cell : tr.select("> th, > td")) {
				cells.add(clean(cell.text()));
			}
			if (cells.isEmpty() || cells.stream().allMatch(String::isEmpty)) {
				continue;
			}
			if (cells.size() > MAX_TABLE_COLUMNS) {
				return;
			}
			if (rows.isEmpty()) {
				header = tr.select("> td").isEmpty();
			}
			rows.add(List.copyOf(cells));
			if (rows.size() >= MAX_TABLE_ROWS) {
				break;
			}
		}
		if (!rows.isEmpty()) {
			blocks.add(new WikiDoc.Table(List.copyOf(rows), header));
		}
	}

	// ------------------------------------------------------------ inline text

	/** The styled runs of text inside an element. */
	static List<WikiDoc.Span> spans(Element element) {
		List<WikiDoc.Span> spans = new ArrayList<>();
		for (Node node : element.childNodes()) {
			collect(node, 0, false, null, spans);
		}
		return trim(spans);
	}

	private static void collect(Node node, int color, boolean bold, String link, List<WikiDoc.Span> spans) {
		if (node instanceof TextNode text) {
			String value = text.getWholeText().replace(' ', ' ').replaceAll("\\s+", " ");
			if (!value.isEmpty()) {
				add(spans, new WikiDoc.Span(value, color, bold, link));
			}
			return;
		}
		if (!(node instanceof Element e)) {
			return;
		}
		switch (e.normalName()) {
			case "img", "sup", "sub" -> {
				return;
			}
			case "br" -> {
				add(spans, new WikiDoc.Span(" ", color, bold, link));
				return;
			}
			case "b", "strong" -> bold = true;
			case "a" -> link = link != null ? link : pageTitle(e.attr("href"));
			default -> {
			}
		}
		for (String cls : e.classNames()) {
			String name = cls.replace('_', '-');
			if (name.startsWith("color-") && COLORS.containsKey(name.substring(6))) {
				color = COLORS.get(name.substring(6));
			} else if (name.length() == 8 && name.startsWith("format-") && FORMAT_CODES.indexOf(name.charAt(7)) >= 0) {
				color = COLOR_BY_CODE[FORMAT_CODES.indexOf(name.charAt(7))];
			}
		}
		for (Node child : e.childNodes()) {
			collect(child, color, bold, link, spans);
		}
	}

	/** Joins a span onto the one before when they look the same, so a paragraph is a few long runs. */
	private static void add(List<WikiDoc.Span> spans, WikiDoc.Span span) {
		if (!spans.isEmpty()) {
			WikiDoc.Span last = spans.get(spans.size() - 1);
			if (last.color() == span.color() && last.bold() == span.bold() && java.util.Objects.equals(last.link(), span.link())) {
				spans.set(spans.size() - 1, new WikiDoc.Span(last.text() + span.text(), last.color(), last.bold(), last.link()));
				return;
			}
			if (last.text().endsWith(" ") && span.text().startsWith(" ")) {
				span = new WikiDoc.Span(span.text().substring(1), span.color(), span.bold(), span.link());
				if (span.text().isEmpty()) {
					return;
				}
			}
		}
		spans.add(span);
	}

	/** Without spaces at the very start and end. */
	private static List<WikiDoc.Span> trim(List<WikiDoc.Span> spans) {
		List<WikiDoc.Span> out = new ArrayList<>(spans);
		while (!out.isEmpty() && out.get(0).text().isBlank()) {
			out.remove(0);
		}
		while (!out.isEmpty() && out.get(out.size() - 1).text().isBlank()) {
			out.remove(out.size() - 1);
		}
		if (!out.isEmpty()) {
			WikiDoc.Span first = out.get(0), last;
			out.set(0, new WikiDoc.Span(first.text().stripLeading(), first.color(), first.bold(), first.link()));
			last = out.get(out.size() - 1);
			out.set(out.size() - 1, new WikiDoc.Span(last.text().stripTrailing(), last.color(), last.bold(), last.link()));
		}
		return List.copyOf(out);
	}

	/** The wiki page a link goes to ("/wiki/Necron%27s_Blade" is "Necron's Blade"), or null for anything else. */
	static String pageTitle(String href) {
		if (!href.startsWith("/wiki/")) {
			return null;
		}
		String path = href.substring(6);
		int hash = path.indexOf('#');
		if (hash >= 0) {
			path = path.substring(0, hash);
		}
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		String title = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8).replace('_', ' ').strip();
		return title.isEmpty() || title.contains(":") ? null : title;      // File:, Category:, Special: pages are not articles
	}

	/** An image's address: lazy-loaded ones keep it in data-src, and src is a placeholder. */
	static String imageSource(Element img) {
		String src = img.attr("data-src");
		if (src.isEmpty()) {
			src = img.attr("src");
		}
		return src.isEmpty() || src.startsWith("data:") ? null : src;
	}

	private static String clean(String text) {
		return text.replace(' ', ' ').replaceAll("\\s+", " ").strip();
	}
}
