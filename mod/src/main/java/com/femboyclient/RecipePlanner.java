package com.femboyclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recipe planners: small glass cards popped out of the item sheet's "Pop out recipe" button. Each shows "Craft Hyperion" with
 * − ×1 + to set how many, a small plain 3×3 grid showing where each item goes (with the shape in words when it is a common one:
 * "Center: ..., Ring: 8 ..."), what you have of each ingredient against what you need with a progress bar (counted from your
 * inventory as you play), and the raw materials, folded away until asked for (the ingredients' own recipes, fetched in the
 * background). Hover a slot for its name, click it for its page.
 *
 * A card moves by dragging it. The pin keeps it: pinned cards come back on every inventory and after a restart (saved in
 * {@code femboyclient/wiki/planners.json}); an unpinned one goes away when the inventory closes. Several can be open; × closes
 * one. Shown on container screens only. Game thread only.
 */
final class RecipePlanner {

	static final int WIDTH = 184;
	private static final int PAD = 9, HEADER = 16, CHIP = 14, SLOT = 15, ROW = 19, ICON = 12, MAX_QTY = 999;

	static final class Card {
		final String title;
		final WikiDoc.Recipe recipe;
		int x, y, qty = 1;
		boolean pinned, rawOpen;
		Map<String, Long> raw;                         // null until fetched
		boolean rawAsked;
		final Map<String, String> images = new HashMap<>();

		Card(String title, WikiDoc.Recipe recipe, int x, int y) {
			this.title = title;
			this.recipe = recipe;
			this.x = x;
			this.y = y;
			for (WikiDoc.Slot slot : recipe.grid()) {
				if (slot != null && slot.image() != null) {
					images.putIfAbsent(slot.name(), slot.image());
				}
			}
		}
	}

	private static List<Card> cards;
	private static Card dragging;
	private static int dragDX, dragDY;
	private static final Map<String, FormattedCharSequence> TEXTS = new HashMap<>();
	private static final Map<String, UiFont.Label> LABELS = new HashMap<>();

	private RecipePlanner() {
	}

	private static List<Card> cards() {
		if (cards == null) {
			cards = load();
		}
		return cards;
	}

	/** Pops a planner out for a recipe, with its top-left at (x, y); an open one for the same item comes to the top instead. */
	static void open(String title, WikiDoc.Recipe recipe, int x, int y) {
		for (Card card : cards()) {
			if (card.title.equalsIgnoreCase(title)) {
				cards.remove(card);
				cards.add(card);
				return;
			}
		}
		cards().add(new Card(title, recipe, x, y));
	}

	/** The inventory closed: unpinned planners go. */
	static void onScreenClosed() {
		if (cards != null) {
			cards.removeIf(card -> !card.pinned);
		}
		if (dragging != null) {
			dragging = null;
			save();
		}
	}

	// ------------------------------------------------------------ layout

	static int height(Card card) {
		int h = PAD + HEADER + 6 + RecipeView.grid(SLOT) + 10 + Theme.LABEL_LINE + card.recipe.totals().size() * ROW + 4 + Theme.LABEL_LINE;
		if (card.rawOpen) {
			h += 4 + Math.max(1, card.raw == null ? 1 : card.raw.size()) * Theme.BODY_LINE;
		}
		if (!card.pinned) {
			h += 4 + Theme.BODY_LINE;                       // the hint
		}
		return h + PAD - 2;
	}

	/** Header controls, right to left: 0 close, 1 pin, 2 plus, 3 minus. The quantity sits between minus and plus. */
	private static int[] control(Card card, Font font, int index) {
		int top = card.y + PAD - 1, right = card.x + WIDTH - PAD;
		int qty = qtyWidth(card, font);
		int x1 = switch (index) {
			case 0 -> right;
			case 1 -> right - CHIP - 4;
			case 2 -> right - 2 * (CHIP + 4) - 4;
			default -> right - 3 * (CHIP + 4) - 4 - qty - 4;
		};
		return new int[] {x1 - CHIP, top, x1, top + CHIP};
	}

	private static int qtyWidth(Card card, Font font) {
		return font.width(text("×" + card.qty, UiFont.Face.STRONG));
	}

	private static int gridY(Card card) {
		return card.y + PAD + HEADER + 6;
	}

	private static int rawToggleY(Card card) {
		return gridY(card) + RecipeView.grid(SLOT) + 10 + Theme.LABEL_LINE + card.recipe.totals().size() * ROW + 4;
	}

	// ------------------------------------------------------------ drawing

