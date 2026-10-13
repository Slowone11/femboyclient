package com.femboyclient;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The MansionFinder screen, where you place its map: drag it with the left mouse button, scroll to make it bigger or smaller, press
 * Esc to keep it where it is. The map always has north up. The same controls work in the chat (see {@link HollowsMapDrag}). Until a
 * Mansion is found it shows a made-up one, so you can see how it will look.
 *
 * The corner buttons: "Reset" puts the map back in the top left at its normal size, and "Dump nearby blocks" writes what is
 * loaded around you to a file, to study what a structure is made of. Opened from the G menu, Esc goes back there.
 */
public final class HollowsMapScreen extends Screen {

	private static final int BTN_W = 130, BTN_H = 20, BTN_GAP = 6;

	/** True while this screen is open, so the corner map is not drawn a second time under it. */
	static volatile boolean open;

	private boolean fromMenu;
	private int resetX0, resetY0, resetX1, resetY1;
	private int dumpX0, dumpY0, dumpX1, dumpY1;
	private UiFont.Label resetLabel, dumpLabel;

	public HollowsMapScreen() {
		super(Component.literal("MansionFinder"));
	}

	@Override
	protected void init() {
		open = true;
		int x1 = width - 8, x0 = x1 - BTN_W;
		resetX0 = x0;
		resetX1 = x1;
		resetY0 = 8;
		resetY1 = resetY0 + BTN_H;
		dumpX0 = x0;
		dumpX1 = x1;
		dumpY0 = resetY1 + BTN_GAP;
		dumpY1 = dumpY0 + BTN_H;
		resetLabel = UiFont.Label.of(font, "Reset position");
		dumpLabel = UiFont.Label.of(font, "Dump nearby blocks");
	}

	private static HollowsMapModel.Model shown() {
		return HollowsHud.model().isEmpty() ? HollowsMapModel.demo() : HollowsHud.model();
	}

	/** Marks this screen as opened from the G menu, so Esc goes back there instead of to the game. */
	public HollowsMapScreen fromMenu() {
		fromMenu = true;
		return this;
	}

	@Override
	public void onClose() {
		if (fromMenu) {
			minecraft.setScreenAndShow(new ClientMenuScreen());
		} else {
			super.onClose();
		}
	}

	/** The "Reset position" button's bounds {x0, y0, x1, y1}, in GUI pixels. For the screenshot test. */
	public int[] resetButtonBounds() {
		return new int[] {resetX0, resetY0, resetX1, resetY1};
	}

	/** The "Dump nearby blocks" button's bounds {x0, y0, x1, y1}, in GUI pixels. For the screenshot test. */
	public int[] dumpButtonBounds() {
		return new int[] {dumpX0, dumpY0, dumpX1, dumpY1};
	}

	/** Where the bottom right corner of the map is now, {x, y} in GUI pixels. For the screenshot test. */
	public static int[] corner() {
		FemboyClientConfig c = FemboyClientConfig.get();
		return new int[] {c.hudX + HollowsHud.panelWidth(c.hudSize), c.hudY + HollowsHud.panelHeight(shown(), c.hudSize)};
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		graphics.fill(0, 0, width, height, 0x66000000);           // the game stays visible, only dimmed
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		FemboyClientConfig c = FemboyClientConfig.get();
		UiDraw.setScale(minecraft.getWindow().getGuiScale());
		UiDraw.setFade(1f);
		HollowsHud.draw(graphics, font, c.hudX, c.hudY, c.hudSize, shown(),
				minecraft.player == null ? 0 : minecraft.player.getBlockX(),
				minecraft.player == null ? 110 : minecraft.player.getBlockY(),
					minecraft.player == null ? 0 : minecraft.player.getBlockZ(),
				minecraft.player == null ? "you" : minecraft.player.getName().getString(),
				FemboyClientMod.HOLLOWS.lobbyCode() != null ? FemboyClientMod.HOLLOWS.lobbyCode() : "m174D", true);
		// The grip in the bottom right corner: drag it to resize.
		int right = c.hudX + HollowsHud.panelWidth(c.hudSize), bottom = c.hudY + HollowsHud.panelHeight(shown(), c.hudSize);
		int grip = HollowsMapDrag.resizing() || HollowsMapDrag.overCorner(mouseX, mouseY, shown()) ? HollowsHud.ACCENT : HollowsHud.DIM;
		for (int i = 0; i < 3; i++) {
			graphics.fill(right - 3 - i * 3, bottom - 3, right - 2 - i * 3, bottom - 2, grip);
			graphics.fill(right - 3, bottom - 3 - i * 3, right - 2, bottom - 2 - i * 3, grip);
			if (i > 0) {
				graphics.fill(right - 3 - i * 3, bottom - 3 - 3, right - 2 - i * 3, bottom - 2 - 3, grip);
			}
		}
		graphics.centeredText(font, "Drag the map to move it · drag its corner or scroll to resize · Esc to keep it", width / 2, height - 24, 0xFFE6E9EF);

		button(graphics, resetX0, resetY0, resetX1, resetY1, resetLabel, mouseX, mouseY);
		button(graphics, dumpX0, dumpY0, dumpX1, dumpY1, dumpLabel, mouseX, mouseY);
	}

	private void button(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, UiFont.Label label, int mouseX, int mouseY) {
		boolean hover = UiDraw.inside(mouseX, mouseY, x0, y0, x1, y1);
		UiDraw.hoverCard(graphics, x0, y0, x1, y1, Theme.ROW_RADIUS, hover);
		UiDraw.label(graphics, font, label, x0 + (x1 - x0 - label.width()) / 2, UiFont.topY(UiFont.Face.LABEL, y0 + 6),
				hover ? Theme.ACCENT_LIGHT : Theme.TEXT_LABEL);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			if (UiDraw.inside(event.x(), event.y(), resetX0, resetY0, resetX1, resetY1)) {
				HollowsMap.reset(FemboyClientConfig.get());
				return true;
			}
			if (UiDraw.inside(event.x(), event.y(), dumpX0, dumpY0, dumpX1, dumpY1)) {
				dumpNearbyBlocks();
				return true;
			}
		}
		return HollowsMapDrag.press(event.x(), event.y(), event.button(), shown(), width, height) || super.mouseClicked(event, doubleClick);
	}

	/** Writes every block loaded around the player to a file, to study what a structure is made of. Never throws. */
	private static void dumpNearbyBlocks() {
		try {
			HollowsDump.Result result = HollowsDump.write(net.minecraft.client.Minecraft.getInstance(), HollowsDump.DEFAULT_RADIUS);
			Chat.notice("Dumped " + result.chunks() + " chunks (" + result.missing() + " not loaded, " + result.runs() + " runs, "
					+ result.kinds() + " kinds of block) to " + result.file());
		} catch (java.io.IOException | RuntimeException e) {
			Chat.notice("Could not dump the blocks: " + e.getMessage());
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		return HollowsMapDrag.drag(event.x(), event.y(), dx, dy, shown(), width, height) || super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		HollowsMapDrag.release();
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		HollowsMapDrag.scroll(mouseX, mouseY, scrollY, shown(), width, height);
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		open = false;
		FemboyClientConfig.get().save();                            // the place is kept for next time
		super.removed();
	}
}
