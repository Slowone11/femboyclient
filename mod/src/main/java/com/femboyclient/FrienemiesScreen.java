package com.femboyclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * "Frienemys": opened from the Frienemys module of the G menu ({@link ClientMenuScreen}); Esc goes back there. Lists the players in your current lobby; pick one to tag them friendly or enemy and write a
 * note on why. The same tag can also be made or edited on the website, which is easier for a longer note.
 */
public final class FrienemiesScreen extends Screen {

	private static final int WIDTH = 460, HEIGHT = 300;
	private static final int LIST_WIDTH = 160;
	private static final int CHIP_H = 22;
	private static final int NOTE_H = 22;

	private long openedAt;
	private int winX0, winY0, winX1, winY1;
	private boolean fromMenu;
	private int listX0, listY0, listX1, listY1;
	private int detailX0, detailX1;
	private int friendlyX0, friendlyX1, enemyX0, enemyX1, chipY0;
	private int noteX0, noteY0, noteX1, noteY1;
	private int saveX0, saveY0, saveX1, saveY1;
	private int untagX0, untagY0, untagX1, untagY1;

	private FormattedCharSequence titleText, noPlayersText, pickText, notFoundText, hintText;
	private UiFont.Label friendlyLabel, enemyLabel, saveLabel, untagLabel;

	private PlayerRow list;
	private LobbyPlayer selected;
	private String pendingTag;                 // "friendly", "enemy" or null while nothing is picked
	private Frienemy existing;                  // the selected player's current tag, or null
	private final NotesModel notes = new NotesModel();
	private boolean noteFocused;
	private boolean saving;
	private String statusText = "";
	private String preparedStatusText;
	private FormattedCharSequence statusSeq, noteText;
	private String preparedNoteText;

	/** Marks this screen as opened from the G menu, so Esc goes back there instead of to the game. */
	public FrienemiesScreen fromMenu() {
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

	public FrienemiesScreen() {
		super(Component.literal("Frienemys"));
	}

	@Override
	@SuppressWarnings("unchecked")
	protected void init() {
		if (openedAt == 0) {
			openedAt = System.nanoTime();
		}
		winX0 = (width - WIDTH) / 2;
		winY0 = (height - HEIGHT) / 2;
		winX1 = winX0 + WIDTH;
		winY1 = winY0 + HEIGHT;

		int left = winX0 + Theme.PADDING, right = winX1 - Theme.PADDING;
		int bodyTop = winY0 + Theme.HEADER_HEIGHT + Theme.PADDING - 4;
		int bodyBottom = winY1 - Theme.PADDING;

		listX0 = left;
		listX1 = left + LIST_WIDTH;
		listY0 = bodyTop;
		listY1 = bodyBottom;

		detailX0 = listX1 + Theme.PADDING;
		detailX1 = right;

		int chipW = (detailX1 - detailX0 - Theme.GAP) / 2;
		chipY0 = bodyTop + Theme.HEAD_SIZE + 14;
		friendlyX0 = detailX0;
		friendlyX1 = friendlyX0 + chipW;
		enemyX0 = friendlyX1 + Theme.GAP;
		enemyX1 = detailX1;

		noteX0 = detailX0;
		noteX1 = detailX1;
		noteY0 = chipY0 + CHIP_H + Theme.GAP;
		noteY1 = noteY0 + NOTE_H;

		saveY0 = noteY1 + Theme.GAP;
		saveY1 = saveY0 + 22;
		saveX0 = detailX0;

		titleText = UiFont.sequence("Frienemys", UiFont.Face.VALUE);
		friendlyLabel = UiFont.Label.of(font, "Friendly");
		enemyLabel = UiFont.Label.of(font, "Enemy");
		saveLabel = UiFont.Label.of(font, "Save tag");
		untagLabel = UiFont.Label.of(font, "Untag");
		noPlayersText = UiFont.sequence("No other players here.", UiFont.Face.BODY);
		pickText = UiFont.sequence("Select a player to tag them.", UiFont.Face.BODY);
		notFoundText = UiFont.sequence("They left the lobby.", UiFont.Face.BODY);
		hintText = UiFont.sequence("Notes (optional)", UiFont.Face.BODY);

		saveX1 = saveX0 + saveLabel.width() + 20;
		untagX0 = saveX1 + Theme.GAP;
		untagX1 = untagX0 + untagLabel.width() + 20;
		untagY0 = saveY0;
		untagY1 = saveY1;

		List<LobbyPlayer> players = FemboyClientMod.PLAYER_TRACKER.lobbyPlayers();
		list = new PlayerRow(minecraft, listX1 - listX0 - 8, listY1 - listY0, listY0, players);
		list.setX(listX0 + 4);
		addRenderableWidget(list);

		if (selected != null && players.stream().noneMatch(p -> p.id().equals(selected.id()))) {
			selected = null;             // they left while the screen was open
		}
		refreshDetail();
	}

	// ------------------------------------------------------------ state

	/** Reloads what is known about the selected player: their existing tag (if any) and the note field. */
	private void refreshDetail() {
		existing = selected == null ? null : findExisting(selected);
		pendingTag = existing == null ? null : existing.tag();
		notes.set(existing == null ? "" : existing.notes());
		statusText = "";
		preparedNoteText = null;
	}

	private static Frienemy findExisting(LobbyPlayer player) {
		for (Frienemy one : FrienemiesSync.all()) {
			if (one.uuid() != null ? one.uuid().equals(player.id()) : one.name().equalsIgnoreCase(player.name())) {
				return one;
			}
		}
		return null;
	}

	private void save() {
		if (selected == null || pendingTag == null || saving) {
			return;
		}
		saving = true;
		statusText = "Saving…";
		FrienemiesSync.tag(selected.name(), selected.id(), pendingTag, notes.text()).thenAccept(response ->
				minecraft.execute(() -> {
					saving = false;
					statusText = response.ok() ? "Saved." : "Could not save (" + response.describe() + ").";
					if (response.ok()) {
						refreshDetail();
					}
				}));
	}

	private void untag() {
		if (existing == null || saving) {
			return;
		}
		saving = true;
		statusText = "Removing…";
		FrienemiesSync.untag(existing.id()).thenAccept(response ->
				minecraft.execute(() -> {
					saving = false;
					statusText = response.ok() ? "Untagged." : "Could not remove the tag (" + response.describe() + ").";
					if (response.ok()) {
						refreshDetail();
					}
				}));
	}

	// ------------------------------------------------------------ frame

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

		UiDraw.card(graphics, listX0, listY0, listX1, listY1, Theme.CARD_RADIUS, Theme.CARD_FILL, Theme.CARD_BORDER);
		if (list.children().isEmpty()) {
			UiDraw.text(graphics, font, noPlayersText, listX0 + Theme.CARD_PADDING, listY0 + Theme.CARD_PADDING, Theme.TEXT_DIM);
		}
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		drawDetail(graphics, mouseX, mouseY);
		UiDraw.setFade(1f);
	}

