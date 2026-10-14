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
import java.util.function.IntConsumer;

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
	private static final int SLIDER_H = 14, SLIDER_KNOB = 10;
	private static final int MARGIN = 8;            // the least space kept between the window and the screen's edge

	static final int MANSION = 0, SCANNER = 1, NPC = 2, LOBBY = 3, FRIENEMYS = 4, FULLBRIGHT = 5, KEEPSPRINT = 6, CLICKER = 7,
			PARTICLES = 8, HANDMAN = 9, COPY_CHAT = 10, HIDE_PLAYERS = 11, CHEST_ESP = 12, GHOST_BLOCKS = 13, KEY_HIGHLIGHT = 14,
			TEAMMATES = 15, SECRET_HITBOXES = 16, PUZZLES = 17, CAMERA = 18, SERVER_DISPLAY = 19, CHAT_COMMANDS = 20, NO_DEBUFF = 21,
			WIKI_BAR = 22, PET_SWAPPER = 23, STARRED_MOBS = 24;

	/** The tabs above the module list. All lists every module; Dungeons only the dungeon ones. */
	private static final String[] TABS = {"All", "Dungeons"};
	private static final java.util.Set<Integer> DUNGEON_MODULES = java.util.Set.of(GHOST_BLOCKS, KEY_HIGHLIGHT, TEAMMATES,
			SECRET_HITBOXES, PUZZLES, STARRED_MOBS);
	private static final int TAB_H = 20;

	/** One module: its name, what it does, and its switch (null for the scanner, which is always on). */
	private record Module(String name, String description, Function<FemboyClientConfig, Boolean> isOn,
			BiConsumer<FemboyClientConfig, Boolean> setOn) {
	}

	private static final List<Module> MODULES = List.of(
			new Module("MansionFinder", "Finds the Mansion in the Crystal Hollows and shows it on a map in the corner of your screen.",
					c -> c.hud, saved((c, on) -> c.hud = on)),
			new Module("Scanner", "Reports every player in your lobby, and everything players type in chat (yours too), to the "
					+ "website. Always on, in every world.", null, null),
			new Module("Auto Conversation", "Clicks the right option of a Hypixel NPC dialog for you, in the background, while you "
					+ "keep playing normally.",
					c -> c.npcAuto, saved((c, on) -> c.npcAuto = on)),
			new Module("Lobby Networth", "Who is in your lobby, with their networth, rank and how often you have met them, from the "
					+ "website.",
					c -> c.lobbyNetworth, saved((c, on) -> c.lobbyNetworth = on)),
			new Module("Frienemys", "Friend or enemy tags on players. A tagged player in your lobby shows up top-left, green or red.",
					c -> c.frienemys, saved((c, on) -> c.frienemys = on)),
			new Module("Fullbright", "Forces maximum brightness, past what the options screen's gamma slider allows.",
					c -> c.fullbright, saved((c, on) -> c.fullbright = on)),
			new Module("Auto Sprint", "Automatically makes you sprint.",
					c -> c.keepsprint, saved((c, on) -> c.keepsprint = on)),
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
					c -> c.noParticles, NoParticles::set),
			new Module("HandMan", "Changes the size, position, rotation and swing speed of your hand and held item. Only what you "
					+ "see changes.",
					c -> c.handMan, saved((c, on) -> c.handMan = on)),
			new Module("Copy Chat", "Allows you to right click messages in chat to copy them.",
					c -> c.copyChat, saved((c, on) -> c.copyChat = on)),
			new Module("Hide Players", "Hides players.",
					c -> c.hidePlayers, saved((c, on) -> c.hidePlayers = on)),
			new Module("Chest ESP", "Renders chams or the bounding box for all chests.",
					c -> c.chestEsp, saved((c, on) -> c.chestEsp = on)),
			new Module("Ghost Blocks", "Creates ghost blocks on key press, and in specific locations.",
					c -> c.ghostBlocks, saved((c, on) -> c.ghostBlocks = on)),
			new Module("Key Highlight", "Draws a box around the key.",
					c -> c.keyHighlight, saved((c, on) -> c.keyHighlight = on)),
			new Module("Teammate Highlight", "Enhances visibility of your dungeon teammates and their name tags.",
					c -> c.teammatesHighlight, saved((c, on) -> c.teammatesHighlight = on)),
			new Module("Secret Hitboxes", "Full block Secret hitboxes.",
					c -> c.secretHitboxes, saved((c, on) -> c.secretHitboxes = on)),
			new Module("Puzzle Solvers", "Dungeon puzzle solvers.",
					c -> c.puzzleSolvers, saved((c, on) -> c.puzzleSolvers = on)),
			new Module("Camera", "Allows you to change qualities about third person view.",
					c -> c.camera, saved((c, on) -> c.camera = on)),
			new Module("Server Display", "Displays your current ping and the server's TPS.",
					c -> c.serverDisplay, saved((c, on) -> c.serverDisplay = on)),
			new Module("Chat Commands", "Type !help in the corresponding channel for cmd list. Use /blacklist.",
					c -> c.chatCommands, saved((c, on) -> c.chatCommands = on)),
			new Module("No Debuff", "Disables certain debuffs.",
					c -> c.noDebuff, saved((c, on) -> c.noDebuff = on)),
			new Module("Wiki Bar", "A bar under every inventory and chest. Type math to calculate it (50m/4, 3s, ans*2), or an item "
					+ "to read its SkyBlock wiki page and plan its recipe. Ctrl+F or a click types in it; Ctrl+drag moves it.",
					c -> c.wikiBar, saved((c, on) -> c.wikiBar = on)),
			new Module("Pet Swapper", "Crystal Hollows: when you enter the Magma Fields it puts Bal on, when your heat goes down it "
					+ "puts back the pet you had before. Swaps through /pets in the background: no menu shows.",
					c -> c.petSwapper, saved((c, on) -> c.petSwapper = on)),
			new Module("Starred Mobs", "Shows the starred (✯) mobs, the ones a room needs killed, through walls in dungeons.",
					c -> c.starredMobs, saved((c, on) -> c.starredMobs = on)));

	/** A module switch that sets one config field and saves the file. */
	private static BiConsumer<FemboyClientConfig, Boolean> saved(BiConsumer<FemboyClientConfig, Boolean> set) {
		return (c, on) -> {
			set.accept(c, on);
			c.save();
		};
	}

	/** The module selected last time, so the menu reopens on it (also after Esc on one of the big screens). */
	private static int selected = 0;

	/** Something clickable drawn this frame. A right click runs {@code right} when there is one. */
	private record Hit(int x0, int y0, int x1, int y1, Runnable left, Runnable right) {
	}

	/** A slider: its name, the knob's left and right ends, the values at them, and where a new value goes. */
	private record Slider(String name, int x0, int x1, int min, int max, int step, IntConsumer set) {
		void setFrom(double x) {
			double t = Math.max(0, Math.min(1, (x - x0) / Math.max(1, x1 - x0)));
			int value = min + (int) Math.round(t * (max - min) / step) * step;
			set.accept(Math.max(min, Math.min(max, value)));
		}
	}

	private record SliderHit(int x0, int y0, int x1, int y1, Slider slider) {
	}

	private final List<Hit> hits = new ArrayList<>();
	private final List<SliderHit> sliderHits = new ArrayList<>();
	private Slider dragging;
	private final Map<String, int[]> named = new HashMap<>();
	private final Map<String, UiFont.Label> labels = new HashMap<>();
	private final Map<String, List<FormattedCharSequence>> wrapped = new HashMap<>();

	private long openedAt;
	private int winX0, winY0, winX1, winY1;
	private int listX0, listX1, listY0, listY1;
	private int paneX0, paneX1, paneY0, paneY1;
	private int listScroll, paneScroll, paneContent;
	/** The tab shown in the list: an index into {@link #TABS}. */
	private static int tab = 0;

	/** The key button waiting for a key press (its name), or null; and where the pressed key goes. */
	private String bindingKey;
	private IntConsumer bindingSet;

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
		listY0 = winY0 + Theme.HEADER_HEIGHT + Theme.GAP + TAB_H + Theme.GAP;
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
		sliderHits.clear();
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

	/** The modules under each tab, as indexes into {@link #MODULES}, in list order. */
	private static final List<List<Integer>> SHOWN = java.util.stream.IntStream.range(0, TABS.length)
			.mapToObj(t -> java.util.stream.IntStream.range(0, MODULES.size()).filter(i -> t == 0 || DUNGEON_MODULES.contains(i))
					.boxed().toList())
			.toList();

	/** The modules under the tab shown now, as indexes into {@link #MODULES}, in list order. */
	private static List<Integer> shown() {
		return SHOWN.get(tab);
	}

	/** The All / Dungeons tabs above the list. */
	private void drawTabs() {
		int y0 = winY0 + Theme.HEADER_HEIGHT + Theme.GAP, y1 = y0 + TAB_H;
		int w = (listX1 - Theme.GAP - listX0 - 4 * (TABS.length - 1)) / TABS.length;
		for (int i = 0; i < TABS.length; i++) {
			int x0 = listX0 + i * (w + 4), x1 = x0 + w;
			boolean hover = UiDraw.inside(mouseX, mouseY, x0, y0, x1, y1);
			UiDraw.accentCard(g, x0, y0, x1, y1, Theme.ROW_RADIUS, Theme.ACCENT, i == tab, hover);
			UiFont.Label text = label(TABS[i]);
			UiDraw.label(g, font, text, x0 + (w - text.width()) / 2, UiFont.centeredY(UiFont.Face.LABEL, y0, TAB_H),
					i == tab || hover ? Theme.TEXT : Theme.TEXT_LABEL);
			int index = i;
			hit(x0, y0, x1, y1, winX0, winY0, winX1, winY1, () -> showTab(index), null);
			named.put("tab:" + TABS[i], new int[] {x0, y0, x1, y1});
		}
	}

	/** Shows a tab, and selects its first module if the selected one is under another tab. */
	private void showTab(int index) {
		if (tab != index) {
			tab = index;
			listScroll = 0;
			if (!shown().contains(selected)) {
				select(shown().get(0));
			}
		}
	}

	private void drawList() {
		drawTabs();
		List<Integer> shown = shown();
		int content = shown.size() * ROW_H;
		listScroll = clamp(listScroll, 0, Math.max(0, content - (listY1 - listY0)));
		FemboyClientConfig config = FemboyClientConfig.get();

		UiDraw.beginClip(g, listX0, listY0, listX1, listY1);
		for (int i : shown) {
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
			case HANDMAN -> handManSettings(config);
			case COPY_CHAT -> copyChatSettings(config);
			case HIDE_PLAYERS -> hidePlayersSettings(config);
			case CHEST_ESP -> chestEspSettings(config);
			case GHOST_BLOCKS -> ghostBlocksSettings(config);
			case KEY_HIGHLIGHT -> keyHighlightSettings(config);
			case TEAMMATES -> teammatesSettings(config);
			case SECRET_HITBOXES -> secretHitboxesSettings(config);
			case PUZZLES -> puzzleSettings(config);
			case CAMERA -> cameraSettings(config);
			case SERVER_DISPLAY -> serverDisplaySettings(config);
			case CHAT_COMMANDS -> chatCommandsSettings(config);
			case NO_DEBUFF -> noDebuffSettings(config);
			case PET_SWAPPER -> petSwapperSettings(config);
			case STARRED_MOBS -> starredMobsSettings(config);
			default -> {
			}
		}

		paneContent = cy + paneScroll - top + 6;
		UiDraw.endClip(g);
		scrollbar(paneX1 + Theme.PADDING / 2, top, paneY1, paneContent, paneScroll);
	}

	private void mansionSettings(FemboyClientConfig config) {
		section("Map");
		slider("Map size", config.hudSize + " px", config.hudSize, HollowsHud.MIN_SIZE, HollowsHud.MAX_SIZE, 10,
				v -> config.hudSize = v);
		switchRow("Render boost", "Biggest view distance in the Crystal Hollows, so every chunk is scanned.", config.scanRenderBoost,
				() -> {
					config.scanRenderBoost = !config.scanRenderBoost;
					config.save();
				});
		buttons(new String[] {"Edit map position", "Reset position"},
				new Runnable[] {() -> minecraft.setScreenAndShow(new HollowsMapScreen().fromMenu()), () -> HollowsMap.reset(config)});
		info("Position", config.hudX + ", " + config.hudY);
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
		slider("Time between clicks", config.clickerMs + " ms", config.clickerMs, LoopedClicker.MIN_MS, LoopedClicker.MAX_MS, 10,
				v -> config.clickerMs = v);
		chips("Button", new String[] {"Left", "Right"}, config.clickerRightClick ? 1 : 0, choice -> {
			config.clickerRightClick = choice == 1;
			config.save();
		});
		keyButton("Start/Stop key", config.clickerKey, v -> config.clickerKey = v);
		paragraph("Click to set the key, then press it (Esc cancels). Right click clears it. The spot it clicks is set with "
				+ "\"Set Location\" in the controls on a container.", Theme.TEXT_DIM);
	}

	private void particleSettings() {
		section("Now");
		info("Particles hidden this game", String.format("%,d", NoParticles.blockedCount()));
	}

	private void handManSettings(FemboyClientConfig config) {
		section("Size");
		slider("Size", HandMan.percent(config.handSize), config.handSize, HandMan.SIZE, v -> config.handSize = v);
		section("Position");
		slider("X", HandMan.offset(config.handX), config.handX, HandMan.X, v -> config.handX = v);
		slider("Y", HandMan.offset(config.handY), config.handY, HandMan.Y, v -> config.handY = v);
		slider("Z", HandMan.offset(config.handZ), config.handZ, HandMan.Z, v -> config.handZ = v);
		section("Rotation");
		slider("Yaw", config.handYaw + "°", config.handYaw, HandMan.ROTATION, v -> config.handYaw = v);
		slider("Pitch", config.handPitch + "°", config.handPitch, HandMan.ROTATION, v -> config.handPitch = v);
		slider("Roll", config.handRoll + "°", config.handRoll, HandMan.ROTATION, v -> config.handRoll = v);
		section("Swing");
		slider("Speed", HandMan.percent(config.handSpeed), config.handSpeed, HandMan.SPEED, v -> config.handSpeed = v);
		configSwitch("Ignore Haste", "The speed above is used as it is, without Haste or Mining Fatigue changing it.",
				config.handIgnoreHaste, (c, on) -> c.handIgnoreHaste = on);
		configSwitch("Block Hit", "The 1.7 look: your item keeps swinging while you block.",
				config.handBlockHit, (c, on) -> c.handBlockHit = on);
		configSwitch("No Equip Reset", "No dip down and back up when you switch items.",
				config.handNoEquipReset, (c, on) -> c.handNoEquipReset = on);
		configSwitch("No Swing", "Your item never swings forward.", config.handNoSwing, (c, on) -> c.handNoSwing = on);
		configSwitch("No Block", "Blocking does not raise your item.", config.handNoBlock, (c, on) -> c.handNoBlock = on);
		buttons(new String[] {"Reset"}, new Runnable[] {() -> HandMan.reset(config)});
		paragraph("Reset puts every setting above back to the game's own look.", Theme.TEXT_DIM);
	}

	private void copyChatSettings(FemboyClientConfig config) {
		section("Settings");
		keyButton("Keybind", config.copyChatKey, v -> config.copyChatKey = v);
		paragraph("Hold to copy message with color codes.", Theme.TEXT_DIM);
		configSwitch("Send Message", "Sends the message you copied in chat.", config.copyChatSendMessage,
				(c, on) -> c.copyChatSendMessage = on);
	}

	private void hidePlayersSettings(FemboyClientConfig config) {
		section("Settings");
		configSwitch("Hide all", "Hides all players, regardless of distance.", config.hidePlayersAll, (c, on) -> c.hidePlayersAll = on);
		slider("Distance", String.format("%.1f blocks", config.hidePlayersDistance / 10.0), config.hidePlayersDistance,
				HidePlayers.DISTANCE, v -> config.hidePlayersDistance = v);
		paragraph("The number of blocks away to hide players.", Theme.TEXT_DIM);
		configSwitch("Click Through", "Allows clicking through players.", config.hidePlayersClickThrough,
				(c, on) -> c.hidePlayersClickThrough = on);
		configSwitch("Only Devs", "Only hides players when standing at ss or fourth device.", config.hidePlayersOnlyDevs,
				(c, on) -> c.hidePlayersOnlyDevs = on);
	}

	private void chestEspSettings(FemboyClientConfig config) {
		section("Settings");
		configSwitch("Only Dungeon", "Only shows chests in dungeons.", config.chestEspOnlyDungeon, (c, on) -> c.chestEspOnlyDungeon = on);
		configSwitch("Only Crystal Hollows", "Only shows chests in the Crystal Hollows.", config.chestEspOnlyHollows,
				(c, on) -> c.chestEspOnlyHollows = on);
		configSwitch("Hide Clicked", "Stops showing a chest once you have right clicked it.", config.chestEspHideClicked,
				(c, on) -> c.chestEspHideClicked = on);
		chips("Render Mode", new String[] {"Chams", "Outline"}, config.chestEspMode, choice -> {
			config.chestEspMode = choice;
			config.save();
		});
		int color = ChestEsp.colorIndex(config.chestEspColor);
		chips("Color", java.util.Arrays.copyOfRange(ChestEsp.COLOR_NAMES, 0, 4), color, choice -> {
			config.chestEspColor = ChestEsp.COLORS[choice];
			config.save();
		});
		chips("", java.util.Arrays.copyOfRange(ChestEsp.COLOR_NAMES, 4, 8), color - 4, choice -> {
			config.chestEspColor = ChestEsp.COLORS[choice + 4];
			config.save();
		});
	}

	private void ghostBlocksSettings(FemboyClientConfig config) {
		section("Settings");
		keyButton("Ghost block Keybind", config.ghostBlocksKey, v -> config.ghostBlocksKey = v);
		paragraph("Makes blocks you're looking at disappear. Click the key button, then press the key (Esc cancels). Right click "
				+ "clears it.", Theme.TEXT_DIM);
		slider("Speed", config.ghostBlocksSpeed + " ms", config.ghostBlocksSpeed, GhostBlocks.SPEED, v -> config.ghostBlocksSpeed = v);
		configSwitch("Ghost Skulls", "If enabled skulls will also be turned into ghost blocks.", config.ghostBlocksSkulls,
				(c, on) -> c.ghostBlocksSkulls = on);
		slider("Range", String.format("%.1f", config.ghostBlocksRange / 10.0), config.ghostBlocksRange, GhostBlocks.RANGE,
				v -> config.ghostBlocksRange = v);
		paragraph("Maximum range at which ghost blocks will be created.", Theme.TEXT_DIM);
		configSwitch("Only In Dungeon", "Will only work inside of a dungeon.", config.ghostBlocksOnlyDungeon,
				(c, on) -> c.ghostBlocksOnlyDungeon = on);
		configSwitch("F7 Ghost blocks", "Ghost blocks the shortcuts in the Floor 7 boss, by phase.", config.ghostBlocksF7,
				(c, on) -> c.ghostBlocksF7 = on);
	}

	private void teammatesSettings(FemboyClientConfig config) {
		section("Settings");
		configSwitch("Show Class", "Shows the class of the teammate.", config.teammatesShowClass, (c, on) -> c.teammatesShowClass = on);
		configSwitch("Outline", "Highlights teammates with an outline.", config.teammatesOutline, (c, on) -> c.teammatesOutline = on);
		configSwitch("Name", "Highlights teammates with a name tag.", config.teammatesName, (c, on) -> c.teammatesName = on);
		configSwitch("When Visible", "Highlights teammates only when they are visible.", config.teammatesWhenVisible,
				(c, on) -> c.teammatesWhenVisible = on);
		configSwitch("In Boss", "Highlights teammates in boss rooms.", config.teammatesInBoss, (c, on) -> c.teammatesInBoss = on);
		section("Now");
		info("Teammates found", Integer.toString(Dungeons.teammates().size()));
	}

	private void secretHitboxesSettings(FemboyClientConfig config) {
		section("Settings");
		configSwitch("Lever", "Levers get a full block hitbox.", config.secretHitboxesLever, (c, on) -> c.secretHitboxesLever = on);
		configSwitch("Button", "Buttons cover the whole face of their block.", config.secretHitboxesButton,
				(c, on) -> c.secretHitboxesButton = on);
		configSwitch("Essence", "Wither essence skulls get a full block hitbox.", config.secretHitboxesEssence,
				(c, on) -> c.secretHitboxesEssence = on);
		configSwitch("Chests", "Chests get a full block hitbox.", config.secretHitboxesChests, (c, on) -> c.secretHitboxesChests = on);
	}

	private void puzzleSettings(FemboyClientConfig config) {
		section("Now");
		info("Room", Dungeons.roomName());
		section("Water");
		configSwitch("Water Board", "Shows you the solution to the water puzzle.", config.puzzleWater, (c, on) -> c.puzzleWater = on);
		configSwitch("Show Order", "Shows the order of the levers to click.", config.puzzleWaterOrder, (c, on) -> c.puzzleWaterOrder = on);
		configSwitch("Show Tracer", "Shows a tracer to the next lever.", config.puzzleWaterTracer, (c, on) -> c.puzzleWaterTracer = on);
		buttons(new String[] {"Reset water"}, new Runnable[] {WaterSolver::reset});
		section("Maze");
		configSwitch("Teleport Maze", "Shows you the solution for the TP maze puzzle.", config.puzzleTpMaze, (c, on) -> c.puzzleTpMaze = on);
		configSwitch("Solution through walls", "Renders the final solution through walls.", config.puzzleTpMazeThroughWalls,
				(c, on) -> c.puzzleTpMazeThroughWalls = on);
		buttons(new String[] {"Reset maze"}, new Runnable[] {TPMaze::reset});
		section("Tic Tac Toe");
		configSwitch("Tic Tac Toe", "Shows you the solution for the TTT puzzle.", config.puzzleTtt, (c, on) -> c.puzzleTtt = on);
		section("Ice Fill");
		configSwitch("Ice Fill Solver", "Solver for the ice fill puzzle.", config.puzzleIceFill, (c, on) -> c.puzzleIceFill = on);
		buttons(new String[] {"Reset ice fill"}, new Runnable[] {IceFillSolver::reset});
		section("Blaze");
		configSwitch("Blaze Solver", "Boxes the blazes in the order to kill them.", config.puzzleBlaze, (c, on) -> c.puzzleBlaze = on);
		configSwitch("Blaze Solver Next Line", "Draws a line from each blaze to the next.", config.puzzleBlazeLineNext,
				(c, on) -> c.puzzleBlazeLineNext = on);
		slider("Blaze Solver Lines", Integer.toString(config.puzzleBlazeLines), config.puzzleBlazeLines, PuzzleSolvers.BLAZE_LINES,
				v -> config.puzzleBlazeLines = v);
		chips("Style", PuzzleSolvers.BLAZE_STYLES, config.puzzleBlazeStyle, choice -> {
			config.puzzleBlazeStyle = choice;
			config.save();
		});
	}

	private void cameraSettings(FemboyClientConfig config) {
		section("Settings");
		configSwitch("No Front Camera", "Skips the third person view from the front.", config.cameraNoFront, (c, on) -> c.cameraNoFront = on);
		configSwitch("Camera Clip", "The third person camera goes through blocks.", config.cameraClip, (c, on) -> c.cameraClip = on);
		slider("Distance", String.format("%.1f", config.cameraDistance / 10.0), config.cameraDistance, CameraTweaks.DISTANCE,
				v -> config.cameraDistance = v);
		section("Freelook");
		chips("Type", new String[] {"Hold", "Toggle"}, config.freelookToggle ? 1 : 0, choice -> {
			config.freelookToggle = choice == 1;
			config.save();
		});
		keyButton("Freelook Key", config.freelookKey, v -> config.freelookKey = v);
		paragraph("Keybind to toggle/ hold for freelook.", Theme.TEXT_DIM);
	}

	private void serverDisplaySettings(FemboyClientConfig config) {
		section("Settings");
		configSwitch("Ping", "Shows your ping.", config.serverDisplayPing, (c, on) -> c.serverDisplayPing = on);
		configSwitch("TPS", "Shows the server's TPS.", config.serverDisplayTps, (c, on) -> c.serverDisplayTps = on);
		configSwitch("FPS", "Shows your FPS.", config.serverDisplayFps, (c, on) -> c.serverDisplayFps = on);
		chips("Style", new String[] {"Row", "Stacked"}, config.serverDisplayStacked, choice -> {
			config.serverDisplayStacked = choice;
			config.save();
		});
		section("Display");
		slider("X", config.serverDisplayX + " px", config.serverDisplayX, 0, Math.max(0, width - 20), 1, v -> config.serverDisplayX = v);
		slider("Y", config.serverDisplayY + " px", config.serverDisplayY, 0, Math.max(0, height - 10), 1, v -> config.serverDisplayY = v);
		slider("Scale", config.serverDisplayScale + "%", config.serverDisplayScale, ServerDisplay.SCALE, v -> config.serverDisplayScale = v);
		info("Now", String.format("Ping %dms · TPS %.1f", (int) ServerStats.ping(), ServerStats.tps()));
	}

	private void chatCommandsSettings(FemboyClientConfig config) {
		section("Settings");
		configSwitch("Party commands", "Toggles chat commands in party chat.", config.chatCommandsParty, (c, on) -> c.chatCommandsParty = on);
		configSwitch("Guild commands", "Toggles chat commands in guild chat.", config.chatCommandsGuild, (c, on) -> c.chatCommandsGuild = on);
		configSwitch("Private commands", "Toggles chat commands in private chat.", config.chatCommandsPrivate,
				(c, on) -> c.chatCommandsPrivate = on);
		section("Commands");
		for (var entry : ChatCommands.COMMANDS.entrySet()) {
			String command = entry.getValue();
			configSwitch(entry.getKey(), "!" + command, !config.chatCommandsOff.contains(command), (c, on) -> {
				if (on) {
					c.chatCommandsOff.remove(command);
				} else {
					c.chatCommandsOff.add(command);
				}
			});
		}
		section("Blacklist");
		info("Players", Integer.toString(config.chatCommandsBlacklist.size()));
		paragraph("/blacklist add <name>, /blacklist remove <name>, /blacklist clear, /blacklist list. Their commands are ignored.",
				Theme.TEXT_DIM);
	}

	private void noDebuffSettings(FemboyClientConfig config) {
		section("Settings");
		configSwitch("No Blindness", "Disables blindness.", config.noDebuffBlindness, (c, on) -> c.noDebuffBlindness = on);
		configSwitch("No Portal Effect", "Disables the nether portal overlay.", config.noDebuffPortal, (c, on) -> c.noDebuffPortal = on);
		configSwitch("No Shield Particle", "Removes purple particles and wither impact hearts.", config.noDebuffShieldParticles,
				(c, on) -> c.noDebuffShieldParticles = on);
		configSwitch("No Water FOV", "Disable FOV change in water.", config.noDebuffWaterFov, (c, on) -> c.noDebuffWaterFov = on);
		configSwitch("No Fire Overlay", "Disable Fire overlay on screen.", config.noDebuffFire, (c, on) -> c.noDebuffFire = on);
		configSwitch("No Push", "Prevents from being pushed out of blocks.", config.noDebuffPush, (c, on) -> c.noDebuffPush = on);
		configSwitch("See Through Blocks", "Makes blocks transparent.", config.noDebuffSeeThrough, (c, on) -> c.noDebuffSeeThrough = on);
	}

	private void starredMobsSettings(FemboyClientConfig config) {
		section("Settings");
		chips("Render Mode", new String[] {"Chams", "Outline"}, config.starredMobsMode, choice -> {
			config.starredMobsMode = choice;
			config.save();
		});
		int color = ChestEsp.colorIndex(config.starredMobsColor);
		chips("Color", java.util.Arrays.copyOfRange(ChestEsp.COLOR_NAMES, 0, 4), color, choice -> {
			config.starredMobsColor = ChestEsp.COLORS[choice];
			config.save();
		});
		chips("", java.util.Arrays.copyOfRange(ChestEsp.COLOR_NAMES, 4, 8), color - 4, choice -> {
			config.starredMobsColor = ChestEsp.COLORS[choice + 4];
			config.save();
		});
		section("Now");
		info("Starred mobs shown", Integer.toString(StarredMobs.count()));
		paragraph("Chams: a see-through box over the mob. Outline: the game's glowing outline. Both show through walls.",
				Theme.TEXT_DIM);
	}

	private void petSwapperSettings(FemboyClientConfig config) {
		section("Now");
		info("Heat", PetSwapper.heatText());
		info("Pet out", PetSwapper.activeText());
		info("Swaps back to", config.petSwapBack.isBlank() ? "not known yet" : config.petSwapBack);
		info("Swaps this game", Integer.toString(PetSwapper.swaps()));
		section("How it works");
		paragraph("Enter the Magma Fields → Bal. Heat down → the pet you had out before Bal. Nothing happens when that pet is "
				+ "already out.",
				Theme.TEXT);
		paragraph("The pet to swap back to is the last pet other than Bal you had out. To change it, summon the other pet "
				+ "yourself once.", Theme.TEXT_DIM);
	}

	private void keyHighlightSettings(FemboyClientConfig config) {
		section("Settings");
		slider("Thickness", String.format("%.1f", config.keyHighlightThickness / 10.0), config.keyHighlightThickness,
				KeyHighlight.THICKNESS, v -> config.keyHighlightThickness = v);
		paragraph("The thickness of the box.", Theme.TEXT_DIM);
	}

	/** A switch for one setting, saved when clicked. */
	private void configSwitch(String name, String hint, boolean on, BiConsumer<FemboyClientConfig, Boolean> set) {
		switchRow(name, hint, on, () -> {
			FemboyClientConfig config = FemboyClientConfig.get();
			set.accept(config, !on);
			config.save();
		});
	}

	/** "Name      [key]": click it, then press a key (Esc cancels); right click clears it. */
	private void keyButton(String name, int key, IntConsumer set) {
		String value = name.equals(bindingKey) ? "Press a key…" : key < 0 ? "not set"
				: InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
		labeledButton(name, value, () -> {
			if (name.equals(bindingKey)) {
				bindingKey = null;
			} else {
				bindingKey = name;
				bindingSet = set;
			}
		}, () -> {
			bindingKey = null;
			set.accept(-1);
			FemboyClientConfig.get().save();
		});
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

	/** A slider for a HandMan number: its range and step come from the setting. */
	private void slider(String name, String value, int current, HandMan.Range range, IntConsumer set) {
		slider(name, value, current, range.min(), range.max(), range.step(), set);
	}

	/**
	 * "Name ............ value" with a slider under it, the full width of the pane. Click or drag anywhere on it; the value
	 * snaps to {@code step} and is saved when the mouse button is let go.
	 */
	private void slider(String name, String value, int current, int min, int max, int step, IntConsumer set) {
		UiDraw.text(g, font, UiFont.sequence(name, UiFont.Face.STRONG), paneX0, UiFont.topY(UiFont.Face.STRONG, cy), Theme.TEXT);
		UiDraw.text(g, font, UiFont.sequence(value, UiFont.Face.STRONG), paneX1 - UiFont.width(font, value, UiFont.Face.STRONG),
				UiFont.topY(UiFont.Face.STRONG, cy), Theme.ACCENT_LIGHT);
		cy += UiFont.capHeight(UiFont.Face.STRONG) + 6;

		int x0 = paneX0 + SLIDER_KNOB / 2, x1 = paneX1 - SLIDER_KNOB / 2, mid = cy + SLIDER_H / 2;
		Slider slider = new Slider(name, x0, x1, min, max, step, set);
		boolean hot = dragging != null ? dragging.name().equals(name) : UiDraw.inside(mouseX, mouseY, paneX0, cy, paneX1, cy + SLIDER_H) && inPane();
		int knobX = x0 + Math.round((x1 - x0) * (float) (clamp(current, min, max) - min) / Math.max(1, max - min));
		UiDraw.pill(g, x0 - 2, mid - 2, x1 + 2, mid + 2, Colors.withAlpha(Theme.SWITCH_OFF, hot ? 235 : 195));
		UiDraw.pill(g, x0 - 2, mid - 2, knobX + 2, mid + 2, Colors.withAlpha(Theme.ACCENT, hot ? 255 : 215));
		int r = SLIDER_KNOB / 2;
		UiDraw.pill(g, knobX - r, mid - r, knobX + r, mid + r, Colors.withAlpha(Theme.TEXT, hot ? 255 : 225));

		int top = Math.max(cy, paneY0), bottom = Math.min(cy + SLIDER_H, paneY1);     // cut to the visible pane, like hit()
		if (top < bottom) {
			sliderHits.add(new SliderHit(paneX0, top, paneX1, bottom, slider));
		}
		named.put("slider:" + name, new int[] {paneX0, cy, paneX1, cy + SLIDER_H});
		cy += SLIDER_H + LINE_GAP + 2;
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
		UiDraw.accentCard(g, x0, cy, x1, cy + CONTROL_H, Theme.ROW_RADIUS, Theme.ACCENT, name.equals(bindingKey), hover);
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
		if (event.button() == 0) {
			for (SliderHit hit : sliderHits) {
				if (UiDraw.inside(event.x(), event.y(), hit.x0(), hit.y0(), hit.x1(), hit.y1())) {
					bindingKey = null;
					dragging = hit.slider();
					dragging.setFrom(event.x());
					return true;
				}
			}
		}
		if (event.button() == 0 || event.button() == 1) {
			// The switch is drawn on top of its row, and was added first, so it wins.
			for (Hit hit : hits) {
				Runnable action = event.button() == 0 ? hit.left() : hit.right();
				if (action != null && UiDraw.inside(event.x(), event.y(), hit.x0(), hit.y0(), hit.x1(), hit.y1())) {
					// A click anywhere but the key button stops waiting for a key.
					int[] keyButton = bindingKey == null ? null : named.get("button:" + bindingKey);
					if (keyButton == null || !UiDraw.inside(event.x(), event.y(), keyButton[0], keyButton[1], keyButton[2], keyButton[3])) {
						bindingKey = null;
					}
					action.run();
					return true;
				}
			}
		}
		bindingKey = null;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging != null) {
			dragging.setFrom(event.x());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			FemboyClientConfig.get().save();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public void removed() {
		if (dragging != null) {
			dragging = null;
			FemboyClientConfig.get().save();
		}
		super.removed();
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
		if (bindingKey != null) {
			bindingKey = null;
			if (!event.isEscape()) {
				bindingSet.accept(event.key());
				FemboyClientConfig.get().save();
			}
			return true;
		}
		List<Integer> shown = shown();
		int at = shown.indexOf(selected);
		if (event.key() == InputConstants.KEY_UP) {
			select(shown.get(Math.max(0, at - 1)));
			return true;
		}
		if (event.key() == InputConstants.KEY_DOWN) {
			select(shown.get(Math.min(shown.size() - 1, at + 1)));
			return true;
		}
		return super.keyPressed(event);
	}

	private void select(int index) {
		if (index != selected) {
			selected = index;
			paneScroll = 0;
			bindingKey = null;
		}
		if (!shown().contains(index)) {
			tab = 0;                 // a module that is not under this tab: show it under All
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

	/** The module selected now (index into the list: {@link #MANSION} to {@link #STARRED_MOBS}). */
	public int selected() {
		return selected;
	}

	/** The bounds {x0, y0, x1, y1} of the row at this index in the list (clicking it selects the module). */
	public int[] rowBounds(int index) {
		int position = shown().indexOf(index);
		int y0 = position < 0 ? -10_000 : listY0 + position * ROW_H - listScroll;      // under the other tab: off screen
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
	 * "switch:Render boost", "slider:Map size", "chip:Right", "pane-switch".
	 */
	public int[] controlBounds(String name) {
		return named.get(name);
	}
}