	static void render(GuiGraphicsExtractor g, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY) {
		Card top = topAt(mouseX, mouseY);
		for (Card card : cards()) {
			card.x = Math.max(0, Math.min(screenWidth - WIDTH, card.x));          // a spot from a bigger window or GUI scale
			card.y = Math.max(0, Math.min(screenHeight - height(card), card.y));
			draw(g, font, card, mouseX, mouseY, card == top && dragging == null);
		}
	}

	private static void draw(GuiGraphicsExtractor g, Font font, Card card, int mouseX, int mouseY, boolean hover) {
		int x = card.x, y = card.y, w = WIDTH, h = height(card);
		UiDraw.shadow(g, x, y, x + w, y + h, Theme.CARD_RADIUS, 12);
		UiDraw.roundRect(g, x, y, x + w, y + h, Theme.CARD_RADIUS, Colors.withAlpha(Theme.BACKDROP, 170));
		UiDraw.glass(g, x, y, x + w, y + h, Theme.CARD_RADIUS);

		// Header: "Craft Hyperion", then − ×1 + pin ×.
		int minusX = control(card, font, 3)[0];
		String title = UiFont.fit(font, "Craft " + card.title, UiFont.Face.STRONG, minusX - 6 - (x + PAD));
		UiDraw.text(g, font, text(title, UiFont.Face.STRONG), x + PAD, UiFont.centeredY(UiFont.Face.STRONG, y + PAD - 1, CHIP), Theme.TEXT);
		String[] glyphs = {"×", "", "+", "−"};
		for (int i = 0; i < 4; i++) {
			int[] b = control(card, font, i);
			boolean over = hover && UiDraw.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
			if (i == 1) {
				UiDraw.accentCard(g, b[0], b[1], b[2], b[3], Theme.BADGE_RADIUS, Theme.ACCENT, card.pinned, over);
				pin(g, b, card.pinned || over);
				continue;
			}
			UiDraw.hoverCard(g, b[0], b[1], b[2], b[3], Theme.BADGE_RADIUS, over);
			FormattedCharSequence glyph = text(glyphs[i], UiFont.Face.STRONG);
			UiDraw.text(g, font, glyph, b[0] + (CHIP - font.width(glyph) + 1) / 2, UiFont.centeredY(UiFont.Face.STRONG, b[1], CHIP),
					over ? Theme.ACCENT_LIGHT : Theme.TEXT_DIM);
		}
		int plusX0 = control(card, font, 2)[0];
		FormattedCharSequence qty = text("×" + card.qty, UiFont.Face.STRONG);
		UiDraw.text(g, font, qty, plusX0 - 4 - font.width(qty), UiFont.centeredY(UiFont.Face.STRONG, y + PAD - 1, CHIP), Theme.ACCENT_LIGHT);

		// The grid, the output, and the shape in words.
		int gy = gridY(card);
		RecipeView.draw(g, font, card.recipe, x + PAD, gy, SLOT, mouseX, mouseY, hover);
		int hintX = x + PAD + RecipeView.width(SLOT) + 8, hintRoom = x + w - PAD - hintX;
		List<String> hint = card.recipe.placementHint();
		int hy = gy + (RecipeView.grid(SLOT) - hint.size() * Theme.BODY_LINE) / 2;
		for (String line : hint) {
			UiDraw.text(g, font, text(UiFont.fit(font, line, UiFont.Face.BODY, hintRoom), UiFont.Face.BODY), hintX, UiFont.topY(UiFont.Face.BODY, hy),
					Theme.TEXT_DIM);
			hy += Theme.BODY_LINE;
		}

		// What you have against what you need.
		int ry = gy + RecipeView.grid(SLOT) + 10;
		UiDraw.label(g, font, label(font, "You have"), x + PAD, UiFont.topY(UiFont.Face.LABEL, ry), Theme.TEXT_LABEL);
		ry += Theme.LABEL_LINE;
		for (Map.Entry<String, Integer> part : card.recipe.totals().entrySet()) {
			String name = part.getKey();
			long need = (long) part.getValue() * card.qty;
			int have = InventoryCounts.count(name);
			boolean done = have >= need;
			String image = card.images.get(name);
			if (!WikiImages.draw(g, image, x + PAD, ry, ICON)) {
				ItemIcons.draw(g, name, x + PAD, ry, ICON);
			}
			String count = done ? Math.min(have, need) + "/" + need + " ✓" : have + "/" + need;
			FormattedCharSequence countText = text(count, UiFont.Face.STRONG);
			int countX = x + w - PAD - font.width(countText);
			UiDraw.text(g, font, text(UiFont.fit(font, name, UiFont.Face.BODY, countX - 6 - (x + PAD + ICON + 5)), UiFont.Face.BODY),
					x + PAD + ICON + 5, UiFont.centeredY(UiFont.Face.BODY, ry, ICON), Theme.TEXT);
			UiDraw.text(g, font, countText, countX, UiFont.centeredY(UiFont.Face.STRONG, ry, ICON), done ? Theme.TEXT_OK : Theme.ACCENT_LIGHT);
			int barY = ry + ICON + 2, barX1 = x + w - PAD;
			UiDraw.pill(g, x + PAD, barY, barX1, barY + 3, Theme.SCROLLBAR_TRACK);
			float part01 = need == 0 ? 1f : Math.min(1f, have / (float) need);
			if (part01 > 0) {
				UiDraw.pill(g, x + PAD, barY, x + PAD + Math.max(3, Math.round((barX1 - x - PAD) * part01)), barY + 3, done ? Theme.TEXT_OK : Theme.ACCENT);
			}
			ry += ROW;
		}

		// Raw materials, folded away until asked for.
		int ty = rawToggleY(card);
		boolean overRaw = hover && UiDraw.inside(mouseX, mouseY, x + PAD, ty - 2, x + w - PAD, ty + Theme.LABEL_LINE);
		UiDraw.label(g, font, label(font, card.rawOpen ? "Raw materials ▾" : "Raw materials ▸"), x + PAD, UiFont.topY(UiFont.Face.LABEL, ty),
				overRaw ? Theme.ACCENT_LIGHT : Theme.TEXT_LABEL);
		int ly = ty + Theme.LABEL_LINE + 4;
		if (card.rawOpen) {
			askRaw(card);
			if (card.raw == null) {
				UiDraw.text(g, font, text("Loading…", UiFont.Face.BODY), x + PAD, UiFont.topY(UiFont.Face.BODY, ly), Theme.TEXT_DIM);
				ly += Theme.BODY_LINE;
			} else {
				for (Map.Entry<String, Long> raw : card.raw.entrySet()) {
					String line = String.format("%,d× %s", raw.getValue() * card.qty, raw.getKey());
					UiDraw.text(g, font, text(UiFont.fit(font, line, UiFont.Face.BODY, w - 2 * PAD), UiFont.Face.BODY), x + PAD,
							UiFont.topY(UiFont.Face.BODY, ly), Theme.TEXT_DIM);
					ly += Theme.BODY_LINE;
				}
			}
		}
		if (!card.pinned) {
			FormattedCharSequence tip = text(UiFont.fit(font, "Hover a slot for its name · pin to keep it", UiFont.Face.BODY, w - 2 * PAD),
					UiFont.Face.BODY);
			UiDraw.text(g, font, tip, x + (w - font.width(tip)) / 2, UiFont.topY(UiFont.Face.BODY, ly + 4), Theme.TEXT_LABEL);
		}
	}

