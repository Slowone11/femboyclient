package com.femboyclient;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The G menu: every module of the mod, each with an ON/OFF switch and (for most of them) its own screen for the rest of its
 * settings. A small dot by the title shows whether the website answers: green if it does, red if it does not. It is not a
 * button; the live view itself has no switch and is always on, silently.
 *
 * Each module's own screen has a small "◂ Menu" tab in its corner that comes back here, so switching between modules never
 * needs Esc and a fresh press of G.
 */
public final class ClientMenuScreen extends Screen {

	private static final int WIDTH = 360, ROW_HEIGHT = 46;
	private static final int SWITCH_W = 44, SWITCH_H = 20;
	private static final int LIVE_DOT_SIZE = 16;

	/**
	 * One row: its name, a one-line description, whether it is on, how to switch it, and the screen to open when the row
	 * (outside the switch) is clicked — or null for a row with nothing to configure beyond the switch itself.
	 */
	private record ModuleRow(String name, String description, Function<FemboyClientConfig, Boolean> isOn,
			BiConsumer<FemboyClientConfig, Boolean> setOn, Supplier<Screen> open) {
	}

	private static final List<ModuleRow> MODULES = List.of(
			new ModuleRow("MansionFinder", "Finds the Mansion in the Crystal Hollows and shows it on a map in the corner of your screen.",
					c -> c.hud, (c, on) -> {
						c.hud = on;
						c.save();
					}, HollowsMapScreen::new),
			new ModuleRow("Scanner", "Reports who is in your lobby to the website.",
					c -> c.scanner, (c, on) -> Scanner.apply(c, on, c.scannerWorld), ScannerScreen::new),
			new ModuleRow("Auto Conversation", "Clicks the right option of an NPC dialog for you.",
					c -> c.npcAuto, (c, on) -> {
						c.npcAuto = on;
						c.save();
					}, NpcAutoScreen::new),
			new ModuleRow("Lobby Networth", "Who is in your lobby, and what the website knows about them.",
					c -> c.lobbyNetworth, (c, on) -> {
						c.lobbyNetworth = on;
						c.save();
					}, PlayerInfoKey::playersScreen),
			new ModuleRow("Chat Viewer", "Records what other players say while the scanner is on, for the website's Chat tab.",
					c -> c.playerChat, (c, on) -> {
						c.playerChat = on;
						c.save();
					}, null),
			new ModuleRow("Frienemys", "Friend or enemy tags on players, shown top-left when they're in your lobby.",
					c -> c.frienemys, (c, on) -> {
						c.frienemys = on;
						c.save();
					}, FrienemiesScreen::new),
			new ModuleRow("Fullbright", "Forces maximum brightness, past what the options screen's gamma slider allows.",
					c -> c.fullbright, (c, on) -> {
						c.fullbright = on;
						c.save();
					}, null),
			new ModuleRow("Keepsprint", "Sprints while you move in any direction, not just forward like vanilla.",
					c -> c.keepsprint, (c, on) -> {
						c.keepsprint = on;
						c.save();
					}, null),
			new ModuleRow("Looped Clicker", "Shows click controls whenever a container is open: where it clicks, how fast, left or right — it keeps clicking until you toggle it off.",
					c -> c.clicker, (c, on) -> {
						c.clicker = on;
						if (!on) {
							LoopedClicker.stop();
						}
						c.save();
					}, null));

	private long openedAt;
	private int winX0, winY0, winX1, winY1;
	private int liveDotX, liveDotY;
	private final int[] rowY0 = new int[MODULES.size()];
	private final int[] rowY1 = new int[MODULES.size()];
	private final int[] switchX0 = new int[MODULES.size()];
	private final int[] switchX1 = new int[MODULES.size()];

	private FormattedCharSequence titleText;
	private FormattedCharSequence[] nameTexts;
	private List<FormattedCharSequence>[] descLines;

	public ClientMenuScreen() {
		super(Component.literal("Femboy Client"));
	}