	private void drawDetail(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (selected == null) {
			UiDraw.text(graphics, font, pickText, detailX0, listY0 + 4, Theme.TEXT_DIM);
			return;
		}
		List<LobbyPlayer> players = FemboyClientMod.PLAYER_TRACKER.lobbyPlayers();
		boolean stillHere = players.stream().anyMatch(p -> p.id().equals(selected.id()));

		ClientPacketListener connection = minecraft.getConnection();
		net.minecraft.client.multiplayer.PlayerInfo entry = connection == null ? null : connection.getPlayerInfo(selected.id());
		int nameY = listY0;
		if (entry != null) {
			UiDraw.head(graphics, entry.getSkin(), detailX0, nameY, Theme.HEAD_SIZE);
		}
		UiDraw.text(graphics, font, UiFont.sequence(selected.name(), UiFont.Face.STRONG),
				detailX0 + Theme.HEAD_SIZE + 8, UiFont.centeredY(UiFont.Face.STRONG, nameY, Theme.HEAD_SIZE), Theme.TEXT);
		if (!stillHere) {
			UiDraw.text(graphics, font, notFoundText, detailX0 + Theme.HEAD_SIZE + 8, nameY + Theme.HEAD_SIZE + 2, Theme.TEXT_DIM);
		}

		drawChip(graphics, mouseX, mouseY, friendlyX0, friendlyX1, "friendly", friendlyLabel, Theme.RANK_FILL[0]);
		drawChip(graphics, mouseX, mouseY, enemyX0, enemyX1, "enemy", enemyLabel, Theme.TEXT_ERROR);

		if (noteFocused) {
			UiDraw.glow(graphics, noteX0, noteY0, noteX1, noteY1, Theme.ROW_RADIUS, Theme.ACCENT, 3);
		}
		UiDraw.card(graphics, noteX0, noteY0, noteX1, noteY1, Theme.ROW_RADIUS,
				noteFocused ? Theme.CARD_HOVER_FILL : Theme.CARD_FILL, noteFocused ? Theme.ACCENT : Theme.CARD_BORDER);
		int noteTextY = UiFont.centeredY(UiFont.Face.BODY, noteY0, noteY1 - noteY0);
		if (notes.isEmpty()) {
			UiDraw.text(graphics, font, hintText, noteX0 + 10, noteTextY, Theme.TEXT_LABEL);
			if (noteFocused && caretOn(System.nanoTime())) {
				UiDraw.fill(graphics, noteX0 + 10, noteY0 + 5, noteX0 + 11, noteY1 - 5, Theme.ACCENT);
			}
		} else {
			if (!notes.text().equals(preparedNoteText)) {
				preparedNoteText = notes.text();
				noteText = UiFont.sequence(preparedNoteText, UiFont.Face.BODY);
			}
			// A note can be longer than the box is wide (up to NotesModel.MAX_LENGTH): clip it to the box, and while
			// editing keep the end (where the caret is) in view instead of the start.
			int room = noteX1 - noteX0 - 20;
			int textWidth = font.width(noteText);
			int textX = noteX0 + 10 - (noteFocused ? Math.max(0, textWidth - room) : 0);
			UiDraw.beginClip(graphics, noteX0 + 2, noteY0 + 2, noteX1 - 2, noteY1 - 2);
			if (notes.allSelected()) {
				UiDraw.roundRect(graphics, textX - 2, noteY0 + 4, textX + 2 + textWidth, noteY1 - 4, 3, Theme.ACCENT_SELECTED);
			}
			UiDraw.text(graphics, font, noteText, textX, noteTextY, Theme.TEXT);
			if (noteFocused && !notes.allSelected() && caretOn(System.nanoTime())) {
				int caretX = textX + textWidth + 1;
				UiDraw.fill(graphics, caretX, noteY0 + 5, caretX + 1, noteY1 - 5, Theme.ACCENT);
			}
			UiDraw.endClip(graphics);
		}

		boolean saveHover = pendingTag != null && !saving && UiDraw.inside(mouseX, mouseY, saveX0, saveY0, saveX1, saveY1);
		UiDraw.card(graphics, saveX0, saveY0, saveX1, saveY1, Theme.ROW_RADIUS,
				pendingTag == null ? Theme.CARD_FILL : (saveHover ? Theme.CARD_HOVER_FILL : Theme.ACCENT_TINT),
				pendingTag == null ? Theme.CARD_BORDER : Theme.ACCENT);
		UiDraw.label(graphics, font, saveLabel, saveX0 + 10, UiFont.topY(UiFont.Face.LABEL, saveY0 + 7),
				pendingTag == null ? Theme.TEXT_DIM : (saveHover ? Theme.ACCENT_LIGHT : Theme.TEXT));

		if (existing != null) {
			boolean untagHover = !saving && UiDraw.inside(mouseX, mouseY, untagX0, untagY0, untagX1, untagY1);
			UiDraw.hoverCard(graphics, untagX0, untagY0, untagX1, untagY1, Theme.ROW_RADIUS, untagHover);
			UiDraw.label(graphics, font, untagLabel, untagX0 + 10, UiFont.topY(UiFont.Face.LABEL, untagY0 + 7),
					untagHover ? Theme.ACCENT_LIGHT : Theme.TEXT_LABEL);
		}

		if (!statusText.isEmpty()) {
			if (!statusText.equals(preparedStatusText)) {
				preparedStatusText = statusText;
				statusSeq = UiFont.sequence(statusText, UiFont.Face.BODY);
			}
			UiDraw.text(graphics, font, statusSeq, detailX0, saveY1 + 14, Theme.TEXT_DIM);
		}
	}

