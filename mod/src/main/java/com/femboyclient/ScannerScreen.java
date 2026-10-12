package com.femboyclient;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * "Scanner": one of the modules the G menu ({@link ClientMenuScreen}) opens, with a small "◂ Menu" tab in the corner to go
 * back. Shows whether it is on, lets you switch it with a click, and picks which SkyBlock world it scans (or every world).
 */
public final class ScannerScreen extends Screen {

	private static final int WIDTH = 460, HEIGHT = 300;
	private static final int TAB_W = 110, TAB_H = 22;
	private static final int COLS = 3;
	private static final int CELL_H = 22, CELL_GAP = 6;

	private long openedAt;
	private int winX0, winY0, winX1, winY1;
	private int tabX0, tabY0, tabX1, tabY1;
	private int switchX0, switchY0, switchX1, switchY1;
	private int cellW, gridX0, gridY0;

	private FormattedCharSequence titleText, switchOnText, switchOffText;
	private UiFont.Label menuTabLabel;
	private List<FormattedCharSequence> introLines;

	/** One chip: the world to filter to ("" for every world) and its label. */
	private record Chip(String world, UiFont.Label label) {
	}

	private List<Chip> chips;

	public ScannerScreen() {
		super(Component.literal("Scanner"));
	}

	@Override
	protected void init() {
		if (openedAt == 0) {
			openedAt = System.nanoTime();
		}
		winX0 = (width - WIDTH) / 2;
		winY0 = (height - HEIGHT) / 2;
		winX1 = winX0 + WIDTH;
		winY1 = winY0 + HEIGHT;
		tabX1 = winX1 - 2;
		tabX0 = tabX1 - TAB_W;
		tabY0 = winY0 + 2;
		tabY1 = tabY0 + TAB_H;

		int left = winX0 + Theme.PADDING;
		switchX0 = left;
		switchY0 = winY0 + Theme.HEADER_HEIGHT + Theme.PADDING;
		switchX1 = switchX0 + 70;
		switchY1 = switchY0 + 20;

		int textWidth = WIDTH - 2 * Theme.PADDING;
		titleText = UiFont.sequence("Scanner", UiFont.Face.VALUE);
		menuTabLabel = UiFont.Label.of(font, "◂ Menu");
		switchOnText = UiFont.sequence("ON", UiFont.Face.STRONG);
		switchOffText = UiFont.sequence("OFF", UiFont.Face.STRONG);
		introLines = font.split(UiFont.component(
				"Reports the usernames in your lobby to the website, so it knows who you have met.", UiFont.Face.BODY), textWidth);

		chips = new ArrayList<>();
		chips.add(new Chip("", UiFont.Label.of(font, "All worlds")));
		for (SkyBlockWorlds.World world : SkyBlockWorlds.ALL) {
			chips.add(new Chip(world.name(), UiFont.Label.of(font, world.name())));
		}

		gridX0 = left;
		gridY0 = switchY1 + 14 + introLines.size() * Theme.BODY_LINE + 16;
		cellW = (WIDTH - 2 * Theme.PADDING - (COLS - 1) * Theme.GAP) / COLS;
	}

