package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiHtmlTest {

	/** Shaped like a Fandom page from the parse API. */
	private static final String HTML = """
			<div class="mw-parser-output">
			<aside class="portable-infobox">
			  <h2 class="pi-title">Hyperion</h2>
			  <div class="pi-data"><div class="pi-data-value"><img src="data:image/gif;base64,AA" data-src="https://img/Iron_Sword.png/revision/latest?cb=1"></div></div>
			  <div class="pi-data"><h3 class="pi-data-label">Rarity</h3>
			    <div class="pi-data-value"><a class="external" href="https://hysb.wiki/w/Rarity"><span class="color-gold">LEGENDARY</span></a></div></div>
			  <section class="pi-group"><h2 class="pi-header">Stats</h2>
			    <div class="pi-data"><h3 class="pi-data-label">❁&#160;Dmg</h3><div class="pi-data-value">260</div></div></section>
			  <section class="pi-group"><h2 class="pi-header">Empty group</h2></section>
			  <section class="pi-group"><h2 class="pi-header">Properties</h2>
			    <section class="pi-smart-group"><section class="pi-smart-group-head"><h3 class="pi-smart-data-label">Salable</h3></section>
			    <section class="pi-smart-group-body"><div class="pi-smart-data-value"><span class="color-red">No</span></div></section></section></section>
			</aside>
			The <b>Hyperion</b> is a <a href="/wiki/Sword" title="Sword">Sword</a>.<sup class="reference">[1]</sup>
			<h2><span class="mw-headline">Obtaining</span><span class="mw-editsection">[edit]</span></h2>
			<p>Craft it from <a href="/wiki/Necron%27s_Blade#Recipe">Necron's Blade</a> and <a href="/wiki/File:X.png">a file</a>.</p>
			<ul><li>One<ul><li>Inner</li></ul></li><li>Two</li></ul>
			<table><tr><th>Level</th><th>Cost</th></tr><tr><td>1</td><td>150</td></tr></table>
			<table class="navbox"><tr><td>Navigation</td></tr></table>
			<h2>Empty section</h2>
			<script>alert(1)</script>
			</div>""";

	private final WikiDoc doc = WikiHtml.parse("Hyperion", HTML);

	@Test
	void infoboxGivesPictureRarityAndRows() {
		assertEquals("https://img/Iron_Sword.png/revision/latest?cb=1", doc.imageUrl());
		assertEquals("LEGENDARY", doc.rarity());
		WikiDoc.Row rarity = (WikiDoc.Row) doc.blocks().get(0);
		assertEquals("Rarity", rarity.label());
		assertEquals(0xFFFFAA00, rarity.value().get(0).color());
		assertEquals(new WikiDoc.Heading(1, "Stats"), doc.blocks().get(1));
		assertEquals("❁ Dmg", ((WikiDoc.Row) doc.blocks().get(2)).label());
		assertEquals(new WikiDoc.Heading(1, "Properties"), doc.blocks().get(3));
		WikiDoc.Row salable = (WikiDoc.Row) doc.blocks().get(4);
		assertEquals("Salable", salable.label());
		assertEquals("No", salable.value().get(0).text());
	}

	@Test
	void looseTextBecomesAParagraphWithBoldAndLinks() {
		WikiDoc.Text intro = (WikiDoc.Text) doc.blocks().get(5);
		assertEquals("The Hyperion is a Sword.", text(intro.spans()));
		assertTrue(intro.spans().get(1).bold());
		assertEquals("Sword", intro.spans().get(3).link());
	}

	@Test
	void editLinksReferencesNavboxesScriptsAndEmptyHeadingsAreDropped() {
		String all = doc.blocks().toString();
		assertFalse(all.contains("[edit]"));
		assertFalse(all.contains("[1]"));
		assertFalse(all.contains("Navigation"));
		assertFalse(all.contains("alert"));
		assertFalse(all.contains("Empty"));
		assertEquals(new WikiDoc.Heading(2, "Obtaining"), doc.blocks().get(6));
	}

	@Test
	void linksToArticlesOnly() {
		WikiDoc.Text p = (WikiDoc.Text) doc.blocks().get(7);
		assertEquals("Necron's Blade", p.spans().get(1).link());
		assertEquals(3, p.spans().size());                       // File: pages are not articles: plain text
		assertNull(p.spans().get(2).link());
	}

	@Test
	void nestedListsKeepTheirDepth() {
		assertEquals(new WikiDoc.Text(List.of(WikiDoc.Span.plain("One")), true, 0), doc.blocks().get(8));
		assertEquals(new WikiDoc.Text(List.of(WikiDoc.Span.plain("Inner")), true, 1), doc.blocks().get(9));
		assertEquals(new WikiDoc.Text(List.of(WikiDoc.Span.plain("Two")), true, 0), doc.blocks().get(10));
	}

	@Test
	void simpleTablesAreKept() {
		WikiDoc.Table table = assertInstanceOf(WikiDoc.Table.class, doc.blocks().get(11));
		assertTrue(table.header());
		assertEquals(List.of(List.of("Level", "Cost"), List.of("1", "150")), table.rows());
		assertEquals(12, doc.blocks().size());
	}

	@Test
	void pageTitles() {
		assertEquals("Necron's Blade", WikiHtml.pageTitle("/wiki/Necron%27s_Blade#Recipe"));
		assertNull(WikiHtml.pageTitle("/wiki/Category:Swords"));
		assertNull(WikiHtml.pageTitle("https://hysb.wiki/w/Damage"));
		assertEquals("C++", WikiHtml.pageTitle("/wiki/C++"));
	}

	private static String text(List<WikiDoc.Span> spans) {
		StringBuilder out = new StringBuilder();
		spans.forEach(s -> out.append(s.text()));
		return out.toString();
	}

	@Test
	void craftingGridsBecomeRecipes() {
		String slot = "<span class=\"invslot\"><span class=\"invslot-item invslot-item-image\" data-minetip-title=\"&amp;bDiamond\">"
				+ "<a href=\"/wiki/Diamond\"><img src=\"data:x\" data-src=\"https://img/Diamond.png/revision/latest/scale-to-width-down/32\">"
				+ "</a></span><span class=\"invslot-stacksize\">32</span></span>";
		String empty = "<span class=\"invslot\"><span class=\"invslot-item\"></span></span>";
		String row = "<div class=\"mcui-row\">" + slot + empty + slot + "</div>";
		String html = "<table class=\"wikitable\"><tr><th>Ingredients</th><th>Recipe</th></tr><tr><td>160x Diamond</td><td>"
				+ "<span class=\"mcui mcui-Crafting&#95;Table\"><div class=\"mcui-input\">" + row + row + row + "</div>"
				+ "<span class=\"mcui-output\"><span class=\"invslot\"><span class=\"invslot-item\" data-minetip-title=\"Enchanted Diamond\">"
				+ "<span><img data-src=\"https://img/Enchanted_Diamond.gif\"></span></span></span></span></span></td></tr></table>"
				+ "<span class=\"mcui mcui-Furnace\">smelting</span>";
		WikiDoc page = WikiHtml.parse("Enchanted Diamond", html);
		assertEquals(1, page.blocks().size());                  // the recipe table becomes the recipe; furnaces are left out
		WikiDoc.Recipe recipe = assertInstanceOf(WikiDoc.Recipe.class, page.blocks().get(0));
		assertEquals(9, recipe.grid().size());
		assertNull(recipe.grid().get(1));
		WikiDoc.Slot diamond = recipe.grid().get(0);
		assertEquals(new WikiDoc.Slot("Diamond", "https://img/Diamond.png/revision/latest/scale-to-width-down/32", "Diamond", 32), diamond);
		assertEquals("Enchanted Diamond", recipe.output().name());
		assertEquals("Enchanted Diamond", recipe.output().link());          // no link: its own name
		assertEquals(java.util.Map.of("Diamond", 192), recipe.totals());
	}
}
