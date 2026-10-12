package com.femboyclient;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * "Auto Conversation": one of the modules the G menu ({@link ClientMenuScreen}) opens, with a small "◂ Menu" tab in the
 * corner to go back. Shows whether it is on, lets you switch it with a click, and explains how it decides (see
 * {@link NpcAutoAnswer}). There is nothing to configure beyond the switch: it reads the dialog's own coloring, not a file.
 */
public final class NpcAutoScreen extends Screen {

	private static final int WIDTH = 460, HEIGHT = 210;
	private static final int TAB_W = 110, TAB_H = 22;         // the corner tab back to the G menu

	private long openedAt;
	private int winX0, winY0, winX1, winY1;
	private int tabX0, tabY0, tabX1, tabY1;

	private FormattedCharSequence titleText, switchOnText, switchOffText;
	private UiFont.Label menuTabLabel;
	private List<FormattedCharSequence> introLines, singleLines, multiLines;

	public NpcAutoScreen() {
		super(Component.literal("Auto Conversation"));
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

		int textWidth = WIDTH - 2 * Theme.PADDING;
		titleText = UiFont.sequence("Auto Conversation", UiFont.Face.VALUE);
		menuTabLabel = UiFont.Label.of(font, "◂ Menu");
		switchOnText = UiFont.sequence("ON", UiFont.Face.STRONG);
		switchOffText = UiFont.sequence("OFF", UiFont.Face.STRONG);
		introLines = font.split(UiFont.component(
				"Clicks the right option of a Hypixel NPC dialog for you, in the background, while you keep playing normally.",
				UiFont.Face.BODY), textWidth);
		singleLines = font.split(UiFont.component(
				"One option offered → always clicked. Nothing to decide.", UiFont.Face.BODY), textWidth);
		multiLines = font.split(UiFont.component(
				"Two or more options → the green one is clicked. None of them green? Nothing is sent, and it is left for you "
						+ "to click yourself.", UiFont.Face.BODY), textWidth);
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

		int y = winY0 + Theme.HEADER_HEIGHT + Theme.PADDING;
		FemboyClientConfig config = FemboyClientConfig.get();
		boolean on = config.npcAuto;
		boolean switchHover = UiDraw.inside(mouseX, mouseY, left, y, left + 70, y + 20);
		UiDraw.toggleSwitch(graphics, left, y, left + 70, y + 20, on, switchHover);
		UiDraw.text(graphics, font, on ? switchOnText : switchOffText, left + 24, UiFont.centeredY(UiFont.Face.STRONG, y, 20), Theme.TEXT);
		y += 30;

		y = drawParagraph(graphics, introLines, left, y);
		y += 4;
		y = drawParagraph(graphics, singleLines, left, y);
		y += 2;
		drawParagraph(graphics, multiLines, left, y);

		UiDraw.setFade(1f);
	}

	private int drawParagraph(GuiGraphicsExtractor graphics, List<FormattedCharSequence> lines, int x, int y) {
		for (FormattedCharSequence line : lines) {
			UiDraw.text(graphics, font, line, x, y, Theme.TEXT_DIM);
			y += Theme.BODY_LINE;
		}
		return y;
	}

	/** The "Menu" tab's bounds {x0, y0, x1, y1}, in GUI pixels. For the screenshot test. */
	public int[] menuTabBounds() {
		return new int[] {tabX0, tabY0, tabX1, tabY1};
	}

	/** The ON/OFF switch's bounds {x0, y0, x1, y1}, in GUI pixels. For the screenshot test. */
	public int[] switchBounds() {
		int left = winX0 + Theme.PADDING;
		int y = winY0 + Theme.HEADER_HEIGHT + Theme.PADDING;
		return new int[] {left, y, left + 70, y + 20};
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			if (UiDraw.inside(event.x(), event.y(), tabX0, tabY0, tabX1, tabY1)) {
				minecraft.setScreenAndShow(new ClientMenuScreen());
				return true;
			}
			int left = winX0 + Theme.PADDING;
			int y = winY0 + Theme.HEADER_HEIGHT + Theme.PADDING;
			if (UiDraw.inside(event.x(), event.y(), left, y, left + 70, y + 20)) {
				FemboyClientConfig config = FemboyClientConfig.get();
				config.npcAuto = !config.npcAuto;
				config.save();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}
}