	private int[] cellBounds(int index) {
		int col = index % COLS, row = index / COLS;
		int x0 = gridX0 + col * (cellW + Theme.GAP);
		int y0 = gridY0 + row * (CELL_H + CELL_GAP);
		return new int[] {x0, y0, x0 + cellW, y0 + CELL_H};
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

		int left = winX0 + Theme.PADDING;
		UiDraw.glowDot(graphics, left + 5, winY0 + Theme.HEADER_HEIGHT / 2, 3, Theme.ACCENT);
		UiDraw.text(graphics, font, titleText, left + 18, UiFont.centeredY(UiFont.Face.VALUE, winY0, Theme.HEADER_HEIGHT), Theme.TEXT);

		boolean tabHover = UiDraw.inside(mouseX, mouseY, tabX0, tabY0, tabX1, tabY1);
		UiDraw.hoverCard(graphics, tabX0, tabY0, tabX1, tabY1, Theme.ROW_RADIUS, tabHover);
		UiDraw.label(graphics, font, menuTabLabel, tabX0 + 8, UiFont.topY(UiFont.Face.LABEL, tabY0 + 7),
				tabHover ? Theme.ACCENT_LIGHT : Theme.TEXT_LABEL);

		FemboyClientConfig config = FemboyClientConfig.get();
		boolean on = config.scanner;
		boolean switchHover = UiDraw.inside(mouseX, mouseY, switchX0, switchY0, switchX1, switchY1);
		UiDraw.toggleSwitch(graphics, switchX0, switchY0, switchX1, switchY1, on, switchHover);
		UiDraw.text(graphics, font, on ? switchOnText : switchOffText, switchX0 + 24,
				UiFont.centeredY(UiFont.Face.STRONG, switchY0, 20), Theme.TEXT);
		UiDraw.text(graphics, font, UiFont.sequence(Scanner.describe(config), UiFont.Face.BODY), switchX1 + 12,
				UiFont.centeredY(UiFont.Face.BODY, switchY0, 20), Theme.TEXT_DIM);

		int y = switchY1 + 14;
		for (FormattedCharSequence line : introLines) {
			UiDraw.text(graphics, font, line, left, y, Theme.TEXT_DIM);
			y += Theme.BODY_LINE;
		}

		UiDraw.text(graphics, font, UiFont.sequence("ONLY SCAN WHILE IN:", UiFont.Face.LABEL), left, UiFont.topY(UiFont.Face.LABEL, y + 4),
				Theme.TEXT_LABEL);

		for (int i = 0; i < chips.size(); i++) {
			Chip chip = chips.get(i);
			int[] b = cellBounds(i);
			boolean selected = chip.world().equals(config.scannerWorld);
			boolean hover = UiDraw.inside(mouseX, mouseY, b[0], b[1], b[2], b[3]);
			UiDraw.card(graphics, b[0], b[1], b[2], b[3], Theme.ROW_RADIUS,
					selected ? Theme.ACCENT_SELECTED : (hover ? Theme.CARD_HOVER_FILL : Theme.CARD_FILL),
					selected ? Theme.ACCENT : (hover ? Theme.CARD_HOVER_BORDER : Theme.CARD_BORDER));
			int textX = b[0] + (cellW - chip.label().width()) / 2;
			UiDraw.label(graphics, font, chip.label(), textX, UiFont.topY(UiFont.Face.LABEL, b[1] + (CELL_H - UiFont.capHeight(UiFont.Face.LABEL)) / 2),
					selected ? Theme.ACCENT_LIGHT : Theme.TEXT_DIM);
		}

		UiDraw.setFade(1f);
	}

	/** The "Menu" tab's bounds {x0, y0, x1, y1}, in GUI pixels. For the screenshot test. */
	public int[] menuTabBounds() {
		return new int[] {tabX0, tabY0, tabX1, tabY1};
	}

	/** The ON/OFF switch's bounds {x0, y0, x1, y1}, in GUI pixels. For the screenshot test. */
	public int[] switchBounds() {
		return new int[] {switchX0, switchY0, switchX1, switchY1};
	}

	/** The bounds {x0, y0, x1, y1} of the world chip at this index (0 is "All worlds"). For the screenshot test. */
	public int[] chipBounds(int index) {
		return cellBounds(index);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			if (UiDraw.inside(event.x(), event.y(), tabX0, tabY0, tabX1, tabY1)) {
				minecraft.setScreenAndShow(new ClientMenuScreen());
				return true;
			}
			FemboyClientConfig config = FemboyClientConfig.get();
			if (UiDraw.inside(event.x(), event.y(), switchX0, switchY0, switchX1, switchY1)) {
				Scanner.apply(config, !config.scanner, config.scannerWorld);
				return true;
			}
			for (int i = 0; i < chips.size(); i++) {
				int[] b = cellBounds(i);
				if (UiDraw.inside(event.x(), event.y(), b[0], b[1], b[2], b[3])) {
					Scanner.apply(config, config.scanner, chips.get(i).world());
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}
}
