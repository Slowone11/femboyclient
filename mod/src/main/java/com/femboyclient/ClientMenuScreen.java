package com.femboyclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The G menu: every module of the mod in one window. The list on the left has each module's name and its ON/OFF switch; the
 * pane on the right shows the selected module's switch, what it does, what it is doing now, and all of its settings, so there
 * is no separate screen per module. Only the three big screens (placing the MansionFinder map, the lobby list and the
 * Frienemys tags) open on their own, from a button in the pane, and Esc there comes back here with the same module selected.
 *
 * The window is {@link Theme#WINDOW_WIDTH} by {@link Theme#WINDOW_HEIGHT}, made smaller to fit a small screen or a big GUI
 * scale; the list and the pane scroll with the mouse wheel when they do not fit. The header shows the website's address and
 * whether it answers.
 */
public final class ClientMenuScreen extends Screen {

	private static final int LIST_W = 176, ROW_H = 24;
	private static final int ROW_SWITCH_W = 30, ROW_SWITCH_H = 16;
	private static final int PANE_SWITCH_W = 44, PANE_SWITCH_H = 20;
	private static final int CONTROL_H = 20, LINE_GAP = 6;
	private static final int MARGIN = 8;            // the least space kept between the window and the screen's edge

	static final int MANSION = 0, SCANNER = 1, NPC = 2, LOBBY = 3, FRIENEMYS = 4, FULLBRIGHT = 5, KEEPSPRINT = 6, CLICKER = 7,
			PARTICLES = 8;

	/** One module: its name, what it does, and its switch (null for the scanner, which is always on). */
	private record Module(String name, String description, Function<FemboyClientConfig, Boolean> isOn,
			BiConsumer<FemboyClientConfig, Boolean> setOn) {
	}

	private static final List<Module> MODULES = List.of(
			new Module("MansionFinder", "Finds the Mansion in the Crystal Hollows and shows it on a map in the corner of your screen.",
					c -> c.hud, (c, on) -> {
						c.hud = on;
						c.save();
					}),
			new Module("Scanner", "Reports every player in your lobby, and everything players type in chat (yours too), to the "
					+ "website. Always on, in every world.", null, null),
			new Module("Auto Conversation", "Clicks the right option of a Hypixel NPC dialog for you, in the background, while you "
					+ "keep playing normally.",
					c -> c.npcAuto, (c, on) -> {
						c.npcAuto = on;
						c.save();
					}),
			new Module("Lobby Networth", "Who is in your lobby, with their networth, rank and how often you have met them, from the "
					+ "website.",
					c -> c.lobbyNetworth, (c, on) -> {
						c.lobbyNetworth = on;
						c.save();
					}),
			new Module("Frienemys", "Friend or enemy tags on players. A tagged player in your lobby shows up top-left, green or red.",
					c -> c.frienemys, (c, on) -> {
						c.frienemys = on;
						c.save();
					}),
			new Module("Fullbright", "Forces maximum brightness, past what the options screen's gamma slider allows.",
					c -> c.fullbright, (c, on) -> {
						c.fullbright = on;
						c.save();
					}),
			new Module("Keepsprint", "Sprints while you move in any direction, not just forward like vanilla.",
					c -> c.keepsprint, (c, on) -> {
						c.keepsprint = on;
						c.save();
					}),
			new Module("Looped Clicker", "Shows click controls whenever a container is open. It keeps clicking one spot until you "
					+ "stop it. Switching it on only shows the controls: it never starts on its own.",
					c -> c.clicker, (c, on) -> {
						c.clicker = on;
						if (!on) {
							LoopedClicker.stop();
						}
						c.save();
					}),
			new Module("No Particles", "Hides every particle: block breaking, mobs and creatures, hits, explosions, potions, rain "
					+ "and abilities. Only what you see changes.",
					c -> c.noParticles, NoParticles::set));

	/** The module selected last time, so the menu reopens on it (also after Esc on one of the big screens). */
	private static int selected = 0;

	/** Something clickable drawn this frame. A right click runs {@code right} when there is one. */
	private record Hit(int x0, int y0, int x1, int y1, Runnable left, Runnable right) {
	}

	private final List<Hit> hits = new ArrayList<>();
	private final Map<String, int[]> named = new HashMap<>();
	private final Map<String, UiFont.Label> labels = new HashMap<>();
	private final Map<String, List<FormattedCharSequence>> wrapped = new HashMap<>();

	private long openedAt;
	private int winX0, winY0, winX1, winY1;
	private int listX0, listX1, listY0, listY1;
	private int paneX0, paneX1, paneY0, paneY1;
	private int listScroll, paneScroll, paneContent;
	private boolean bindingKey;

	// What the frame being drawn uses, so the small drawing helpers below need fewer arguments.
	private GuiGraphicsExtractor g;
	private int mouseX, mouseY, cy;

	public ClientMenuScreen() {
		super(Component.literal("Femboy Client"));
	}

	@Override
	protected void init() {
		if (openedAt == 0) {
			openedAt = System.nanoTime();
		}
		int w = Math.min(Theme.WINDOW_WIDTH, width - 2 * MARGIN);
		int h = Math.min(Theme.WINDOW_HEIGHT, height - 2 * MARGIN);
		winX0 = (width - w) / 2;
		winY0 = (height - h) / 2;
		winX1 = winX0 + w;
		winY1 = winY0 + h;

		listX0 = winX0 + Theme.GAP;
		listX1 = winX0 + Math.min(LIST_W, w / 3);
		listY0 = winY0 + Theme.HEADER_HEIGHT + Theme.GAP;
		listY1 = winY1 - Theme.GAP;
		paneX0 = listX1 + Theme.PADDING;
		paneX1 = winX1 - Theme.PADDING;
		paneY0 = winY0 + Theme.HEADER_HEIGHT;
		paneY1 = winY1 - Theme.GAP;
		wrapped.clear();
		selected = Math.max(0, Math.min(MODULES.size() - 1, selected));
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		float opened = Ease.inOutCubic((System.nanoTime() - openedAt) / 1_000_000f / Theme.OPEN_MS);
		UiDraw.setFade(opened);
		UiDraw.setScale(minecraft.getWindow().getGuiScale());
		UiDraw.blurBackdrop(graphics, width, height);
		UiDraw.setFade(1f);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		this.g = graphics;
		this.mouseX = mouseX;
		this.mouseY = mouseY;
		hits.clear();
		named.clear();
		UiDraw.setScale(minecraft.getWindow().getGuiScale());
		UiDraw.setFade(Ease.inOutCubic((System.nanoTime() - openedAt) / 1_000_000f / Theme.OPEN_MS));

		UiDraw.shadow(graphics, winX0, winY0, winX1, winY1, Theme.PANEL_RADIUS, Theme.SHADOW_SPREAD);
		UiDraw.glass(graphics, winX0, winY0, winX1, winY1, Theme.PANEL_RADIUS);
		UiDraw.divider(graphics, winX0 + 1, winY0 + Theme.HEADER_HEIGHT, winX1 - 1);
		UiDraw.fill(graphics, listX1 + Theme.GAP / 2, winY0 + Theme.HEADER_HEIGHT + Theme.GAP, listX1 + Theme.GAP / 2 + 1, winY1 - Theme.GAP,
				Theme.DIVIDER);

		drawHeader();
		drawList();
		drawPane();
		UiDraw.setFade(1f);
	}

	// ------------------------------------------------------------ header

	/** The title on the left; the website's address and whether it answers on the right. */
	private void drawHeader() {
		int mid = winY0 + Theme.HEADER_HEIGHT / 2;
		UiDraw.glowDot(g, winX0 + Theme.PADDING + 5, mid, 3, Theme.ACCENT);
		UiDraw.text(g, font, UiFont.sequence("Femboy Client", UiFont.Face.VALUE), winX0 + Theme.PADDING + 18,
				UiFont.centeredY(UiFont.Face.VALUE, winY0, Theme.HEADER_HEIGHT), Theme.TEXT);

		Boolean connected = FemboyClientMod.SERVER.connected();
		String state = connected == null ? "checking…" : connected ? "connected" : "not reachable";
		String address = FemboyClientMod.SERVER.serverUrl().replaceFirst("^https?://", "");
		int titleEnd = winX0 + Theme.PADDING + 18 + UiFont.width(font, "Femboy Client", UiFont.Face.VALUE) + 16;
		int dotX = winX1 - Theme.PADDING - 4;
		String shown = UiFont.fit(font, address + " · " + state, UiFont.Face.BODY, Math.max(0, dotX - 10 - titleEnd));
		int textW = UiFont.width(font, shown, UiFont.Face.BODY);
		UiDraw.text(g, font, UiFont.sequence(shown, UiFont.Face.BODY), dotX - 10 - textW,
				UiFont.centeredY(UiFont.Face.BODY, winY0, Theme.HEADER_HEIGHT),
				connected == null ? Theme.TEXT_DIM : connected ? Theme.TEXT_DIM : Theme.TEXT_ERROR);
		UiDraw.glowDot(g, dotX, mid, 3, connected == null ? Theme.TEXT_DIM : connected ? Theme.ACCENT : Theme.TEXT_ERROR);
	}

	// ------------------------------------------------------------ module list

	private void drawList() {
		int content = MODULES.size() * ROW_H;
		listScroll = clamp(listScroll, 0, Math.max(0, content - (listY1 - listY0)));
		FemboyClientConfig config = FemboyClientConfig.get();

		UiDraw.beginClip(g, listX0, listY0, listX1, listY1);
		for (int i = 0; i < MODULES.size(); i++) {
			Module module = MODULES.get(i);
			int[] row = rowBounds(i);
			if (row[3] <= listY0 || row[1] >= listY1) {
				continue;
			}
			boolean hover = visible(mouseX, mouseY, listX0, listY0, listX1, listY1) && UiDraw.inside(mouseX, mouseY, row[0], row[1], row[2], row[3]);
			if (i == selected) {
				UiDraw.accentCard(g, row[0], row[1], row[2], row[3], Theme.ROW_RADIUS, Theme.ACCENT, true, false);
			} else if (hover) {
				UiDraw.hoverCard(g, row[0], row[1], row[2], row[3], Theme.ROW_RADIUS, true);
			}
			int textRight = row[2] - 8 - ROW_SWITCH_W - 6;
			String name = UiFont.fit(font, module.name(), UiFont.Face.STRONG, textRight - row[0] - 9);
			UiDraw.text(g, font, UiFont.sequence(name, UiFont.Face.STRONG), row[0] + 9, UiFont.centeredY(UiFont.Face.STRONG, row[1], ROW_H - 2),
					i == selected || hover ? Theme.TEXT : Theme.TEXT_DIM);

			int index = i;
			if (module.isOn() == null) {
				UiDraw.glowDot(g, row[2] - 8 - ROW_SWITCH_W / 2, (row[1] + row[3]) / 2, 3, Theme.ACCENT);
			} else {
				int[] sw = switchBounds(i);
				boolean swHover = hover && UiDraw.inside(mouseX, mouseY, sw[0], sw[1], sw[2], sw[3]);
				UiDraw.toggleSwitch(g, sw[0], sw[1], sw[2], sw[3], module.isOn().apply(config), swHover);
				hit(sw[0], sw[1], sw[2], sw[3], listX0, listY0, listX1, listY1, () -> toggle(index), null);
			}
			hit(row[0], row[1], row[2], row[3], listX0, listY0, listX1, listY1, () -> select(index), null);
		}
		UiDraw.endClip(g);
		scrollbar(listX1 - 2, listY0, listY1, content, listScroll);
	}

	// ------------------------------------------------------------ settings pane

	private void drawPane() {
		Module module = MODULES.get(selected);
		FemboyClientConfig config = FemboyClientConfig.get();
		int top = paneY0 + 12;
		paneScroll = clamp(paneScroll, 0, Math.max(0, paneContent - (paneY1 - top)));
		cy = top - paneScroll;

		UiDraw.beginClip(g, paneX0, paneY0 + 1, paneX1, paneY1);

		// The title, and the module's switch (or the scanner's badge) on the same line.
		UiDraw.text(g, font, UiFont.sequence(module.name(), UiFont.Face.VALUE), paneX0, UiFont.topY(UiFont.Face.VALUE, cy), Theme.TEXT);
		int titleH = UiFont.capHeight(UiFont.Face.VALUE);
		if (module.isOn() == null) {
			badge("ALWAYS ON", paneX1, cy + titleH / 2);
		} else {
			int[] sw = paneSwitchBounds();
			boolean on = module.isOn().apply(config);
			UiDraw.toggleSwitch(g, sw[0], sw[1], sw[2], sw[3], on, over(sw));
			int index = selected;
			hit(sw[0], sw[1], sw[2], sw[3], () -> toggle(index), null);
			named.put("pane-switch", sw);
		}
		cy += Math.max(titleH, PANE_SWITCH_H - 4) + 10;
		paragraph(module.description(), Theme.TEXT_DIM);

		switch (selected) {
			case MANSION -> mansionSettings(config);
			case SCANNER -> scannerSettings();
			case NPC -> npcSettings();
			case LOBBY -> lobbySettings();
			case FRIENEMYS -> frienemySettings();
			case CLICKER -> clickerSettings(config);
			case PARTICLES -> particleSettings();
			default -> {
			}
		}

		paneContent = cy + paneScroll - top + 6;
		UiDraw.endClip(g);
		scrollbar(paneX1 + Theme.PADDING / 2, top, paneY1, paneContent, paneScroll);
	}

	private void mansionSettings(FemboyClientConfig config) {
		section("Map");
		stepper("Map size", config.hudSize + " px",
				() -> resizeMap(config, -10), () -> resizeMap(config, 10));
		switchRow("Render boost", "Biggest view distance in the Crystal Hollows, so every chunk is scanned.", config.scanRenderBoost,
				() -> {
					config.scanRenderBoost = !config.scanRenderBoost;
					config.save();
				});
		buttons(new String[] {"Edit map position", "Reset position"},
				new Runnable[] {() -> minecraft.setScreenAndShow(new HollowsMapScreen().fromMenu()), () -> HollowsMap.reset(config)});
		info("Position", config.hudX + ", " + config.hudY);
	}

	private static void resizeMap(FemboyClientConfig config, int by) {
		config.hudSize = clamp(config.hudSize + by, HollowsHud.MIN_SIZE, HollowsHud.MAX_SIZE);
		config.save();
	}

	private void scannerSettings() {
		section("Now");
		String world = FemboyClientMod.PLAYER_TRACKER.knownWorld();
		info("World", world == null ? "not known yet" : world);
		Instant last = FemboyClientMod.PLAYER_TRACKER.lastReportedAt();
		info("Players last sent", last == null ? "none yet" : FemboyClientMod.PLAYER_TRACKER.lastReported() + " · " + ago(last));
		info("Chat messages logged", Integer.toString(FemboyClientMod.CHAT_COLLECTOR.collected()));
		Boolean connected = FemboyClientMod.SERVER.connected();
		info("Website", connected == null ? "checking…" : connected ? "connected" : "not reachable", connected != null && !connected);
		section("What it records");
		paragraph("Players: everyone in your tab list, each time you join a lobby, in every SkyBlock world.", Theme.TEXT_DIM);
		paragraph("Chat: everything a player types, in public, party, guild, co-op and private chat, yours too, every line, "
				+ "repeats included. Server lines (Bazaar, drops) are not chat and are left out.", Theme.TEXT_DIM);
	}

	private void npcSettings() {
		section("How it picks");
		paragraph("One option offered → always clicked. Nothing to decide.", Theme.TEXT);
		paragraph("Two or more options → the green one is clicked. None of them green? Nothing is sent, and it is left for you to "
				+ "click yourself.", Theme.TEXT);
	}

	private void lobbySettings() {
		section("Lobby");
		info("Players in your lobby", Integer.toString(FemboyClientMod.PLAYER_TRACKER.lobbyNames().size()));
		buttons(new String[] {"Open lobby list"}, new Runnable[] {() -> minecraft.setScreenAndShow(PlayerInfoKey.playersScreen().fromMenu())});
		paragraph("Off still lists who is in your lobby; it only skips asking the website about them.", Theme.TEXT_DIM);
	}

	private void frienemySettings() {
		section("Tags");
		buttons(new String[] {"Open tags"}, new Runnable[] {() -> minecraft.setScreenAndShow(new FrienemiesScreen().fromMenu())});
		paragraph("Off hides the overlay. The tags themselves stay on the website.", Theme.TEXT_DIM);
	}

	private void clickerSettings(FemboyClientConfig config) {
		section("Clicking");
		stepper("Time between clicks", config.clickerMs + " ms",
				() -> setClickerMs(config, config.clickerMs - 10), () -> setClickerMs(config, config.clickerMs + 10));
		chips("Button", new String[] {"Left", "Right"}, config.clickerRightClick ? 1 : 0, choice -> {
			config.clickerRightClick = choice == 1;
			config.save();
		});
		String key = bindingKey ? "Press a key…" : config.clickerKey < 0 ? "not set"
				: InputConstants.Type.KEYSYM.getOrCreate(config.clickerKey).getDisplayName().getString();
		labeledButton("Start/Stop key", key, () -> bindingKey = !bindingKey, () -> {
			bindingKey = false;
			config.clickerKey = -1;
			config.save();
		});
		paragraph("Click to set the key, then press it (Esc cancels). Right click clears it. The spot it clicks is set with "
				+ "\"Set Location\" in the controls on a container.", Theme.TEXT_DIM);
	}

	private static void setClickerMs(FemboyClientConfig config, int ms) {
		config.clickerMs = clamp(ms, LoopedClicker.MIN_MS, LoopedClicker.MAX_MS);
		config.save();
	}

	private void particleSettings() {
		section("Now");
		info("Particles hidden this game", String.format("%,d", NoParticles.blockedCount()));
	}

	// ------------------------------------------------------------ pane building blocks

	/** A small ALL-CAPS heading with a little space above it. */
	private void section(String text) {
		cy += 4;
		UiDraw.label(g, font, label(text), paneX0, UiFont.topY(UiFont.Face.LABEL, cy), Theme.TEXT_LABEL);
		cy += UiFont.capHeight(UiFont.Face.LABEL) + LINE_GAP + 2;
	}

	/** Wrapped text, the full width of the pane. */
	private void paragraph(String text, int color) {
		List<FormattedCharSequence> lines = wrapped.computeIfAbsent(text,
				t -> font.split(UiFont.component(t, UiFont.Face.BODY), Math.max(40, paneX1 - paneX0)));
		for (FormattedCharSequence line : lines) {
			UiDraw.text(g, font, line, paneX0, UiFont.topY(UiFont.Face.BODY, cy), color);
			cy += Theme.BODY_LINE;
		}
		cy += LINE_GAP - 2;
	}

	/** "Name ............ value" on one line. */
	private void info(String name, String value) {
		info(name, value, false);
	}

	private void info(String name, String value, boolean problem) {
		int y = UiFont.topY(UiFont.Face.BODY, cy);
		UiDraw.text(g, font, UiFont.sequence(name, UiFont.Face.BODY), paneX0, y, Theme.TEXT_DIM);
		String shown = UiFont.fit(font, value, UiFont.Face.STRONG, (paneX1 - paneX0) / 2);
		UiDraw.text(g, font, UiFont.sequence(shown, UiFont.Face.STRONG), paneX1 - UiFont.width(font, shown, UiFont.Face.STRONG),
				UiFont.topY(UiFont.Face.STRONG, cy), problem ? Theme.TEXT_ERROR : Theme.TEXT);
		cy += Theme.BODY_LINE + 2;
	}

	/** A name and a hint on the left, a switch on the right. */
	private void switchRow(String name, String hint, boolean on, Runnable toggle) {
		int rowTop = cy;
		int sx1 = paneX1, sx0 = sx1 - PANE_SWITCH_W, sy0 = rowTop, sy1 = sy0 + PANE_SWITCH_H;
		UiDraw.text(g, font, UiFont.sequence(name, UiFont.Face.STRONG), paneX0, UiFont.topY(UiFont.Face.STRONG, cy + 2), Theme.TEXT);
		cy += UiFont.capHeight(UiFont.Face.STRONG) + 6;
		List<FormattedCharSequence> lines = wrapped.computeIfAbsent("hint|" + hint,
				t -> font.split(UiFont.component(hint, UiFont.Face.BODY), Math.max(40, sx0 - Theme.GAP - paneX0)));
		for (FormattedCharSequence line : lines) {
			UiDraw.text(g, font, line, paneX0, UiFont.topY(UiFont.Face.BODY, cy), Theme.TEXT_DIM);
			cy += Theme.BODY_LINE;
		}
		UiDraw.toggleSwitch(g, sx0, sy0, sx1, sy1, on, UiDraw.inside(mouseX, mouseY, sx0, sy0, sx1, sy1) && inPane());
		hit(sx0, sy0, sx1, sy1, toggle, null);
		named.put("switch:" + name, new int[] {sx0, sy0, sx1, sy1});
		cy = Math.max(cy, sy1) + LINE_GAP;
	}

	/** "Name      [−] value [+]" */
	private void stepper(String name, String value, Runnable minus, Runnable plus) {
		UiDraw.text(g, font, UiFont.sequence(name, UiFont.Face.STRONG), paneX0, UiFont.centeredY(UiFont.Face.STRONG, cy, CONTROL_H), Theme.TEXT);
		int valueW = Math.max(56, UiFont.width(font, value, UiFont.Face.STRONG) + 12);
		int px1 = paneX1, px0 = px1 - CONTROL_H;
		int vx1 = px0 - 4, vx0 = vx1 - valueW;
		int mx1 = vx0 - 4, mx0 = mx1 - CONTROL_H;
		smallButton("−", mx0, cy, mx1, cy + CONTROL_H, minus);
		UiDraw.card(g, vx0, cy, vx1, cy + CONTROL_H, Theme.ROW_RADIUS, Theme.CARD_FILL, Theme.CARD_BORDER);
		UiDraw.text(g, font, UiFont.sequence(value, UiFont.Face.STRONG), vx0 + (valueW - UiFont.width(font, value, UiFont.Face.STRONG)) / 2,
				UiFont.centeredY(UiFont.Face.STRONG, cy, CONTROL_H), Theme.TEXT);
		smallButton("+", px0, cy, px1, cy + CONTROL_H, plus);
		named.put("minus:" + name, new int[] {mx0, cy, mx1, cy + CONTROL_H});
		named.put("plus:" + name, new int[] {px0, cy, px1, cy + CONTROL_H});
		cy += CONTROL_H + LINE_GAP;
	}

	/** "Name      [A] [B]": one of a few options, the chosen one in the accent. */
	private void chips(String name, String[] options, int chosen, java.util.function.IntConsumer choose) {
		UiDraw.text(g, font, UiFont.sequence(name, UiFont.Face.STRONG), paneX0, UiFont.centeredY(UiFont.Face.STRONG, cy, CONTROL_H), Theme.TEXT);
		int x1 = paneX1;
		for (int i = options.length - 1; i >= 0; i--) {
			UiFont.Label text = label(options[i]);
			int x0 = x1 - text.width() - 20;
			boolean hover = UiDraw.inside(mouseX, mouseY, x0, cy, x1, cy + CONTROL_H) && inPane();
			UiDraw.accentCard(g, x0, cy, x1, cy + CONTROL_H, Theme.ROW_RADIUS, Theme.ACCENT, i == chosen, hover);
			UiDraw.label(g, font, text, x0 + 10, UiFont.centeredY(UiFont.Face.LABEL, cy, CONTROL_H), i == chosen || hover ? Theme.TEXT : Theme.TEXT_LABEL);
			int choice = i;
			hit(x0, cy, x1, cy + CONTROL_H, () -> choose.accept(choice), null);
			named.put("chip:" + options[i], new int[] {x0, cy, x1, cy + CONTROL_H});
			x1 = x0 - 4;
		}
		cy += CONTROL_H + LINE_GAP;
	}

	/** "Name      [value]": a button showing a value, for the key binding. */
	private void labeledButton(String name, String value, Runnable left, Runnable right) {
		UiDraw.text(g, font, UiFont.sequence(name, UiFont.Face.STRONG), paneX0, UiFont.centeredY(UiFont.Face.STRONG, cy, CONTROL_H), Theme.TEXT);
		int w = Math.max(90, UiFont.width(font, value, UiFont.Face.STRONG) + 20);
		int x1 = paneX1, x0 = x1 - w;
		boolean hover = UiDraw.inside(mouseX, mouseY, x0, cy, x1, cy + CONTROL_H) && inPane();
		UiDraw.accentCard(g, x0, cy, x1, cy + CONTROL_H, Theme.ROW_RADIUS, Theme.ACCENT, bindingKey, hover);
		UiDraw.text(g, font, UiFont.sequence(value, UiFont.Face.STRONG), x0 + (w - UiFont.width(font, value, UiFont.Face.STRONG)) / 2,
				UiFont.centeredY(UiFont.Face.STRONG, cy, CONTROL_H), Theme.TEXT);
		hit(x0, cy, x1, cy + CONTROL_H, left, right);
		named.put("button:" + name, new int[] {x0, cy, x1, cy + CONTROL_H});
		cy += CONTROL_H + LINE_GAP;
	}

	/** A row of buttons, left to right. */
	private void buttons(String[] texts, Runnable[] actions) {
		int x0 = paneX0;
		for (int i = 0; i < texts.length; i++) {
			UiFont.Label text = label(texts[i]);
			int x1 = x0 + text.width() + 24;
			boolean hover = UiDraw.inside(mouseX, mouseY, x0, cy, x1, cy + CONTROL_H) && inPane();
			UiDraw.hoverCard(g, x0, cy, x1, cy + CONTROL_H, Theme.ROW_RADIUS, hover);
			UiDraw.label(g, font, text, x0 + 12, UiFont.centeredY(UiFont.Face.LABEL, cy, CONTROL_H), hover ? Theme.ACCENT_LIGHT : Theme.TEXT_LABEL);
			hit(x0, cy, x1, cy + CONTROL_H, actions[i], null);
			named.put("button:" + texts[i], new int[] {x0, cy, x1, cy + CONTROL_H});
			x0 = x1 + 6;
		}
		cy += CONTROL_H + LINE_GAP;
	}

	private void smallButton(String text, int x0, int y0, int x1, int y1, Runnable action) {
		boolean hover = UiDraw.inside(mouseX, mouseY, x0, y0, x1, y1) && inPane();
		UiDraw.hoverCard(g, x0, y0, x1, y1, Theme.ROW_RADIUS, hover);
		UiDraw.text(g, font, UiFont.sequence(text, UiFont.Face.STRONG), x0 + (x1 - x0 - UiFont.width(font, text, UiFont.Face.STRONG)) / 2,
				UiFont.centeredY(UiFont.Face.STRONG, y0, y1 - y0), hover ? Theme.ACCENT_LIGHT : Theme.TEXT);
		hit(x0, y0, x1, y1, action, null);
	}

	/** A small pill with an ALL-CAPS word, right-aligned to {@code right} and centred on {@code midY}. */
	private void badge(String text, int right, int midY) {
		UiFont.Label word = label(text);
		int x0 = right - word.width() - 16, y0 = midY - Theme.BADGE_HEIGHT / 2;
		UiDraw.card(g, x0, y0, right, y0 + Theme.BADGE_HEIGHT, Theme.BADGE_RADIUS, Theme.ACCENT_TINT, Theme.ACCENT_LINE);
		UiDraw.label(g, font, word, x0 + 8, UiFont.centeredY(UiFont.Face.LABEL, y0, Theme.BADGE_HEIGHT), Theme.ACCENT_LIGHT);
	}

	private void scrollbar(int x, int top, int bottom, int content, int scroll) {
		int visible = bottom - top;
		if (content <= visible || visible <= 0) {
			return;
		}
		int thumb = Math.max(16, visible * visible / content);
		int thumbTop = top + (visible - thumb) * scroll / Math.max(1, content - visible);
		UiDraw.scrollbar(g, x, top, x + Theme.SCROLLBAR_WIDTH, bottom, thumbTop, thumbTop + thumb, false);
	}

	private UiFont.Label label(String text) {
		return labels.computeIfAbsent(text, t -> UiFont.Label.of(font, t));
	}

	// ------------------------------------------------------------ clicks and keys

	private void hit(int x0, int y0, int x1, int y1, Runnable left, Runnable right) {
		hit(x0, y0, x1, y1, paneX0, paneY0, paneX1, paneY1, left, right);
	}

	/** Remembers a clickable box, cut to the visible area it was drawn in (so a scrolled-away control cannot be clicked). */
	private void hit(int x0, int y0, int x1, int y1, int cx0, int cy0, int cx1, int cy1, Runnable left, Runnable right) {
		int a = Math.max(x0, cx0), b = Math.max(y0, cy0), c = Math.min(x1, cx1), d = Math.min(y1, cy1);
		if (a < c && b < d) {
			hits.add(new Hit(a, b, c, d, left, right));
		}
	}

	private boolean over(int[] box) {
		return UiDraw.inside(mouseX, mouseY, box[0], box[1], box[2], box[3]) && inPane();
	}

	private boolean inPane() {
		return visible(mouseX, mouseY, paneX0, paneY0, paneX1, paneY1);
	}

	private static boolean visible(double x, double y, int x0, int y0, int x1, int y1) {
		return UiDraw.inside(x, y, x0, y0, x1, y1);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 || event.button() == 1) {
			// The switch is drawn on top of its row, and was added first, so it wins.
			for (Hit hit : hits) {
				Runnable action = event.button() == 0 ? hit.left() : hit.right();
				if (action != null && UiDraw.inside(event.x(), event.y(), hit.x0(), hit.y0(), hit.x1(), hit.y1())) {
					// A click anywhere but the key button stops waiting for a key.
					int[] keyButton = named.get("button:Start/Stop key");
					if (keyButton == null || !UiDraw.inside(event.x(), event.y(), keyButton[0], keyButton[1], keyButton[2], keyButton[3])) {
						bindingKey = false;
					}
					action.run();
					return true;
				}
			}
		}
		bindingKey = false;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		int step = (int) Math.round(-scrollY * Theme.SCROLL_STEP);
		if (visible(mouseX, mouseY, listX0, listY0, listX1, listY1)) {
			listScroll += step;
			return true;
		}
		if (visible(mouseX, mouseY, paneX0, paneY0, winX1, paneY1)) {
			paneScroll += step;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (bindingKey) {
			bindingKey = false;
			if (!event.isEscape()) {
				FemboyClientConfig config = FemboyClientConfig.get();
				config.clickerKey = event.key();
				config.save();
			}
			return true;
		}
		if (event.key() == InputConstants.KEY_UP) {
			select(Math.max(0, selected - 1));
			return true;
		}
		if (event.key() == InputConstants.KEY_DOWN) {
			select(Math.min(MODULES.size() - 1, selected + 1));
			return true;
		}
		return super.keyPressed(event);
	}

	private void select(int index) {
		if (index != selected) {
			selected = index;
			paneScroll = 0;
			bindingKey = false;
		}
		// Keep the selected row in view.
		int[] row = rowBounds(index);
		if (row[1] < listY0) {
			listScroll -= listY0 - row[1];
		} else if (row[3] > listY1) {
			listScroll += row[3] - listY1;
		}
	}

	private static void toggle(int index) {
		Module module = MODULES.get(index);
		if (module.setOn() != null) {
			FemboyClientConfig config = FemboyClientConfig.get();
			module.setOn().accept(config, !module.isOn().apply(config));
		}
	}

	// ------------------------------------------------------------ helpers

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	/** "12s ago", "3m ago", "2h ago". */
	private static String ago(Instant when) {
		long seconds = Math.max(0, Duration.between(when, Instant.now()).getSeconds());
		if (seconds < 60) {
			return seconds + "s ago";
		}
		if (seconds < 3600) {
			return seconds / 60 + "m ago";
		}
		return seconds / 3600 + "h ago";
	}

	// ------------------------------------------------------------ for the screenshot tests

	/** The module selected now (index into the list: {@link #MANSION} to {@link #PARTICLES}). */
	public int selected() {
		return selected;
	}

	/** The bounds {x0, y0, x1, y1} of the row at this index in the list (clicking it selects the module). */
	public int[] rowBounds(int index) {
		int y0 = listY0 + index * ROW_H - listScroll;
		return new int[] {listX0, y0 + 1, listX1 - Theme.GAP, y0 + ROW_H - 1};
	}

	/** The bounds {x0, y0, x1, y1} of the ON/OFF switch on the list row at this index. */
	public int[] switchBounds(int index) {
		int[] row = rowBounds(index);
		int y0 = (row[1] + row[3] - ROW_SWITCH_H) / 2;
		return new int[] {row[2] - 8 - ROW_SWITCH_W, y0, row[2] - 8, y0 + ROW_SWITCH_H};
	}

	/** The bounds {x0, y0, x1, y1} of the big switch at the top of the settings pane. */
	public int[] paneSwitchBounds() {
		int top = paneY0 + 12 - paneScroll;
		int mid = top + UiFont.capHeight(UiFont.Face.VALUE) / 2;
		return new int[] {paneX1 - PANE_SWITCH_W, mid - PANE_SWITCH_H / 2, paneX1, mid + PANE_SWITCH_H / 2};
	}

	/**
	 * The bounds {x0, y0, x1, y1} of a control in the pane as last drawn, or null: "button:Open lobby list",
	 * "switch:Render boost", "minus:Map size", "plus:Time between clicks", "chip:Right", "pane-switch".
	 */
	public int[] controlBounds(String name) {
		return named.get(name);
	}
}