	private boolean caretOn(long now) {
		return ((now - openedAt) / 1_000_000L) % Theme.CARET_BLINK_MS < Theme.CARET_BLINK_MS / 2;
	}

	private void drawChip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int x0, int x1, String tag,
			UiFont.Label label, int accent) {
		boolean selectedChip = tag.equals(pendingTag);
		boolean hover = UiDraw.inside(mouseX, mouseY, x0, chipY0, x1, chipY0 + CHIP_H);
		UiDraw.accentCard(graphics, x0, chipY0, x1, chipY0 + CHIP_H, Theme.ROW_RADIUS, accent, selectedChip, hover);
		int textX = x0 + (x1 - x0 - label.width()) / 2;
		UiDraw.label(graphics, font, label, textX, UiFont.topY(UiFont.Face.LABEL, chipY0 + (CHIP_H - UiFont.capHeight(UiFont.Face.LABEL)) / 2),
				selectedChip ? Theme.TEXT : Theme.TEXT_DIM);
	}

	// ------------------------------------------------------------ input

	private void setNoteFocused(boolean focused) {
		noteFocused = focused;
		if (!focused) {
			notes.clearSelection();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			if (selected != null) {
				boolean inNote = UiDraw.inside(event.x(), event.y(), noteX0, noteY0, noteX1, noteY1);
				setNoteFocused(inNote);
				if (inNote) {
					return true;
				}
				if (UiDraw.inside(event.x(), event.y(), friendlyX0, chipY0, friendlyX1, chipY0 + CHIP_H)) {
					pendingTag = "friendly";
					return true;
				}
				if (UiDraw.inside(event.x(), event.y(), enemyX0, chipY0, enemyX1, chipY0 + CHIP_H)) {
					pendingTag = "enemy";
					return true;
				}
				if (pendingTag != null && !saving && UiDraw.inside(event.x(), event.y(), saveX0, saveY0, saveX1, saveY1)) {
					save();
					return true;
				}
				if (existing != null && !saving && UiDraw.inside(event.x(), event.y(), untagX0, untagY0, untagX1, untagY1)) {
					untag();
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (noteFocused) {
			notes.type(event.codepoint());
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!noteFocused) {
			return super.keyPressed(event);
		}
		if (event.isEscape() || event.isConfirmation()) {
			setNoteFocused(false);
			return true;
		}
		if (event.key() == InputConstants.KEY_BACKSPACE) {
			notes.backspace();
		} else if (event.isSelectAll()) {
			notes.selectAll();
		} else if (event.isPaste()) {
			notes.paste(minecraft.keyboardHandler.getClipboard());
		}
		return true;
	}

	@Override
	public void removed() {
		UiDraw.releaseTextures();
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------ the player list

	/** The scrollable list of lobby players on the left, with a small colored dot for anyone already tagged. */
	private final class PlayerRow extends ObjectSelectionList<PlayerRow.Row> {

		PlayerRow(Minecraft minecraft, int width, int height, int y, List<LobbyPlayer> players) {
			super(minecraft, width, height, y, Theme.ROW_HEIGHT);
			for (LobbyPlayer player : players) {
				Row row = new Row(player);
				addEntry(row);
				if (selected != null && selected.id().equals(player.id())) {
					setSelected(row);
				}
			}
		}

		@Override
		public int getRowWidth() {
			return getWidth() - 12;
		}

		@Override
		protected int scrollBarX() {
			return getX() + getWidth() - Theme.SCROLLBAR_WIDTH - 4;
		}

		@Override
		protected void extractScrollbar(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
			int x0 = scrollBarX();
			int thumbTop = scrollBarY();
			UiDraw.scrollbar(graphics, x0, getY(), x0 + Theme.SCROLLBAR_WIDTH, getY() + getHeight(), thumbTop, thumbTop + scrollerHeight(),
					isOverScrollbar(mouseX, mouseY));
		}

		@Override
		protected void extractListBackground(GuiGraphicsExtractor graphics) {
		}

		@Override
		protected void extractListSeparators(GuiGraphicsExtractor graphics) {
		}

		@Override
		protected void extractSelection(GuiGraphicsExtractor graphics, Row entry, int color) {
			int x = entry.getX(), y = entry.getY(), w = entry.getWidth(), h = entry.getHeight();
			UiDraw.roundRect(graphics, x, y, x + w, y + h, Theme.ROW_RADIUS, Theme.ACCENT_SELECTED);
		}

		private final class Row extends ObjectSelectionList.Entry<Row> {
			private final LobbyPlayer player;
			private final net.minecraft.client.multiplayer.PlayerInfo tabEntry;
			private final FormattedCharSequence nameSeq;

			Row(LobbyPlayer player) {
				this.player = player;
				ClientPacketListener connection = Minecraft.getInstance().getConnection();
				this.tabEntry = connection == null ? null : connection.getPlayerInfo(player.id());
				this.nameSeq = UiFont.sequence(player.name(), UiFont.Face.STRONG);
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
				if (hovered && getSelected() != this) {
					UiDraw.roundRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), Theme.ROW_RADIUS, Theme.ROW_HOVER);
				}
				int textX = getX() + 10;
				Frienemy tagged = findExisting(player);
				if (tagged != null) {
					UiDraw.roundRect(graphics, textX, getY() + getHeight() / 2 - 2, textX + 4, getY() + getHeight() / 2 + 2, 2,
							(tagged.friendly() ? Theme.RANK_FILL[0] : Theme.TEXT_ERROR) | 0xFF000000);
					textX += 9;
				}
				if (tabEntry != null) {
					UiDraw.head(graphics, tabEntry.getSkin(), textX, getY() + (getHeight() - Theme.HEAD_SIZE) / 2, Theme.HEAD_SIZE);
					textX += Theme.HEAD_SIZE + 8;
				}
				UiDraw.text(graphics, font, nameSeq, textX, UiFont.centeredY(UiFont.Face.STRONG, getY(), getHeight()), Theme.TEXT);
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				setSelected(this);
				selected = player;
				refreshDetail();
				return true;
			}

			@Override
			public Component getNarration() {
				return Component.literal(player.name());
			}
		}
	}
}
