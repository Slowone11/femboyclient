package com.femboyclient;

import com.femboyclient.mixin.AbstractContainerScreenAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Wiki Bar module: a text bar centered under every container screen (your inventory, chests, menus, storage). Math typed into
 * it is worked out live, with the answer at the right end of the bar ("= 50,000,000 · 50m"); Enter copies it. Anything else is a
 * SkyBlock wiki search: results show above the bar as you type, each with the item's picture, its name and a dot in its rarity
 * color, grouped under ITEMS, with your three latest RECENT searches and calculations under them (and on their own while the bar
 * is empty; "Show more" lists the rest).
 * Enter (or a click) opens the item in {@link WikiPanel}.
 *
 * While the bar is focused it takes every key, so typing "e" does not close the inventory and number keys do not swap hotbar
 * slots. Esc leaves the bar first and closes the screen on the second press. Ctrl+F or a click focuses it. The text stays
 * between screens. Ctrl+drag moves the bar (the spot is saved) and Ctrl+right click puts it back under the inventory; a plain
 * right click clears it. Hooked onto screens with {@link ScreenEvents} like {@link LoopedClickerOverlay}.
 *
 * A key (none by default; set it in the game's Controls) opens the wiki page of the item under the mouse.
 */
final class WikiBar {

	private static final int BAR_MIN_WIDTH = 180;
	private static final int BAR_MAX_WIDTH = 260;
	private static final int MARGIN = 4;              // the least space kept between the bar and the screen's edge
	private static final int HISTORY_SIZE = 50;
	private static final int SUGGESTIONS = 8;
	private static final int ITEM_SUGGESTIONS = 5;    // item names first, then the wiki's own titles
	private static final int LIST_MIN_WIDTH = 200;
	private static final int ROW = Theme.SUGGESTION_ROW_HEIGHT, GROUP = 15, FOOTER = 15, ICON = 16;

	static final UiTextField FIELD = new UiTextField(256, "Search wiki or calculate…");
	private static final Calculator CALCULATOR = new Calculator();
	static final RecentSearches RECENT = new RecentSearches(
			FabricLoader.getInstance().getGameDir().resolve("femboyclient").resolve("wiki").resolve("recent.json"));

	/** What the text in the bar is. */
	enum Mode { EMPTY, CALCULATION, MATH_ERROR, SEARCH }

	private static Mode mode = Mode.EMPTY;
	private static double answer;                      // the live answer while mode is CALCULATION
	private static double ans;                         // the last answer copied with Enter, for "ans"
	private static FormattedCharSequence answerText, shortText, errorText;
	private static int answerWidth, shortWidth, errorWidth;
	private static long copiedAt;
	private static FormattedCharSequence copiedText;
	private static int copiedWidth;
	private static int textWidth;                      // the typed text's width, so the bar can grow to fit it and the answer

	private static final List<String> history = new ArrayList<>();
	private static int historyAt = -1;                 // -1: not browsing; else an index into history, 0 = newest
	private static String draft = "";                  // what was typed before browsing started

	// The result list above the bar: group titles, items, recent pages and calculations.
	enum Kind { GROUP, ITEM, RECENT_PAGE, RECENT_CALCULATION, MORE }

	private static final int RECENT_SHOWN = 3;
	private static boolean recentExpanded;             // "Show more" was chosen; until the bar loses focus

	private record Entry(Kind kind, String text, String answer, FormattedCharSequence shown) {
		boolean selectable() {
			return kind != Kind.GROUP;
		}

		int height() {
			return kind == Kind.GROUP ? GROUP : ROW;
		}
	}

	private static List<String> suggestions = List.of();
	private static final List<Entry> entries = new ArrayList<>();
	private static int selected = -1;                  // -1: none; else an index into entries
	private static boolean suggestionsHidden;          // after Enter opened a page, until the text changes
	private static String searchPending;               // asked of the wiki once the typing pauses
	private static long searchDueAt;
	private static int searchRequest;
	private static final Map<String, UiFont.Label> LABELS = new HashMap<>();
	private static FormattedCharSequence footerText, recentGlyph, equalsGlyph;

	private static KeyMapping wikiKey;

	// Moving the bar: a press on it becomes a move once the mouse has gone DRAG_START pixels.
	private static final int DRAG_START = 4;
	private static boolean pressedBar, movingBar;
	private static double pressX, pressY;
	private static int moveDX, moveDY;

	private WikiBar() {
	}

	/** Registers the hooks and the item key. Call once at start-up, after {@link PlayerInfoKey#register} (its key category). */
	static void register() {
		wikiKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + FemboyClientMod.MOD_ID + ".wiki",
				InputConstants.UNKNOWN.getValue(), PlayerInfoKey.category));
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?>)) {
				return;
			}
			WikiPanel.onScreenOpened();
			ScreenEvents.remove(screen).register(s -> {
				FIELD.setFocused(false);
				recentExpanded = false;
				if (movingBar) {
					FemboyClientConfig.get().save();
				}
				pressedBar = movingBar = false;
				WikiPanel.onScreenClosed();
				RecipePlanner.onScreenClosed();
			});
			ScreenEvents.afterExtract(screen).register(WikiBar::render);
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !click(s, event));
			ScreenMouseEvents.allowMouseDrag(screen).register((s, event, dx, dy) -> !(usable()
					&& (dragBar(event.x(), event.y()) || WikiPanel.drag(event.x(), event.y()) || RecipePlanner.drag(event.x(), event.y()))));
			ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> !(usable()
					&& (releaseBar() | WikiPanel.release() | RecipePlanner.release())));
			ScreenMouseEvents.allowMouseScroll(screen).register((s, x, y, sx, sy) -> !(usable() && WikiPanel.scroll(s, x, y, sy)));
			ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> !keyPress(s, event));
			ScreenKeyboardEvents.allowCharType(screen).register((s, event) -> !charType(event));
		});
	}

	static boolean usable() {
		FemboyClientConfig config = FemboyClientConfig.get();
		return config.enabled && config.wikiBar;
	}

	// ------------------------------------------------------------ layout

	/**
	 * Centered under the container's window (or where it was dragged to); pulled up inside the screen when the window reaches the
	 * bottom. It grows wider, on both sides, when the text and the answer need more room (a big calculation), up to the screen's
	 * width.
	 */
	private static void layout(Screen screen) {
		AbstractContainerScreenAccessor container = (AbstractContainerScreenAccessor) screen;
		int width = Math.max(BAR_MIN_WIDTH, Math.min(BAR_MAX_WIDTH, container.femboyclient$imageWidth()));
		width = Math.max(width, 10 + textWidth + 16 + reserve() + 10);
		width = Math.min(width, screen.width - 2 * MARGIN);
		FemboyClientConfig config = FemboyClientConfig.get();
		boolean moved = config.wikiBarX >= 0;
		int center = moved ? config.wikiBarX : screen.width / 2;
		int x0 = Math.max(MARGIN, Math.min(screen.width - MARGIN - width, center - width / 2));
		int y0 = moved ? config.wikiBarY : container.femboyclient$topPos() + container.femboyclient$imageHeight() + Theme.GAP;
		y0 = Math.max(MARGIN, Math.min(y0, screen.height - MARGIN - Theme.SEARCH_HEIGHT));
		FIELD.setBounds(x0, y0, x0 + width, y0 + Theme.SEARCH_HEIGHT);
	}

	/**
	 * The result list: right above the bar, or right under it when the bar was moved near the top of the screen; at least
	 * {@link #LIST_MIN_WIDTH} wide, centered on the bar. {x0, y0, x1, y1, entries shown}, or null when it is not shown.
	 */
	private static int[] listBounds() {
		if (!FIELD.focused() || suggestionsHidden || entries.isEmpty() || movingBar
				|| mode == Mode.CALCULATION || mode == Mode.MATH_ERROR) {
			return null;
		}
		int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
		int above = FIELD.y0() - 4 - MARGIN, below = screenHeight - FIELD.y1() - 4 - MARGIN;
		boolean up = above >= Math.min(listHeight(entries.size()), 100) || above >= below;
		int room = up ? above : below;
		int shown = entries.size();
		while (shown > 0 && listHeight(shown) > room) {
			shown--;
		}
		if (shown == 0 || !entries.get(0).selectable() && shown == 1) {
			return null;
		}
		int width = Math.max(LIST_MIN_WIDTH, FIELD.x1() - FIELD.x0());
		int x0 = Math.max(MARGIN, Math.min(screenWidth - MARGIN - width, (FIELD.x0() + FIELD.x1() - width) / 2));
		int h = listHeight(shown);
		int y0 = up ? FIELD.y0() - 4 - h : FIELD.y1() + 4;
		return new int[] {x0, y0, x0 + width, y0 + h, shown};
	}

	/** The list's height with its first {@code count} entries: padding, the rows, and the key hints at the bottom. */
	private static int listHeight(int count) {
		int h = 8 + FOOTER;
		for (int i = 0; i < count; i++) {
			h += entries.get(i).height();
		}
		return h;
	}

	/** The top y of entry {@code index} in a list drawn at {@code box}. */
	private static int entryTop(int[] box, int index) {
		int y = box[1] + 4;
		for (int i = 0; i < index; i++) {
			y += entries.get(i).height();
		}
		return y;
	}

	// ------------------------------------------------------------ drawing

	private static void render(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		if (!usable()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		UiDraw.setScale(mc.getWindow().getGuiScale());
		UiDraw.setFade(1f);
		layout(screen);
		Font font = mc.font;
		askWikiWhenDue();
		RecipeView.beginFrame();

		WikiPanel.render(screen, g, mouseX, mouseY);
		RecipePlanner.render(g, font, screen.width, screen.height, mouseX, mouseY);

		boolean copied = copied();
		int reserve = Math.min(reserve(), FIELD.x1() - FIELD.x0() - 60);
		FIELD.draw(g, font, mouseX, mouseY, reserve, mode == Mode.MATH_ERROR ? Theme.TEXT_ERROR : 0);

		int right = FIELD.x1() - 10, h = FIELD.y1() - FIELD.y0();
		if (copied) {
			float since = (System.nanoTime() - copiedAt) / 1_000_000f - Theme.TOAST_MS;
			UiDraw.setFade(1f - Ease.clamp01(since / Theme.TOAST_FADE_MS));
			UiDraw.text(g, font, copiedText, right - copiedWidth, UiFont.centeredY(UiFont.Face.STRONG, FIELD.y0(), h), Theme.ACCENT_LIGHT);
			UiDraw.setFade(1f);
		} else if (mode == Mode.CALCULATION) {
			UiDraw.text(g, font, shortText, right - shortWidth, UiFont.centeredY(UiFont.Face.BODY, FIELD.y0(), h), Theme.TEXT_DIM);
			UiDraw.text(g, font, answerText, right - shortWidth - 6 - answerWidth, UiFont.centeredY(UiFont.Face.STRONG, FIELD.y0(), h),
					Theme.ACCENT_LIGHT);
		} else if (mode == Mode.MATH_ERROR) {
			UiDraw.text(g, font, errorText, right - errorWidth, UiFont.centeredY(UiFont.Face.BODY, FIELD.y0(), h), Theme.TEXT_ERROR);
		}
		drawList(g, font, mouseX, mouseY);
		RecipeView.drawTooltip(g, font, screen.width, screen.height);
	}

	private static boolean copied() {
		return System.nanoTime() - copiedAt < (Theme.TOAST_MS + Theme.TOAST_FADE_MS) * 1_000_000L;
	}

	/** How much of the bar's right end the answer (or "Copied!", or the error) takes. */
	private static int reserve() {
		if (copied()) {
			return copiedWidth + 12;
		}
		return switch (mode) {
			case CALCULATION -> answerWidth + 6 + shortWidth + 12;
			case MATH_ERROR -> errorWidth + 12;
			default -> 0;
		};
	}

	/**
	 * The result list: ITEMS and RECENT as small ALL-CAPS group titles; each row with the item's picture, its name and a dot in
	 * its rarity color; recent rows with ↺ in front (and = for a calculation). The selected row is tinted pink with the accent
	 * bar at its left, like the menu's module list. The keys at the bottom.
	 */
	private static void drawList(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		int[] box = listBounds();
		if (box == null) {
			return;
		}
		UiDraw.shadow(g, box[0], box[1], box[2], box[3], Theme.CARD_RADIUS, 12);
		UiDraw.roundRect(g, box[0], box[1], box[2], box[3], Theme.CARD_RADIUS, Colors.withAlpha(Theme.BACKDROP, 210));
		UiDraw.glass(g, box[0], box[1], box[2], box[3], Theme.CARD_RADIUS);
		if (recentGlyph == null) {
			recentGlyph = UiFont.sequence("↺", UiFont.Face.STRONG);
			equalsGlyph = UiFont.sequence("=", UiFont.Face.STRONG);
			footerText = UiFont.sequence("↑↓ choose · ↵ open · esc close", UiFont.Face.BODY);
		}
		int y = box[1] + 4, rowX0 = box[0] + 4, rowX1 = box[2] - 4;
		for (int i = 0; i < box[4]; i++) {
			Entry entry = entries.get(i);
			if (entry.kind() == Kind.MORE) {
				boolean hover = UiDraw.inside(mouseX, mouseY, rowX0, y, rowX1, y + ROW);
				if (i == selected || hover) {
					UiDraw.roundRect(g, rowX0, y, rowX1, y + ROW, 6, i == selected ? Theme.ACCENT_SELECTED : Theme.ROW_HOVER);
				}
				UiDraw.text(g, font, entry.shown(), rowX0 + 8, UiFont.centeredY(UiFont.Face.BODY, y, ROW),
						i == selected || hover ? Theme.ACCENT_LIGHT : Theme.TEXT_LABEL);
				y += ROW;
				continue;
			}
			if (entry.kind() == Kind.GROUP) {
				UiDraw.label(g, font, label(font, entry.text()), rowX0 + 6, UiFont.topY(UiFont.Face.LABEL, y + GROUP - UiFont.capHeight(UiFont.Face.LABEL) - 3),
						Theme.TEXT_LABEL);
				y += GROUP;
				continue;
			}
			boolean hover = UiDraw.inside(mouseX, mouseY, rowX0, y, rowX1, y + ROW);
			if (i == selected) {
				UiDraw.roundRect(g, rowX0, y, rowX1, y + ROW, 6, Theme.ACCENT_SELECTED);
				UiDraw.pill(g, rowX0 + 1, y + 4, rowX0 + 1 + Theme.ACCENT_BAR_WIDTH, y + ROW - 4, Theme.ACCENT);
			} else if (hover) {
				UiDraw.roundRect(g, rowX0, y, rowX1, y + ROW, 6, Theme.ROW_HOVER);
			}
			boolean lit = i == selected || hover;
			int x = rowX0 + 8, mid = UiFont.centeredY(UiFont.Face.BODY, y, ROW);
			if (entry.kind() != Kind.ITEM) {
				UiDraw.text(g, font, recentGlyph, x, UiFont.centeredY(UiFont.Face.STRONG, y, ROW), Theme.TEXT_LABEL);
				x += font.width(recentGlyph) + 5;
			}
			if (entry.kind() == Kind.RECENT_CALCULATION) {
				UiDraw.text(g, font, equalsGlyph, x, UiFont.centeredY(UiFont.Face.STRONG, y, ROW), Theme.ACCENT_LIGHT);
				x += font.width(equalsGlyph) + 5;
			} else {
				ItemIcons.draw(g, entry.text(), x, y + (ROW - ICON) / 2, ICON);
				x += ICON + 6;
			}
			UiDraw.text(g, font, entry.shown(), x, mid, entry.kind() == Kind.ITEM || lit ? Theme.TEXT : Theme.TEXT_DIM);
			if (entry.kind() != Kind.RECENT_CALCULATION) {
				int dot = rarityColor(entry.text());
				if (dot != 0) {
					int cx = rowX1 - 9, cy = y + ROW / 2;
					UiDraw.roundRect(g, cx - 3, cy - 3, cx + 3, cy + 3, 3, dot);
				}
			}
			y += ROW;
		}
		UiDraw.text(g, font, footerText, (box[0] + box[2] - font.width(footerText)) / 2, UiFont.centeredY(UiFont.Face.BODY, box[3] - 4 - FOOTER, FOOTER),
				Theme.TEXT_LABEL);
	}

	/** The rarity color of an item from Hypixel's list, or 0 when it is not known. */
	private static int rarityColor(String name) {
		ItemNames.Info info = WikiPanel.ITEM_NAMES.infoFor(name);
		int index = info == null ? -1 : WikiLayout.rarityIndex(info.tier());
		return index < 0 ? 0 : Theme.RARITY_FILL[index];
	}

	private static UiFont.Label label(Font font, String text) {
		return LABELS.computeIfAbsent(text, t -> UiFont.Label.of(font, t));
	}

	// ------------------------------------------------------------ the text

	/** Works out what the bar now holds. Runs when the text changes, not every frame. */
	private static void onTextChanged() {
		String text = FIELD.model.text();
		Font font = Minecraft.getInstance().font;
		textWidth = Math.min(UiFont.width(font, text, UiFont.Face.BODY), 400);
		suggestionsHidden = false;
		if (text.isBlank()) {
			mode = Mode.EMPTY;
			setSuggestions(List.of());
			return;
		}
		Calculator.Result result = CALCULATOR.evaluate(text, ans);
		if (result.ok()) {
			mode = Mode.CALCULATION;
			answer = result.value();
			String shown = "= " + Calculator.format(answer);
			String shorthand = Calculator.shorthand(answer);
			answerText = UiFont.sequence(shown, UiFont.Face.STRONG);
			answerWidth = font.width(answerText);
			boolean same = shorthand.equals(Calculator.format(answer));
			shortText = UiFont.sequence(same ? "" : shorthand, UiFont.Face.BODY);
			shortWidth = same ? -6 : font.width(shortText);
			setSuggestions(List.of());
		} else if (Calculator.looksLikeMath(text)) {
			mode = Mode.MATH_ERROR;
			errorText = UiFont.sequence(result.error(), UiFont.Face.BODY);
			errorWidth = font.width(errorText);
			setSuggestions(List.of());
		} else {
			mode = Mode.SEARCH;
			String query = text.strip();
			WikiPanel.loadItemNames();
			setSuggestions(merge(query, WikiPanel.ITEM_NAMES.match(query, SUGGESTIONS), List.of()));      // item names at once
			searchPending = query;
			searchDueAt = System.nanoTime() + Theme.SEARCH_DEBOUNCE_MS * 1_000_000L;
		}
	}

	/** Asks the wiki for suggestions once the typing has paused for {@link Theme#SEARCH_DEBOUNCE_MS}. */
	private static void askWikiWhenDue() {
		if (searchPending == null || System.nanoTime() < searchDueAt) {
			return;
		}
		String query = searchPending;
		searchPending = null;
		if (mode != Mode.SEARCH) {
			return;
		}
		int mine = ++searchRequest;
		Minecraft mc = Minecraft.getInstance();
		WikiPanel.client().search(query, SUGGESTIONS).whenComplete((titles, failure) -> mc.execute(() -> {
			if (mine == searchRequest && mode == Mode.SEARCH && titles != null) {
				setSuggestions(merge(query, WikiPanel.ITEM_NAMES.match(query, ITEM_SUGGESTIONS), titles));
				if (!suggestions.isEmpty()) {
					WikiPanel.prefetch(suggestions.get(0));       // the likely pick: ready before Enter
				}
			}
		}));
	}

	/**
	 * Item names first, then the wiki titles that really match what was typed, each once (ignoring case), at most
	 * {@link #SUGGESTIONS}. The wiki's own search pads its answer with pages that only mention the words somewhere ("plasma"
	 * gives Anguish Ring), so a title is kept only when it matches like an item name would.
	 */
	static List<String> merge(String query, List<String> items, List<String> titles) {
		String q = query.strip().toLowerCase(Locale.ROOT);
		Map<String, String> unique = new LinkedHashMap<>();
		for (String name : items) {
			unique.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
		}
		for (String title : titles) {
			if (ItemNames.score(title.toLowerCase(Locale.ROOT), q) >= 0) {
				unique.putIfAbsent(title.toLowerCase(Locale.ROOT), title);
			}
		}
		return unique.values().stream().limit(SUGGESTIONS).toList();
	}

	private static void setSuggestions(List<String> list) {
		suggestions = list;
		rebuildEntries();
	}

	/**
	 * The list's rows: while searching, ITEMS (the suggestions) and RECENT (recent searches that match, at most three); while the
	 * bar is empty, RECENT alone. Keeps the selected row selected when it is still there.
	 */
	private static void rebuildEntries() {
		Entry keep = selected >= 0 && selected < entries.size() ? entries.get(selected) : null;
		entries.clear();
		Font font = Minecraft.getInstance().font;
		int room = Math.max(LIST_MIN_WIDTH, FIELD.x1() - FIELD.x0()) - 8 - 8 - ICON - 6 - 20;
		List<String> pictures = new ArrayList<>(suggestions);
		if (mode == Mode.SEARCH && !suggestions.isEmpty()) {
			entries.add(new Entry(Kind.GROUP, "Items", null, null));
			for (String name : suggestions) {
				entries.add(new Entry(Kind.ITEM, name, null, UiFont.sequence(UiFont.fit(font, name, UiFont.Face.BODY, room), UiFont.Face.BODY)));
			}
		}
		if (mode == Mode.SEARCH || mode == Mode.EMPTY) {
			String q = mode == Mode.SEARCH ? FIELD.model.text().strip().toLowerCase(Locale.ROOT) : "";
			List<Entry> recent = new ArrayList<>();
			int limit = recentExpanded ? RecentSearches.MAX : RECENT_SHOWN, hidden = 0;
			for (RecentSearches.Entry past : RECENT.entries()) {
				if (!past.text().toLowerCase(Locale.ROOT).contains(q)) {
					continue;
				}
				if (recent.size() >= limit) {
					hidden++;
					continue;
				}
				if (past.isCalculation()) {
					String text = past.text() + " = " + past.answer();
					recent.add(new Entry(Kind.RECENT_CALCULATION, past.text(), past.answer(),
							UiFont.sequence(UiFont.fit(font, text, UiFont.Face.BODY, room), UiFont.Face.BODY)));
				} else if (suggestions.stream().noneMatch(s -> s.equalsIgnoreCase(past.text()))) {
					recent.add(new Entry(Kind.RECENT_PAGE, past.text(), null,
							UiFont.sequence(UiFont.fit(font, past.text(), UiFont.Face.BODY, room), UiFont.Face.BODY)));
					pictures.add(past.text());
				}
			}
			if (!recent.isEmpty()) {
				entries.add(new Entry(Kind.GROUP, "Recent", null, null));
				entries.addAll(recent);
				if (hidden > 0) {
					entries.add(new Entry(Kind.MORE, "more", null, UiFont.sequence("Show " + hidden + " more ▾", UiFont.Face.BODY)));
				}
			}
		}
		ItemIcons.want(pictures);
		selected = -1;
		for (int i = 0; keep != null && i < entries.size(); i++) {
			if (entries.get(i).kind() == keep.kind() && entries.get(i).text().equals(keep.text())) {
				selected = i;
			}
		}
	}

	/**
	 * Enter: on a selected row, opens it (a recent calculation goes back into the bar); else a calculation copies its answer and
	 * a search opens the top result (or the typed text) in the sheet.
	 */
	private static void enter() {
		if (selected >= 0 && selected < entries.size() && entries.get(selected).selectable() && listBounds() != null) {
			choose(entries.get(selected));
			return;
		}
		if (mode == Mode.SEARCH) {
			String text = FIELD.model.text().strip();
			WikiPanel.open(!suggestions.isEmpty() ? suggestions.get(0) : text);
			suggestionsHidden = true;
			selected = -1;
			return;
		}
		if (mode != Mode.CALCULATION) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		mc.keyboardHandler.setClipboard(Calculator.plain(answer));          // plain digits paste into a Bazaar or Auction sign
		ans = answer;
		String text = FIELD.model.text().strip();
		history.remove(text);
		history.add(0, text);
		if (history.size() > HISTORY_SIZE) {
			history.remove(history.size() - 1);
		}
		historyAt = -1;
		RECENT.addCalculation(text, Calculator.shorthand(answer));
		copiedAt = System.nanoTime();
		copiedText = UiFont.sequence("Copied!", UiFont.Face.STRONG);
		copiedWidth = mc.font.width(copiedText);
	}

	private static void choose(Entry entry) {
		if (entry.kind() == Kind.MORE) {
			recentExpanded = true;
			rebuildEntries();
			return;
		}
		if (entry.kind() == Kind.RECENT_CALCULATION) {
			FIELD.model.set(entry.text());
			onTextChanged();
			return;
		}
		WikiPanel.open(entry.text());
		suggestionsHidden = true;
		selected = -1;
	}

	/** Up (older) or Down (newer) through the calculations copied with Enter. Down past the newest brings back the draft. */
	private static void browseHistory(int step) {
		if (history.isEmpty()) {
			return;
		}
		if (historyAt == -1) {
			if (step < 0) {
				return;
			}
			draft = FIELD.model.text();
		}
		historyAt = Math.max(-1, Math.min(history.size() - 1, historyAt + step));
		FIELD.model.set(historyAt == -1 ? draft : history.get(historyAt));
		onTextChanged();
	}

	/**
	 * Up and Down through the list's rows, skipping the group titles. Up from the bar goes to the row next to the bar; past the
	 * last row goes back to the bar.
	 */
	private static void moveSelection(boolean up, int shown) {
		boolean listAbove = listBounds() != null && listBounds()[1] < FIELD.y0();
		int step = up ? -1 : 1;
		if (selected == -1) {
			if (up != listAbove) {
				return;                                     // away from the list: nothing to go to
			}
			int i = up ? shown - 1 : 0;
			while (i >= 0 && i < shown && !entries.get(i).selectable()) {
				i += step;
			}
			selected = i >= 0 && i < shown ? i : -1;
			return;
		}
		int i = selected + step;
		while (i >= 0 && i < shown && !entries.get(i).selectable()) {
			i += step;
		}
		selected = i >= 0 && i < shown ? i : (up == listAbove ? selected : -1);
	}

	private static void focus() {
		FIELD.setFocused(true);
		WikiPanel.loadItemNames();
		if (mode == Mode.EMPTY) {
			rebuildEntries();                               // the recent searches
		}
	}

	// ------------------------------------------------------------ input

	/**
	 * A click on a row opens it; on a planner or the sheet goes to them; on the bar focuses it (all swallowed). A click elsewhere
	 * unfocuses the bar and goes on to the container as usual.
	 */
	private static boolean click(Screen screen, MouseButtonEvent event) {
		if (!usable()) {
			return false;
		}
		layout(screen);
		int[] box = listBounds();
		if (box != null && UiDraw.inside(event.x(), event.y(), box[0], box[1], box[2], box[3])) {
			for (int i = 0; i < box[4]; i++) {
				int top = entryTop(box, i);
				Entry entry = entries.get(i);
				if (event.button() == 0 && entry.selectable() && event.y() >= top && event.y() < top + entry.height()) {
					choose(entry);
				}
			}
			return true;
		}
		if (RecipePlanner.click(event.x(), event.y(), event.button())) {
			return true;                                // planner cards sit on top of the sheet
		}
		if (WikiPanel.click(screen, event.x(), event.y(), event.button())) {
			return true;
		}
		if (event.button() == 0 && event.hasControlDown() && FIELD.inside(event.x(), event.y())) {
			pressedBar = true;                          // Ctrl+drag moves the bar
			pressX = event.x();
			pressY = event.y();
			moveDX = (int) event.x() - (FIELD.x0() + FIELD.x1()) / 2;
			moveDY = (int) event.y() - FIELD.y0();
			return true;
		}
		if (event.button() == 0 && FIELD.click(Minecraft.getInstance().font, event.x(), event.y(), event.hasShiftDown())) {
			focus();
			return true;
		}
		if (event.button() == 1 && FIELD.inside(event.x(), event.y())) {
			if (event.hasControlDown()) {
				FemboyClientConfig config = FemboyClientConfig.get();
				if (config.wikiBarX >= 0) {
					config.wikiBarX = config.wikiBarY = -1;     // back under the inventory
					config.save();
				}
			} else if (!FIELD.model.isEmpty()) {
				FIELD.model.set("");                    // a right click clears the bar
				historyAt = -1;
				onTextChanged();
			}
			return true;
		}
		if (FIELD.inside(event.x(), event.y())) {
			return true;                                // other buttons on the bar do nothing, and never reach a slot under it
		}
		FIELD.setFocused(false);
		recentExpanded = false;
		return false;
	}

	/** Moves the bar once a press on it has turned into a drag. Returns true while the drag is the bar's. */
	private static boolean dragBar(double x, double y) {
		if (!pressedBar) {
			return false;
		}
		if (!movingBar && Math.hypot(x - pressX, y - pressY) >= DRAG_START) {
			movingBar = true;
		}
		if (movingBar) {
			FemboyClientConfig config = FemboyClientConfig.get();
			config.wikiBarX = Math.max(0, (int) x - moveDX);
			config.wikiBarY = Math.max(0, (int) y - moveDY);
		}
		return true;
	}

	private static boolean releaseBar() {
		if (!pressedBar) {
			return false;
		}
		pressedBar = false;
		if (movingBar) {
			movingBar = false;
			FemboyClientConfig.get().save();
		}
		return true;
	}

	/** True to swallow the key. While focused every key is swallowed; Esc unfocuses. Ctrl+F focuses. */
	private static boolean keyPress(Screen screen, KeyEvent event) {
		if (!usable()) {
			return false;
		}
		if (event.isEscape() && WikiPanel.escape()) {
			return true;                                // the browser prompt goes first
		}
		if (!FIELD.focused()) {
			if (wikiKey != null && !wikiKey.isUnbound() && wikiKey.matches(event)) {
				return openHoveredItem(screen);
			}
			if (event.key() == InputConstants.KEY_F && event.hasControlDown()) {
				focus();
				FIELD.model.selectAll();
				return true;
			}
			return false;
		}
		if (event.isEscape()) {
			FIELD.setFocused(false);                    // the first Esc leaves the bar; the next one closes the screen
			return true;
		}
		int key = event.key();
		if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
			enter();
			return true;
		}
		if (key == InputConstants.KEY_UP || key == InputConstants.KEY_DOWN) {
			int[] box = listBounds();
			if (box != null) {
				moveSelection(key == InputConstants.KEY_UP, box[4]);
			} else if (mode != Mode.SEARCH) {
				browseHistory(key == InputConstants.KEY_UP ? 1 : -1);
			}
			return true;
		}
		if (FIELD.key(event) == 1) {
			historyAt = -1;
			onTextChanged();
		}
		return true;                                    // while you type, no other key does anything
	}

	/** The item key: opens the wiki page of the item under the mouse. Returns false (the key acts as usual) over no item. */
	private static boolean openHoveredItem(Screen screen) {
		Slot slot = ((AbstractContainerScreenAccessor) screen).femboyclient$hoveredSlot();
		if (slot == null || !slot.hasItem()) {
			return false;
		}
		WikiPanel.loadItemNames();
		WikiPanel.open(pageFor(slot.getItem()));
		return true;
	}

	/**
	 * The wiki page for an item: from its SkyBlock id when Hypixel's item list knows it (reforges and stars never get in the way),
	 * else its display name without color codes, reforge and stars.
	 */
	static String pageFor(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		String id = data == null ? null : data.copyTag().getString("id").orElse(null);
		String byId = id == null || id.equals("PET") ? null : WikiPanel.ITEM_NAMES.nameForId(id);
		return byId != null ? byId : WikiPanel.ITEM_NAMES.baseName(stack.getHoverName().getString());
	}

	private static boolean charType(CharacterEvent event) {
		if (!usable() || !FIELD.focused()) {
			return false;
		}
		if (FIELD.type(event.codepoint())) {
			historyAt = -1;
			onTextChanged();
		}
		return true;
	}
}