	/** Starts breaking the recipe down to raw materials, once, in the background. */
	private static void askRaw(Card card) {
		if (card.rawAsked) {
			return;
		}
		card.rawAsked = true;
		Minecraft mc = Minecraft.getInstance();
		new RawMaterials(WikiPanel::fetchDoc).resolve(card.recipe)
				.whenComplete((raw, failure) -> mc.execute(() -> card.raw = raw != null ? raw : Map.of()));
	}

	/** A small drawn pin: a round head on a short needle. */
	private static void pin(GuiGraphicsExtractor g, int[] b, boolean lit) {
		int cx = (b[0] + b[2]) / 2, color = lit ? Theme.TEXT : Theme.TEXT_DIM;
		UiDraw.pill(g, cx - 3, b[1] + 3, cx + 3, b[1] + 8, color);
		UiDraw.fill(g, cx, b[1] + 8, cx + 1, b[3] - 3, color);
	}

	private static FormattedCharSequence text(String text, UiFont.Face face) {
		if (TEXTS.size() > 600) {
			TEXTS.clear();
		}
		return TEXTS.computeIfAbsent(face.name() + "|" + text, k -> UiFont.sequence(text, face));
	}

	private static UiFont.Label label(Font font, String text) {
		return LABELS.computeIfAbsent(text, t -> UiFont.Label.of(font, t));
	}

	/** The card on top under (mx, my), or null. */
	private static Card topAt(double mx, double my) {
		List<Card> all = cards();
		for (int i = all.size() - 1; i >= 0; i--) {
			Card card = all.get(i);
			if (UiDraw.inside(mx, my, card.x, card.y, card.x + WIDTH, card.y + height(card))) {
				return card;
			}
		}
		return null;
	}

	// ------------------------------------------------------------ input