	@Override
	@SuppressWarnings("unchecked")
	protected void init() {
		if (openedAt == 0) {
			openedAt = System.nanoTime();
		}
		int windowHeight = Theme.HEADER_HEIGHT + MODULES.size() * ROW_HEIGHT + Theme.PADDING;
		winX0 = (width - WIDTH) / 2;
		winY0 = (height - windowHeight) / 2;
		winX1 = winX0 + WIDTH;
		winY1 = winY0 + windowHeight;
		liveDotX = winX1 - Theme.PADDING - 6;
		liveDotY = winY0 + Theme.HEADER_HEIGHT / 2;

		titleText = UiFont.sequence("Femboy Client", UiFont.Face.VALUE);
		nameTexts = new FormattedCharSequence[MODULES.size()];
		descLines = new List[MODULES.size()];
		int left = winX0 + Theme.PADDING, right = winX1 - Theme.PADDING;
		int textWidth = right - left - 10 - SWITCH_W - Theme.GAP;
		int y = winY0 + Theme.HEADER_HEIGHT;
		for (int i = 0; i < MODULES.size(); i++) {
			ModuleRow module = MODULES.get(i);
			nameTexts[i] = UiFont.sequence(module.name(), UiFont.Face.STRONG);
			descLines[i] = font.split(UiFont.component(module.description(), UiFont.Face.BODY), textWidth);
			rowY0[i] = y + 3;
			rowY1[i] = y + ROW_HEIGHT - 3;
			switchX1[i] = right;
			switchX0[i] = right - SWITCH_W;
			y += ROW_HEIGHT;
		}
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
		UiDraw.setScale(minecraft.getWindow().getGuiScale());
		float opened = Ease.inOutCubic((System.nanoTime() - openedAt) / 1_000_000f / Theme.OPEN_MS);
		UiDraw.setFade(opened);

		UiDraw.shadow(graphics, winX0, winY0, winX1, winY1, Theme.PANEL_RADIUS, Theme.SHADOW_SPREAD);
		UiDraw.glass(graphics, winX0, winY0, winX1, winY1, Theme.PANEL_RADIUS);
		UiDraw.divider(graphics, winX0 + 1, winY0 + Theme.HEADER_HEIGHT, winX1 - 1);
		UiDraw.glowDot(graphics, winX0 + Theme.PADDING + 5, winY0 + Theme.HEADER_HEIGHT / 2, 3, Theme.ACCENT);
		UiDraw.text(graphics, font, titleText, winX0 + Theme.PADDING + 18, UiFont.centeredY(UiFont.Face.VALUE, winY0, Theme.HEADER_HEIGHT), Theme.TEXT);

		Boolean connected = FemboyClientMod.SERVER.connected();
		UiDraw.glowDot(graphics, liveDotX, liveDotY, 3, connected == null ? Theme.TEXT_DIM : connected ? Theme.ACCENT : Theme.TEXT_ERROR);

		FemboyClientConfig config = FemboyClientConfig.get();
		int left = winX0 + Theme.PADDING, right = winX1 - Theme.PADDING;
		for (int i = 0; i < MODULES.size(); i++) {
			ModuleRow module = MODULES.get(i);
			boolean hover = UiDraw.inside(mouseX, mouseY, left, rowY0[i], right, rowY1[i]);
			UiDraw.hoverCard(graphics, left, rowY0[i], right, rowY1[i], Theme.ROW_RADIUS, hover);
			UiDraw.text(graphics, font, nameTexts[i], left + 10, UiFont.topY(UiFont.Face.STRONG, rowY0[i] + 5), hover ? Theme.ACCENT_LIGHT : Theme.TEXT);
			int textY = rowY0[i] + 5 + UiFont.capHeight(UiFont.Face.STRONG) + 5;
			for (FormattedCharSequence line : descLines[i]) {
				UiDraw.text(graphics, font, line, left + 10, textY, Theme.TEXT_DIM);
				textY += Theme.LABEL_LINE;
			}

			int sy0 = (rowY0[i] + rowY1[i] - SWITCH_H) / 2, sy1 = sy0 + SWITCH_H;
			boolean on = module.isOn().apply(config);
			boolean switchHover = UiDraw.inside(mouseX, mouseY, switchX0[i], sy0, switchX1[i], sy1);
			UiDraw.toggleSwitch(graphics, switchX0[i], sy0, switchX1[i], sy1, on, switchHover);
		}
		UiDraw.setFade(1f);
	}

	private int switchY0(int index) {
		return (rowY0[index] + rowY1[index] - SWITCH_H) / 2;
	}

	/** The bounds {x0, y0, x1, y1} of the row at this index (its clickable, navigable area), for the screenshot test. */
	public int[] rowBounds(int index) {
		int left = winX0 + Theme.PADDING, right = winX1 - Theme.PADDING;
		return new int[] {left, rowY0[index], right, rowY1[index]};
	}

	/** The bounds {x0, y0, x1, y1} of the ON/OFF switch at this row index, for the screenshot test. */
	public int[] switchBounds(int index) {
		int sy0 = switchY0(index);
		return new int[] {switchX0[index], sy0, switchX1[index], sy0 + SWITCH_H};
	}

	/** The bounds {x0, y0, x1, y1} of the live status dot, for the screenshot test. */
	public int[] liveDotBounds() {
		int half = LIVE_DOT_SIZE / 2;
		return new int[] {liveDotX - half, liveDotY - half, liveDotX + half, liveDotY + half};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			int left = winX0 + Theme.PADDING, right = winX1 - Theme.PADDING;
			FemboyClientConfig config = FemboyClientConfig.get();
			for (int i = 0; i < MODULES.size(); i++) {
				ModuleRow module = MODULES.get(i);
				int sy0 = switchY0(i);
				if (UiDraw.inside(event.x(), event.y(), switchX0[i], sy0, switchX1[i], sy0 + SWITCH_H)) {
					module.setOn().accept(config, !module.isOn().apply(config));
					return true;
				}
				if (UiDraw.inside(event.x(), event.y(), left, rowY0[i], right, rowY1[i])) {
					if (module.open() != null) {
						minecraft.setScreenAndShow(module.open().get());
					} else {
						// Nothing else to configure: the whole row toggles it, so there is no dead click.
						module.setOn().accept(config, !module.isOn().apply(config));
					}
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}
}
