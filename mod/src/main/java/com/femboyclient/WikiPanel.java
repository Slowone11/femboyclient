package com.femboyclient;

import com.femboyclient.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * The item sheet: the wiki page of the item opened from the Wiki Bar, in the menu's frosted glass. Its header glows in the
 * item's rarity color and shows the item's picture, name and rarity badge, with open in browser (asks first) and close at the
 * top right (and back, once there is somewhere to go back to). Under it the page is cut into sections that fold open and closed
 * (Stats as small chips, then the infobox's other groups, About, the page's own sections, Used in); which ones are open is
 * remembered from page to page. Under Stats, "Pop out recipe" opens the item's recipe in a {@link RecipePlanner}.
 *
 * By default the sheet is docked between the container's window and the right edge of the screen (at most
 * {@link Theme#WIKI_PANEL_MAX_WIDTH}), its top in line with the window's top; narrower than {@link Theme#WIKI_PANEL_MIN_WIDTH}
 * it goes compact, below {@link Theme#WIKI_PANEL_COMPACT_MIN_WIDTH} it hides. Dragging the header floats it wherever it is
 * dropped (the spot is saved); a double click on the header docks it again. The page scrolls with the wheel or its scroll bar,
 * and links open in the sheet. While a page loads it shows a pulsing dot; a missing page or a network failure shows a message
 * (with Retry). The page stays open between screens until the close button is pressed.
 *
 * Every request runs in the background ({@link WikiClient}); answers come back to the game thread with {@code mc.execute}.
 */
final class WikiPanel {

	private static final int MARGIN = 8;                 // between the panel and the window, and the screen's edge
	private static final int FLOATING_HEIGHT = 300;
	private static final int HERO = Theme.WIKI_HEADER_HEIGHT;
	private static final long DOUBLE_CLICK_NS = 350_000_000L;
	private static final int GLOW_STEPS = 12;

	enum State { CLOSED, LOADING, PAGE, NOT_FOUND, ERROR }

	private static State state = State.CLOSED;
	private static String title;                         // the page asked for (or shown)
	private static WikiDoc doc;
	private static WikiDoc.Recipe recipe;                // the recipe that makes this page's item, for the pop out button
	private static String error;
	private static int request;                          // only the newest request's answer is used
	private static final Deque<String> back = new ArrayDeque<>(), forward = new ArrayDeque<>();
	private static long shownAt;
	private static boolean confirming;                   // the "open in browser?" prompt
	/** The sections kept open, by lower-case name; remembered from page to page. */
	private static final Set<String> OPEN = new HashSet<>(Set.of("stats"));

	// Layout, recomputed every frame (it is only a few numbers) and the page's layout only when something changes.
	private static int x0, y0, x1, y1, contentX0, contentY0, contentX1, contentY1;
	private static boolean visible, compact;
	private static WikiLayout layout;
	private static WikiDoc laidOut;
	private static int laidOutWidth = -1, laidOutOpen = -1;
	private static float scroll, scrollGoal;
	private static long lastFrame;
	private static boolean draggingBar;
	private static String hoveredLink;

	// Moving the panel by its header.
	private static boolean movingPanel;
	private static int moveDX, moveDY;
	private static long lastHeaderPress;

	// What can be clicked on the page this frame.
	private record Toggle(int y0, int y1, String key) {
	}

	private static final List<Toggle> toggles = new ArrayList<>();
	private static int[] popOutBounds;

	// Texts made once.
	private static FormattedCharSequence loadingText, heroTitle;
	private static final Map<String, UiFont.Label> LABELS = new HashMap<>();
	private static final Map<String, FormattedCharSequence> GLYPHS = new HashMap<>();
	private static String heroTitleFor;
	private static int heroTitleWidth = -1;
	private static String messageFor;
	private static List<FormattedCharSequence> messageLines;
	private static FormattedCharSequence confirmTitle, confirmUrl;
	private static String confirmUrlFor;

	private static WikiClient client;
	private static ExecutorService parser;
	static final ItemNames ITEM_NAMES = new ItemNames();
	private static boolean itemsRequested;

	private WikiPanel() {
	}

	// ------------------------------------------------------------ the wiki

	static synchronized WikiClient client() {
		if (client == null) {
			String version = FabricLoader.getInstance().getModContainer(FemboyClientMod.MOD_ID)
					.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
			Path cache = FabricLoader.getInstance().getGameDir().resolve("femboyclient").resolve("wiki");
			client = new WikiClient(WikiClient.FANDOM, "FemboyClient/" + version + " (Minecraft mod; SkyBlock wiki lookups from the inventory)",
					cache, Duration.ofHours(24), Daemons.worker("wiki"),
					Daemons.pool("wiki-images", 4), System::currentTimeMillis);
			parser = Daemons.worker("wiki-parse");
		}
		return client;
	}

	/** Pages already read (or being read), the most recent ones, so opening one again, or one fetched ahead, is instant. */
	private static final Map<String, CompletableFuture<WikiDoc>> DOCS = new java.util.LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, CompletableFuture<WikiDoc>> eldest) {
			return size() > 24;
		}
	};

	/** A page, fetched and read in the background; the same future for the same page while it is remembered. Any thread. */
	static CompletableFuture<WikiDoc> fetchDoc(String page) {
		String key = page.strip().toLowerCase(java.util.Locale.ROOT);
		synchronized (DOCS) {
			CompletableFuture<WikiDoc> known = DOCS.get(key);
			if (known != null && !known.isCompletedExceptionally()) {
				return known;
			}
			WikiClient wiki = client();
			CompletableFuture<WikiDoc> future = wiki.page(page).thenApplyAsync(answer -> WikiHtml.parse(answer.title(), answer.html()), parser);
			DOCS.put(key, future);
			return future;
		}
	}

	/** Starts fetching and reading a page before it is asked for (the top search result), so Enter opens it at once. */
	static void prefetch(String page) {
		if (page != null && !page.isBlank()) {
			Minecraft mc = Minecraft.getInstance();
			fetchDoc(page).thenAccept(doc -> mc.execute(() -> WikiImages.request(doc.imageUrl())));
		}
	}

	/** Loads Hypixel's item list once: names for autocomplete, ids, rarities and materials. Kept on disk for a day. */
	static void loadItemNames() {
		if (itemsRequested) {
			return;
		}
		itemsRequested = true;
		Minecraft mc = Minecraft.getInstance();
		client().text(ItemNames.ITEMS_URL, "items.json", Duration.ofDays(1))
				.whenComplete((json, failure) -> {
					List<String> names = json == null ? List.of() : ItemNames.parse(json);
					if (!names.isEmpty()) {
						ITEM_NAMES.set(names);
						ITEM_NAMES.setIds(ItemNames.parseIds(json));
						ITEM_NAMES.setInfo(ItemNames.parseInfo(json));
						mc.execute(ItemIcons::reset);
					} else {
						mc.execute(() -> itemsRequested = false);      // try again on the next focus
					}
				});
	}

	// ------------------------------------------------------------ navigation

	static boolean isOpen() {
		return state != State.CLOSED;
	}

	/** Opens a page, keeping the one shown now in the back list. */
	static void open(String page) {
		if (page == null || page.isBlank()) {
			return;
		}
		if (state == State.PAGE && doc != null && !doc.title().equalsIgnoreCase(page)) {
			back.push(doc.title());
			forward.clear();
		}
		WikiBar.RECENT.addPage(page.strip());
		load(page.strip());
	}

	private static void goBack() {
		if (!back.isEmpty()) {
			if (doc != null) {
				forward.push(doc.title());
			}
			load(back.pop());
		}
	}

	static void close() {
		state = State.CLOSED;
		doc = null;
		back.clear();
		forward.clear();
		confirming = false;
		request++;
		WikiImages.releaseTextures();
	}

	private static void load(String page) {
		if (state == State.CLOSED) {
			shownAt = System.nanoTime();
		}
		title = page;
		state = State.LOADING;
		confirming = false;
		int mine = ++request;
		Minecraft mc = Minecraft.getInstance();
		fetchDoc(page).whenComplete((parsed, failure) -> mc.execute(() -> {
			if (mine != request) {
				return;                                 // a newer page was asked for meanwhile
			}
			if (parsed != null) {
				show(parsed);
			} else {
				Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
				state = cause instanceof WikiClient.NotFound ? State.NOT_FOUND : State.ERROR;
				error = cause == null || cause.getMessage() == null || cause.getMessage().isBlank() ? "no connection" : cause.getMessage();
			}
		}));
	}

	private static void show(WikiDoc parsed) {
		doc = parsed;
		title = parsed.title();
		recipe = RawMaterials.craftingRecipeFor(parsed, parsed.title());
		state = State.PAGE;
		scroll = scrollGoal = 0;
		laidOut = null;
		WikiImages.request(parsed.imageUrl());
		ItemIcons.want(List.of(parsed.title()));
	}

	// ------------------------------------------------------------ layout

	/** Where the panel goes on this screen: docked beside the window, or floating where it was dropped. */
	private static void place(Screen screen) {
		FemboyClientConfig config = FemboyClientConfig.get();
		if (config.wikiPanelX >= 0 || movingPanel) {
			int w = Math.min(Theme.WIKI_PANEL_MAX_WIDTH, screen.width - 2 * MARGIN);
			int h = Math.min(FLOATING_HEIGHT, screen.height - 2 * MARGIN);
			x0 = Math.max(0, Math.min(screen.width - w, config.wikiPanelX));
			y0 = Math.max(0, Math.min(screen.height - h, config.wikiPanelY));
			x1 = x0 + w;
			y1 = y0 + h;
		} else {
			AbstractContainerScreenAccessor container = (AbstractContainerScreenAccessor) screen;
			int left = container.femboyclient$leftPos() + container.femboyclient$imageWidth() + MARGIN;
			int room = screen.width - MARGIN - left;
			x0 = left;
			x1 = left + Math.max(0, Math.min(room, Theme.WIKI_PANEL_MAX_WIDTH));
			y0 = Math.max(MARGIN, container.femboyclient$topPos());
			y1 = Math.max(y0 + HERO + 60, screen.height - MARGIN);
		}
		visible = state != State.CLOSED && x1 - x0 >= Theme.WIKI_PANEL_COMPACT_MIN_WIDTH;
		compact = x1 - x0 < Theme.WIKI_PANEL_MIN_WIDTH;
		int pad = compact ? 6 : Theme.CARD_PADDING;
		contentX0 = x0 + pad;
		contentX1 = x1 - pad - Theme.SCROLLBAR_WIDTH - 3;
		contentY0 = y0 + HERO + 4;
		contentY1 = y1 - pad;
	}

	static boolean inside(double x, double y) {
		return visible && UiDraw.inside(x, y, x0, y0, x1, y1);
	}

	// ------------------------------------------------------------ drawing

	static void render(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY) {
		place(screen);
		toggles.clear();
		popOutBounds = null;
		if (!visible) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		UiDraw.setFade(Ease.inOutCubic((now - shownAt) / 1_000_000f / Theme.OPEN_MS));

		if (FemboyClientConfig.get().wikiPanelX >= 0 || movingPanel) {
			UiDraw.shadow(g, x0, y0, x1, y1, Theme.PANEL_RADIUS, 14);              // a window lifted off the screen
		}
		UiDraw.roundRect(g, x0, y0, x1, y1, Theme.PANEL_RADIUS, Colors.withAlpha(Theme.BACKDROP, 150));     // reads over a busy world
		UiDraw.glass(g, x0, y0, x1, y1, Theme.PANEL_RADIUS);
		drawHero(g, font, mouseX, mouseY);
		UiDraw.divider(g, x0 + 1, y0 + HERO, x1 - 1);

		switch (state) {
			case LOADING -> drawLoading(g, font, now);
			case PAGE -> drawPage(g, font, mouseX, mouseY, dt);
			case NOT_FOUND -> drawMessage(g, font, "No wiki page called \"" + title + "\".", Theme.TEXT_DIM, false, mouseX, mouseY);
			case ERROR -> drawMessage(g, font, "Can't reach the wiki (" + error + ").", Theme.TEXT_ERROR, true, mouseX, mouseY);
			default -> {
			}
		}
		if (confirming) {
			drawConfirm(g, font, mouseX, mouseY);
		}
		UiDraw.setFade(1f);
	}

	/** The rarity color of the page shown, or the accent when it has none. */
	private static int rarityColor() {
		int rarity = doc == null ? -1 : WikiLayout.rarityIndex(doc.rarity());
		return rarity >= 0 ? Theme.RARITY_FILL[rarity] : Theme.ACCENT;
	}

	/**
	 * The header: a glow in the rarity color, strongest at the left and fading to the right; the picture, the name in the big
	 * face and the rarity badge; ← (when there is history), ↗ and × at the top right.
	 */
	private static void drawHero(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		int glow = rarityColor();
		UiDraw.beginClip(g, x0, y0, x1, y0 + HERO);
		for (int i = 0; i < GLOW_STEPS; i++) {
			int right = x0 + (x1 - x0) * (GLOW_STEPS - i) / GLOW_STEPS;
			UiDraw.roundRect(g, x0, y0, right, y0 + HERO + Theme.PANEL_RADIUS, Theme.PANEL_RADIUS, Colors.withAlpha(glow, 6));
		}
		UiDraw.endClip(g);

		int pad = compact ? 8 : 12, size = compact ? 24 : Theme.WIKI_IMAGE_SIZE;
		int px = x0 + pad, py = y0 + (HERO - size) / 2;
		boolean picture = doc != null && (WikiImages.draw(g, doc.imageUrl(), px, py, size) || ItemIcons.draw(g, doc.title(), px, py, size));
		int textX = picture || doc != null ? px + size + 9 : px;

		// Buttons at the top right: ← (only with history), ↗, ×.
		boolean onButton = false;
		for (int i = 0; i < 3; i++) {
			if (i == 0 && back.isEmpty()) {
				continue;
			}
			int[] b = button(i);
			boolean hover = !confirming && UiDraw.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
			onButton |= hover;
			if (hover) {
				UiDraw.hoverCard(g, b[0], b[1], b[2], b[3], Theme.BADGE_RADIUS, true);
			}
			FormattedCharSequence icon = glyph(i == 0 ? "←" : i == 1 ? "↗" : "×");
			UiDraw.text(g, font, icon, b[0] + (b[2] - b[0] - font.width(icon) + 1) / 2, UiFont.centeredY(UiFont.Face.STRONG, b[1], b[3] - b[1]),
					hover ? Theme.ACCENT_LIGHT : Theme.TEXT_DIM);
		}

		int titleRoom = button(back.isEmpty() ? 1 : 0)[0] - 6 - textX;
		String name = title == null ? "" : title;
		UiFont.Face face = compact ? UiFont.Face.STRONG : UiFont.Face.VALUE;
		if (!name.equals(heroTitleFor) || heroTitleWidth != titleRoom) {
			heroTitleFor = name;
			heroTitleWidth = titleRoom;
			heroTitle = UiFont.sequence(UiFont.fit(font, name, face, Math.max(0, titleRoom)), face);
		}
		int rarity = doc == null ? -1 : WikiLayout.rarityIndex(doc.rarity());
		int titleTop = rarity >= 0 ? y0 + HERO / 2 - UiFont.capHeight(face) - 3 : y0 + (HERO - UiFont.capHeight(face)) / 2;
		UiDraw.text(g, font, heroTitle, textX, UiFont.topY(face, titleTop), Theme.TEXT);
		if (rarity >= 0) {
			UiFont.Label label = label(font, Theme.RARITIES[rarity]);
			int by = y0 + HERO / 2 + 3;
			UiDraw.roundRect(g, textX, by, textX + label.width() + 12, by + Theme.BADGE_HEIGHT - 1, Theme.BADGE_RADIUS, Theme.RARITY_FILL[rarity]);
			UiDraw.label(g, font, label, textX + 6, UiFont.centeredY(UiFont.Face.LABEL, by, Theme.BADGE_HEIGHT - 1), Theme.RARITY_TEXT[rarity]);
		}
	}

	/** Header buttons: 0 back, 1 open in browser, 2 close; at the top right, close rightmost. */
	private static int[] button(int index) {
		int size = Theme.WIKI_ICON_BUTTON, top = y0 + 7, pad = compact ? 5 : 8;
		int bx1 = x1 - pad - (2 - index) * (size + 2);
		return new int[] {bx1 - size, top, bx1, top + size};
	}

	private static void drawLoading(GuiGraphicsExtractor g, Font font, long now) {
		float pulse = 0.5f + 0.5f * (float) Math.sin(now / 1e9 * Math.PI * 2);
		int cy = contentY0 + 10;
		int dx = contentX0 + 4;
		UiDraw.glow(g, dx - 3, cy - 3, dx + 3, cy + 3, 3, Theme.ACCENT, 2 + Math.round(4 * pulse));
		UiDraw.roundRect(g, dx - 3, cy - 3, dx + 3, cy + 3, 3, Theme.ACCENT);
		if (loadingText == null) {
			loadingText = UiFont.sequence("Loading…", UiFont.Face.BODY);
		}
		UiDraw.text(g, font, loadingText, contentX0 + 14, UiFont.centeredY(UiFont.Face.BODY, cy - 6, 12), Theme.TEXT_DIM);
	}

	private static int[] retryBounds;

	private static void drawMessage(GuiGraphicsExtractor g, Font font, String message, int color, boolean retry, int mouseX, int mouseY) {
		int y = contentY0 + 6;
		String key = message + "|" + (contentX1 - contentX0);
		if (!key.equals(messageFor)) {
			messageFor = key;
			messageLines = font.split(UiFont.component(message, UiFont.Face.BODY), Math.max(20, contentX1 - contentX0));
		}
		for (FormattedCharSequence line : messageLines) {
			UiDraw.text(g, font, line, contentX0, UiFont.topY(UiFont.Face.BODY, y), color);
			y += Theme.BODY_LINE;
		}
		retryBounds = null;
		if (retry) {
			UiFont.Label label = label(font, "Retry");
			int bx0 = contentX0, by0 = y + 6, bx1 = bx0 + label.width() + 24, by1 = by0 + 20;
			boolean hover = !confirming && UiDraw.inside(mouseX, mouseY, bx0, by0, bx1, by1);
			UiDraw.hoverCard(g, bx0, by0, bx1, by1, Theme.ROW_RADIUS, hover);
			UiDraw.label(g, font, label, bx0 + 12, UiFont.centeredY(UiFont.Face.LABEL, by0, 20), hover ? Theme.ACCENT_LIGHT : Theme.TEXT_LABEL);
			retryBounds = new int[] {bx0, by0, bx1, by1};
		}
	}

	private static void drawPage(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, float dt) {
		int width = contentX1 - contentX0;
		if (laidOut != doc || laidOutWidth != width || laidOutOpen != OPEN.hashCode()) {
			layout = WikiLayout.build(font, doc, width, OPEN, recipe != null);
			laidOut = doc;
			laidOutWidth = width;
			laidOutOpen = OPEN.hashCode();
		}
		int view = contentY1 - contentY0;
		float max = Math.max(0, layout.height - view);
		scrollGoal = Math.max(0, Math.min(max, scrollGoal));
		scroll = Ease.approach(scroll, scrollGoal, Theme.SCROLL_EASE_PER_SECOND, dt);
		int offset = Math.round(scroll);

		boolean overContent = !confirming && !movingPanel && UiDraw.inside(mouseX, mouseY, contentX0, contentY0, contentX1, contentY1);
		hoveredLink = null;
		UiDraw.beginClip(g, x0 + 1, contentY0, x1 - 1, contentY1);
		for (WikiLayout.Op op : layout.ops) {
			int top = contentY0 + op.top() - offset;
			if (top > contentY1 || contentY0 + op.bottom() - offset < contentY0) {
				continue;
			}
			switch (op) {
				case WikiLayout.TextOp text -> drawLine(g, font, text, contentX0 + text.x(), top, overContent, mouseX, mouseY);
				case WikiLayout.LabelOp label -> UiDraw.label(g, font, label.label(), contentX0 + label.x(),
						UiFont.topY(UiFont.Face.LABEL, top), label.color());
				case WikiLayout.FillOp fill -> UiDraw.roundRect(g, contentX0 + fill.x0(), top, contentX0 + fill.x1(),
						contentY0 + fill.y1() - offset, fill.radius(), fill.color());
				case WikiLayout.DividerOp line -> UiDraw.divider(g, contentX0 + line.x0(), top, contentX0 + line.x1());
				case WikiLayout.SectionOp section -> drawSection(g, font, section, top, overContent, mouseX, mouseY);
				case WikiLayout.ChipOp chip -> {
					int cx = contentX0 + chip.x();
					UiDraw.card(g, cx, top, cx + chip.width(), top + WikiLayout.CHIP_HEIGHT, WikiLayout.CHIP_HEIGHT / 2, Theme.CARD_FILL,
							Theme.CARD_BORDER);
					UiDraw.text(g, font, chip.label(), cx + 7, UiFont.centeredY(UiFont.Face.BODY, top, WikiLayout.CHIP_HEIGHT), Theme.TEXT_DIM);
					UiDraw.text(g, font, chip.value(), cx + 7 + chip.labelWidth() + 4, UiFont.centeredY(UiFont.Face.STRONG, top,
							WikiLayout.CHIP_HEIGHT), Theme.TEXT);
				}
				case WikiLayout.ButtonOp button -> drawPopOut(g, font, button, contentX0 + button.x(), top, overContent, mouseX, mouseY);
			}
		}
		UiDraw.endClip(g);

		if (layout.height > view) {
			int[] bar = scrollbarBounds();
			int thumb = Math.max(16, view * view / layout.height);
			int thumbTop = contentY0 + Math.round((view - thumb) * (scroll / Math.max(1f, max)));
			boolean hot = draggingBar || !confirming && UiDraw.inside(mouseX, mouseY, bar[0] - 3, bar[1], bar[2] + 3, bar[3]);
			UiDraw.scrollbar(g, bar[0], bar[1], bar[2], bar[3], thumbTop, thumbTop + thumb, hot);
		}
	}

	/** A section header row: a hairline above (but the first), the name, and ▾ or ▸ at the right. */
	private static void drawSection(GuiGraphicsExtractor g, Font font, WikiLayout.SectionOp section, int top, boolean overContent,
			int mouseX, int mouseY) {
		boolean hover = overContent && UiDraw.inside(mouseX, mouseY, contentX0, top, contentX1, top + WikiLayout.SECTION_HEIGHT);
		if (section.divider()) {
			UiDraw.divider(g, contentX0, top, contentX1);
		}
		UiDraw.text(g, font, section.name(), contentX0, UiFont.centeredY(UiFont.Face.BODY, top, WikiLayout.SECTION_HEIGHT),
				hover ? Theme.ACCENT_LIGHT : Theme.TEXT);
		FormattedCharSequence arrow = glyph(section.open() ? "▾" : "▸");
		UiDraw.text(g, font, arrow, contentX1 - font.width(arrow) - 2, UiFont.centeredY(UiFont.Face.STRONG, top, WikiLayout.SECTION_HEIGHT),
				hover ? Theme.ACCENT_LIGHT : Theme.TEXT_LABEL);
		toggles.add(new Toggle(Math.max(top, contentY0), Math.min(top + WikiLayout.SECTION_HEIGHT, contentY1), section.key()));
	}

	/** "Pop out recipe": a pink-tinted button with an accent border, two overlapping squares for its icon. */
	private static void drawPopOut(GuiGraphicsExtractor g, Font font, WikiLayout.ButtonOp button, int x, int top, boolean overContent,
			int mouseX, int mouseY) {
		int x1b = x + button.width(), y1b = top + WikiLayout.BUTTON_HEIGHT;
		boolean hover = overContent && UiDraw.inside(mouseX, mouseY, x, top, x1b, y1b);
		if (hover) {
			UiDraw.glow(g, x, top, x1b, y1b, Theme.ROW_RADIUS, Theme.ACCENT, 4);
		}
		UiDraw.accentCard(g, x, top, x1b, y1b, Theme.ROW_RADIUS, Theme.ACCENT, true, false);
		int ix = x + 9, iy = top + (WikiLayout.BUTTON_HEIGHT - 9) / 2;
		UiDraw.border(g, ix + 3, iy, ix + 10, iy + 7, 1, Theme.TEXT);
		UiDraw.roundRect(g, ix, iy + 2, ix + 7, iy + 9, 1, Colors.withAlpha(Theme.ACCENT, 200));
		UiDraw.border(g, ix, iy + 2, ix + 7, iy + 9, 1, Theme.TEXT);
		UiDraw.text(g, font, button.text(), x + 24, UiFont.centeredY(UiFont.Face.STRONG, top, WikiLayout.BUTTON_HEIGHT), Theme.TEXT);
		popOutBounds = new int[] {x, Math.max(top, contentY0), x1b, Math.min(y1b, contentY1)};
	}

	private static int[] scrollbarBounds() {
		int x = x1 - (compact ? 4 : 7) - Theme.SCROLLBAR_WIDTH;
		return new int[] {x, contentY0, x + Theme.SCROLLBAR_WIDTH, contentY1};
	}

	/** A line of text: links in the light accent, the one under the mouse in the full accent and underlined. */
	private static void drawLine(GuiGraphicsExtractor g, Font font, WikiLayout.TextOp line, int x, int top, boolean overContent,
			int mouseX, int mouseY) {
		int y = UiFont.topY(UiFont.Face.BODY, top);
		for (WikiLayout.Piece piece : line.pieces()) {
			int px = x + piece.x();
			boolean hover = piece.link() != null && overContent && UiDraw.inside(mouseX, mouseY, px, top, px + piece.width(), top + Theme.BODY_LINE);
			if (hover) {
				hoveredLink = piece.link();
			}
			boolean linkHovered = piece.link() != null && piece.link().equals(hoveredLink);
			int color = linkHovered ? Theme.TEXT_LINK_HOVER : piece.color() != 0 ? piece.color() : line.color();
			UiDraw.text(g, font, piece.text(), px, y, color);
			if (linkHovered) {
				UiDraw.fill(g, px, top + Theme.BODY_LINE - 2, px + piece.width(), top + Theme.BODY_LINE - 1, Theme.TEXT_LINK_HOVER);
			}
		}
	}

	// ------------------------------------------------------------ the browser prompt

	private static int[] confirmBox() {
		int w = Math.min(x1 - x0 - 16, 200), h = 72;
		int bx0 = (x0 + x1 - w) / 2, by0 = y0 + HERO + 12;
		return new int[] {bx0, by0, bx0 + w, by0 + h};
	}

	/** Index 0: Open, 1: Cancel. */
	private static int[] confirmButton(int index) {
		int[] box = confirmBox();
		int w = (box[2] - box[0] - 3 * Theme.GAP) / 2, by1 = box[3] - Theme.GAP, by0 = by1 - 20;
		int bx0 = box[0] + Theme.GAP + index * (w + Theme.GAP);
		return new int[] {bx0, by0, bx0 + w, by1};
	}

	private static void drawConfirm(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		int[] box = confirmBox();
		UiDraw.shadow(g, box[0], box[1], box[2], box[3], Theme.CARD_RADIUS, 10);
		UiDraw.card(g, box[0], box[1], box[2], box[3], Theme.CARD_RADIUS, Theme.TOAST_FILL, Theme.TOAST_BORDER);
		int inner = box[2] - box[0] - 2 * Theme.CARD_PADDING;
		if (confirmTitle == null) {
			confirmTitle = UiFont.sequence("Open in browser?", UiFont.Face.STRONG);
		}
		String url = client().pageUrl(title).replaceFirst("^https://", "");
		if (!(url + inner).equals(confirmUrlFor)) {
			confirmUrlFor = url + inner;
			confirmUrl = UiFont.sequence(UiFont.fit(font, url, UiFont.Face.BODY, inner), UiFont.Face.BODY);
		}
		UiDraw.text(g, font, confirmTitle, box[0] + Theme.CARD_PADDING, UiFont.topY(UiFont.Face.STRONG, box[1] + Theme.CARD_PADDING), Theme.TEXT);
		UiDraw.text(g, font, confirmUrl, box[0] + Theme.CARD_PADDING, UiFont.topY(UiFont.Face.BODY, box[1] + Theme.CARD_PADDING + 14),
				Theme.TEXT_DIM);
		String[] names = {"Open", "Cancel"};
		for (int i = 0; i < 2; i++) {
			int[] b = confirmButton(i);
			boolean hover = UiDraw.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
			UiDraw.accentCard(g, b[0], b[1], b[2], b[3], Theme.ROW_RADIUS, Theme.ACCENT, i == 0, hover);
			UiFont.Label label = label(font, names[i]);
			UiDraw.label(g, font, label, b[0] + (b[2] - b[0] - label.width()) / 2, UiFont.centeredY(UiFont.Face.LABEL, b[1], b[3] - b[1]),
					i == 0 || hover ? Theme.TEXT : Theme.TEXT_LABEL);
		}
	}

	// ------------------------------------------------------------ input

	/** A click. Returns true if it was on the panel (then nothing under it gets it). */
	static boolean click(Screen screen, double x, double y, int button) {
		place(screen);
		if (!inside(x, y)) {
			return false;
		}
		if (button != 0) {
			return true;
		}
		if (confirming) {
			if (hit(confirmButton(0), x, y)) {
				confirming = false;
				Util.getPlatform().openUri(client().pageUrl(title));
			} else if (hit(confirmButton(1), x, y) || !hit(confirmBox(), x, y)) {
				confirming = false;
			}
			return true;
		}
		for (int i = 0; i < 3; i++) {
			if ((i != 0 || !back.isEmpty()) && hit(button(i), x, y)) {
				switch (i) {
					case 0 -> goBack();
					case 1 -> confirming = title != null;
					default -> close();
				}
				return true;
			}
		}
		if (y < y0 + HERO) {
			headerPress(x, y);
			return true;
		}
		if (state == State.ERROR && retryBounds != null && hit(retryBounds, x, y)) {
			load(title);
			return true;
		}
		if (state == State.PAGE) {
			int[] bar = scrollbarBounds();
			if (UiDraw.inside(x, y, bar[0] - 3, bar[1], bar[2] + 3, bar[3]) && layout != null && layout.height > contentY1 - contentY0) {
				draggingBar = true;
				drag(x, y);
				return true;
			}
			if (popOutBounds != null && hit(popOutBounds, x, y) && recipe != null) {
				popOut();
				return true;
			}
			for (Toggle toggle : toggles) {
				if (y >= toggle.y0() && y < toggle.y1() && x >= contentX0 && x < contentX1) {
					if (!OPEN.remove(toggle.key())) {
						OPEN.add(toggle.key());
					}
					return true;
				}
			}
			if (hoveredLink != null) {
				open(hoveredLink);
			}
		}
		return true;
	}

	/** Opens the recipe planner beside the sheet: to its left when there is room, else to its right, else over it. */
	private static void popOut() {
		int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
		int x = x0 - RecipePlanner.WIDTH - MARGIN;
		if (x < 0) {
			x = x1 + MARGIN <= screenWidth - RecipePlanner.WIDTH ? x1 + MARGIN : x0 + 16;
		}
		RecipePlanner.open(doc.title(), recipe, x, y0);
	}

	/** A press on the header: starts moving the panel; a second press soon after docks it again. */
	private static void headerPress(double x, double y) {
		FemboyClientConfig config = FemboyClientConfig.get();
		long now = System.nanoTime();
		if (now - lastHeaderPress < DOUBLE_CLICK_NS && config.wikiPanelX >= 0) {
			config.wikiPanelX = config.wikiPanelY = -1;
			config.save();
			lastHeaderPress = 0;
			return;
		}
		lastHeaderPress = now;
		movingPanel = true;
		moveDX = (int) x - x0;
		moveDY = (int) y - y0;
		config.wikiPanelX = x0;
		config.wikiPanelY = y0;
	}

	/** Dragging: the panel by its header, or the scroll bar. Returns true if the drag was ours. */
	static boolean drag(double x, double y) {
		if (movingPanel) {
			FemboyClientConfig config = FemboyClientConfig.get();
			config.wikiPanelX = Math.max(0, (int) x - moveDX);
			config.wikiPanelY = Math.max(0, (int) y - moveDY);
			return true;
		}
		if (!draggingBar || layout == null) {
			return false;
		}
		int view = contentY1 - contentY0;
		float t = (float) ((y - contentY0) / Math.max(1, view));
		scrollGoal = scroll = Ease.clamp01(t) * Math.max(0, layout.height - view);
		return true;
	}

	/** The mouse button came up: ends a drag. */
	static boolean release() {
		if (movingPanel) {
			movingPanel = false;
			FemboyClientConfig.get().save();
			return true;
		}
		boolean was = draggingBar;
		draggingBar = false;
		return was;
	}

	/** The wheel over the panel scrolls the page. Returns true if it was over the panel. */
	static boolean scroll(Screen screen, double x, double y, double amount) {
		place(screen);
		if (!inside(x, y)) {
			return false;
		}
		scrollGoal -= (float) (amount * Theme.SCROLL_STEP);
		return true;
	}

	/** Esc while the browser prompt is up closes only the prompt. Returns true if it did. */
	static boolean escape() {
		if (confirming) {
			confirming = false;
			return true;
		}
		return false;
	}

	/** The screen is closing: its textures go, the page stays for the next screen. */
	static void onScreenClosed() {
		WikiImages.releaseTextures();
		if (movingPanel) {
			FemboyClientConfig.get().save();
		}
		draggingBar = movingPanel = false;
		confirming = false;
		lastFrame = 0;
	}

	/** A new screen opened with the page still open: fade in with it. */
	static void onScreenOpened() {
		if (state != State.CLOSED) {
			shownAt = System.nanoTime();
		}
	}

	private static FormattedCharSequence glyph(String text) {
		return GLYPHS.computeIfAbsent(text, t -> UiFont.sequence(t, UiFont.Face.STRONG));
	}

	private static UiFont.Label label(Font font, String text) {
		return LABELS.computeIfAbsent(text, t -> UiFont.Label.of(font, t));
	}

	private static boolean hit(int[] box, double x, double y) {
		return UiDraw.inside(x, y, box[0], box[1], box[2], box[3]);
	}
}