	/** A click. Returns true if it was on a card (then nothing under it gets it). */
	static boolean click(double mx, double my, int button) {
		Card card = topAt(mx, my);
		if (card == null) {
			return false;
		}
		if (button != 0) {
			return true;
		}
		cards.remove(card);
		cards.add(card);                                // clicked cards come to the top
		Font font = Minecraft.getInstance().font;
		for (int i = 0; i < 4; i++) {
			int[] b = control(card, font, i);
			if (UiDraw.inside(mx, my, b[0], b[1], b[2], b[3])) {
				switch (i) {
					case 0 -> cards.remove(card);
					case 1 -> card.pinned = !card.pinned;
					case 2 -> card.qty = Math.min(MAX_QTY, card.qty + 1);
					default -> card.qty = Math.max(1, card.qty - 1);
				}
				save();
				return true;
			}
		}
		WikiDoc.Slot slot = RecipeView.slotAt(card.recipe, card.x + PAD, gridY(card), SLOT, mx, my);
		if (slot != null) {
			WikiPanel.open(slot.link());
			return true;
		}
		int ty = rawToggleY(card);
		if (UiDraw.inside(mx, my, card.x + PAD, ty - 2, card.x + WIDTH - PAD, ty + Theme.LABEL_LINE)) {
			card.rawOpen = !card.rawOpen;
			save();
			return true;
		}
		dragging = card;                                // anywhere else on the card moves it
		dragDX = (int) mx - card.x;
		dragDY = (int) my - card.y;
		return true;
	}

	static boolean drag(double mx, double my) {
		if (dragging == null) {
			return false;
		}
		dragging.x = (int) mx - dragDX;
		dragging.y = (int) my - dragDY;
		return true;
	}

	static boolean release() {
		if (dragging == null) {
			return false;
		}
		dragging = null;
		save();
		return true;
	}

	// ------------------------------------------------------------ on disk (pinned cards only)

	private static Path file() {
		return FabricLoader.getInstance().getGameDir().resolve("femboyclient").resolve("wiki").resolve("planners.json");
	}

	private static void save() {
		JsonArray all = new JsonArray();
		for (Card card : cards()) {
			if (!card.pinned) {
				continue;
			}
			JsonObject saved = new JsonObject();
			saved.addProperty("title", card.title);
			saved.addProperty("x", card.x);
			saved.addProperty("y", card.y);
			saved.addProperty("qty", card.qty);
			saved.addProperty("rawOpen", card.rawOpen);
			JsonArray grid = new JsonArray();
			card.recipe.grid().forEach(slot -> grid.add(toJson(slot)));
			saved.add("grid", grid);
			saved.add("output", toJson(card.recipe.output()));
			all.add(saved);
		}
		try {
			Path file = file();
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling("planners.json.tmp");
			Files.writeString(temp, all.toString());
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
			// not saved: the cards still work until the game closes
		}
	}

	private static List<Card> load() {
		List<Card> loaded = new ArrayList<>();
		try {
			Path file = file();
			if (Files.exists(file)) {
				for (JsonElement element : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
					JsonObject saved = element.getAsJsonObject();
					List<WikiDoc.Slot> grid = new ArrayList<>();
					for (JsonElement slot : saved.getAsJsonArray("grid")) {
						grid.add(fromJson(slot));
					}
					WikiDoc.Slot output = fromJson(saved.get("output"));
					if (grid.size() == 9 && output != null) {
						Card card = new Card(saved.get("title").getAsString(), new WikiDoc.Recipe(Collections.unmodifiableList(grid), output),
								saved.get("x").getAsInt(), saved.get("y").getAsInt());
						card.qty = Math.max(1, saved.get("qty").getAsInt());
						card.rawOpen = saved.has("rawOpen") && saved.get("rawOpen").getAsBoolean();
						card.pinned = true;
						loaded.add(card);
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			// a broken file: start with no cards
		}
		return loaded;
	}

	private static JsonElement toJson(WikiDoc.Slot slot) {
		if (slot == null) {
			return JsonNull.INSTANCE;
		}
		JsonObject saved = new JsonObject();
		saved.addProperty("name", slot.name());
		saved.addProperty("image", slot.image());
		saved.addProperty("link", slot.link());
		saved.addProperty("count", slot.count());
		return saved;
	}

	private static WikiDoc.Slot fromJson(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		JsonObject saved = element.getAsJsonObject();
		JsonElement image = saved.get("image");
		return new WikiDoc.Slot(saved.get("name").getAsString(), image == null || image.isJsonNull() ? null : image.getAsString(),
				saved.get("link").getAsString(), saved.get("count").getAsInt());
	}
}
