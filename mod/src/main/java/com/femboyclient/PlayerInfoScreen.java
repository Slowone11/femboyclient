package com.femboyclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * The lobby player screen, a frosted-glass dashboard. A floating glass panel holds a header (the selected player's face, rank and
 * name, and a search box), the list of players in your lobby on the left, and on the right three stat cards (Discord, times met,
 * networth), the chat log, and the player's 3D model when there is room.
 *
 * Clicking a player selects them and asks the website about them. Clicking the Discord value copies it.
 *
 * Everything is drawn with the fonts and colors of {@link UiFont} and {@link Theme}. Every text is built once (when the screen
 * opens, a player is selected or an answer arrives) and only drawn afterwards, so drawing does not allocate.
 *
 * Animations run from the real clock, so they take the same time at any frame rate: the window fades and scales in, rows and the
 * Discord card fade their highlight, the accent bar slides to the selected row, both scroll areas ease, and "Copied!" fades out.
 */
public final class PlayerInfoScreen extends Screen {

	/** One chat message, already cut into lines that fit the box, with its total height. */
	private record Block(FormattedCharSequence header, List<FormattedCharSequence> lines, int height) {
	}

	private final List<LobbyPlayer> players;
	private final PlayerLookup lookup;
	private final PlayerModelView model = new PlayerModelView();
	private final ParticleField particles = new ParticleField(Theme.PARTICLES, 20260921L);
	private final SearchModel search = new SearchModel();
	private PlayerList list;
	private boolean searchFocused;

	// Where everything is, worked out in layout().
	private int winX0, winY0, winX1, winY1;
	private int searchX0, searchY0, searchX1, searchY1;
	private int listX0, listY0, listX1, listY1;                    // the list card (with its heading)
	private int discordX0, discordX1, timesX0, timesX1, networthX0, networthX1, statY0, statY1;
	private int chatHeadY, chatX0, chatX1, chatY0, chatY1;         // the chat heading, and the chat box below it
	private int modelX0, modelX1;                                  // the 3D model's stage; empty when there is no room
	private boolean fromMenu;                                        // opened from the G menu: Esc goes back there

	// Texts built once.
	private UiFont.Label playersLabel, chatLabel, discordLabel, timesLabel, networthLabel, copyLabel;
	private FormattedCharSequence titleText, hintText, dashText, copiedText, loadingText, noDataText, noChatText, searchText;
	private FormattedCharSequence noPlayersText, noMatchText, pickText, playerCountText;
	private int copiedWidth, searchTextWidth, playerCountWidth, noPreviewWidth;
	private FormattedCharSequence noPreviewText;

	// Time and animation state.
	private long openedAt;                       // when the screen first opened, in nanoseconds
	private long lastFrame;                      // when the previous frame was drawn
	private float frameSeconds;                  // how long the last frame took (at most 0.1)
	private float openFade = 1f;                 // the opening fade, so the toast can fade on top of it
	private float barOffset;                     // how far the accent bar still has to slide to reach the selected row, in pixels
	private float discordHover;                  // 0..1: how lit the Discord card is
	private float scrollTarget, scrollShown;     // the chat log: where the wheel sent it, and where it is drawn now
	private long toastAt;                        // when "Copied!" was shown, in nanoseconds; 0 = never
	private int mouseNowX, mouseNowY;

	private int chatHeight, chatViewHeight;      // set while drawing: how tall the chat content and its view are

	// The header, built when a player is selected.
	private LobbyPlayer headerFor;
	private FormattedCharSequence nameText;
	private net.minecraft.client.multiplayer.PlayerInfo headerEntry;   // the game's record of them, which holds their skin

	// What is shown for the current answer, built when the answer (or a width) changes.
	private PlayerInfo preparedFor;
	private int preparedKey;
	private String discordFull;                  // the whole Discord name, for the clipboard; null when there is none
	private boolean discordLinked;
	private FormattedCharSequence discordText, timesText, networthText, chatCountText, badgeText;
	private UiFont.Face discordFace = UiFont.Face.VALUE;
	private int badgeWidth, badgeStyle = -1;
	private List<FormattedCharSequence> errorLines = List.of();
	private List<Block> blocks = List.of();

	/**
	 * @param players the players to list, from the tab list
	 * @param fetcher asks the website about one player
	 */
	public PlayerInfoScreen(List<LobbyPlayer> players, PlayerLookup.Fetcher fetcher) {
		super(Component.literal("Lobby players"));
		this.players = players;
		this.lookup = new PlayerLookup(fetcher, runnable -> Minecraft.getInstance().execute(runnable));
	}

	@Override
	protected void init() {
		if (openedAt == 0) {
			openedAt = System.nanoTime();          // resizing the window re-runs init: the opening animation only plays once
		}
		buildTexts();
		layout();

		list = new PlayerList(minecraft, listX1 - listX0 - 8, listY1 - listY0 - Theme.SECTION_HEIGHT - 6, listY0 + Theme.SECTION_HEIGHT);
		list.setX(listX0 + 4);
		list.refill(search.text());
		addRenderableWidget(list);
	}

	/** Centers the window and divides it into the header, the list, the stat cards, the chat box and the model's stage. */
	private void layout() {
		int windowWidth = Math.max(1, Math.min(Theme.WINDOW_WIDTH, width - 2 * Theme.GAP));
		int windowHeight = Math.max(1, Math.min(Theme.WINDOW_HEIGHT, height - 2 * Theme.GAP));
		winX0 = (width - windowWidth) / 2;
		winY0 = (height - windowHeight) / 2;
		winX1 = winX0 + windowWidth;
		winY1 = winY0 + windowHeight;

		int left = winX0 + Theme.PADDING;
		int right = winX1 - Theme.PADDING;
		int headerBottom = winY0 + Theme.HEADER_HEIGHT;
		int bodyTop = headerBottom + Theme.PADDING - 2;
		int bodyBottom = winY1 - Theme.PADDING;

		searchX1 = right;
		searchX0 = Math.max(left + 140, right - Theme.SEARCH_WIDTH);
		searchY0 = winY0 + (Theme.HEADER_HEIGHT - Theme.SEARCH_HEIGHT) / 2;
		searchY1 = searchY0 + Theme.SEARCH_HEIGHT;

		listX0 = left;
		listX1 = Math.min(left + Theme.LIST_WIDTH, right - 60);
		listY0 = bodyTop;
		listY1 = bodyBottom;

		int rightX0 = listX1 + Theme.PADDING - 2;
		boolean room = right - rightX0 >= Theme.MODEL_MIN_RIGHT_WIDTH;
		modelX1 = room ? right : 0;
		modelX0 = room ? right - Theme.MODEL_COLUMN_WIDTH : 0;
		int contentX1 = room ? modelX0 - Theme.GAP : right;

		// Times met and networth are as wide as their labels need (at least a quarter and a third of the row); Discord gets the rest.
		int available = contentX1 - rightX0 - 2 * Theme.GAP;
		int timesWidth = Math.max(available * 22 / 100, timesLabel.width() + 2 * Theme.CARD_PADDING + 4);
		int networthWidth = Math.max(available * 30 / 100, networthLabel.width() + 2 * Theme.CARD_PADDING + 4);
		int discordWidth = Math.max(available - timesWidth - networthWidth, discordLabel.width() + copyLabel.width() + 3 * Theme.CARD_PADDING);
		statY0 = bodyTop;
		statY1 = statY0 + Theme.STAT_CARD_HEIGHT;
		discordX0 = rightX0;
		discordX1 = discordX0 + discordWidth;
		timesX0 = discordX1 + Theme.GAP;
		timesX1 = timesX0 + timesWidth;
		networthX0 = timesX1 + Theme.GAP;
		networthX1 = Math.min(contentX1, networthX0 + networthWidth);

		chatHeadY = statY1 + Theme.PADDING - 2;
		chatX0 = rightX0;
		chatX1 = contentX1;
		chatY0 = chatHeadY + Theme.SECTION_HEIGHT;
		chatY1 = bodyBottom;
	}

	/** Builds every text that does not depend on an answer. Runs when the screen opens or is resized. */
	private void buildTexts() {
		playersLabel = UiFont.Label.of(font, "Players");
		chatLabel = UiFont.Label.of(font, "Chat log");
		discordLabel = UiFont.Label.of(font, "Discord");
		timesLabel = UiFont.Label.of(font, "Times met");
		networthLabel = UiFont.Label.of(font, "Networth");
		copyLabel = UiFont.Label.of(font, "Copy");
		titleText = UiFont.sequence("Lobby players", UiFont.Face.VALUE);
		hintText = UiFont.sequence("Search players", UiFont.Face.BODY);
		dashText = UiFont.sequence("—", UiFont.Face.VALUE);
		copiedText = UiFont.sequence("Copied!", UiFont.Face.BODY);
		copiedWidth = font.width(copiedText);
		noPreviewText = UiFont.sequence("No preview", UiFont.Face.BODY);
		noPreviewWidth = font.width(noPreviewText);
		loadingText = UiFont.sequence("Loading...", UiFont.Face.BODY);
		noDataText = UiFont.sequence("No data for this player yet.", UiFont.Face.BODY);
		noChatText = UiFont.sequence("No chat logs from this player yet.", UiFont.Face.BODY);
		noPlayersText = UiFont.sequence("No other players here.", UiFont.Face.BODY);
		noMatchText = UiFont.sequence("No player matches.", UiFont.Face.BODY);
		pickText = UiFont.sequence("Select a player to see their details.", UiFont.Face.BODY);
		playerCountText = UiFont.sequence(String.valueOf(players.size()), UiFont.Face.LABEL);
		playerCountWidth = font.width(playerCountText);
		onSearchChanged(false);
		if (headerFor != null) {
			buildHeader(headerFor);                         // rebuilt with the new font objects after a resize
		}
		preparedFor = null;                                 // and so is the answer's text, for the new widths
	}

	private boolean showsModel() {
		return modelX1 > modelX0;
	}

	/** The width the chat text may use: the box without its padding and the scroll bar. */
	private int chatTextWidth() {
		return chatX1 - chatX0 - 2 * 6 - 2 * Theme.CHAT_ROW_PAD - 3 - Theme.SCROLLBAR_WIDTH - 4;
	}

	// ------------------------------------------------------------ frame

	/** How far the opening animation has got, from 0 (just opened) to 1 (done), eased. */
	private float openProgress(long now) {
		return Ease.inOutCubic((now - openedAt) / 1_000_000f / Theme.OPEN_MS);
	}

	/** The backdrop: the game behind the screen, blurred and dimmed (the dimming fades in with the window). */
	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		UiDraw.setFade(openProgress(System.nanoTime()));
		UiDraw.setScale(minecraft.getWindow().getGuiScale());
		UiDraw.blurBackdrop(graphics, width, height);
		UiDraw.setFade(1f);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		UiDraw.setScale(minecraft.getWindow().getGuiScale());
		frameSeconds = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		mouseNowX = mouseX;
		mouseNowY = mouseY;
		barOffset = Ease.approach(barOffset, 0f, 4000f / Theme.BAR_SLIDE_MS, frameSeconds);
		boolean overDiscord = discordLinked && lookup.selected() != null && UiDraw.inside(mouseX, mouseY, discordX0, statY0, discordX1, statY1);
		discordHover = Ease.approach(discordHover, overDiscord ? 1f : 0f, 3000f / Theme.HOVER_MS, frameSeconds);

		// Opening: everything fades in and grows from a little smaller, about the middle of the screen.
		float opened = openProgress(now);
		openFade = opened;
		float grow = Ease.mix(Theme.OPEN_START_SCALE, 1f, opened);
		UiDraw.setFade(opened);
		graphics.pose().pushMatrix();
		graphics.pose().translate(width / 2f, height / 2f);
		graphics.pose().scale(grow, grow);
		graphics.pose().translate(-width / 2f, -height / 2f);
		try {
			drawLayers(graphics, mouseX, mouseY, delta, now);
		} finally {
			graphics.pose().popMatrix();
			UiDraw.setFade(1f);
		}
	}

	/** Marks this screen as opened from the G menu, so Esc goes back there instead of to the game. */
	public PlayerInfoScreen fromMenu() {
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

	private void drawLayers(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta, long now) {
		// Depth: the glass slides a few pixels against the mouse and its shadow a little less, while everything you can click
		// stays put (so clicks land exactly where they look).
		float towardsX = (mouseX - width / 2f) / (width / 2f);
		float towardsY = (mouseY - height / 2f) / (height / 2f);
		float shiftX = -Math.max(-1f, Math.min(1f, towardsX)) * Theme.PARALLAX_PIXELS;
		float shiftY = -Math.max(-1f, Math.min(1f, towardsY)) * Theme.PARALLAX_PIXELS;
		float seconds = (now - openedAt) / 1_000_000_000f;

		graphics.pose().pushMatrix();
		graphics.pose().translate(shiftX * Theme.SHADOW_PARALLAX, shiftY * Theme.SHADOW_PARALLAX);
		UiDraw.shadow(graphics, winX0, winY0, winX1, winY1, Theme.PANEL_RADIUS, Theme.SHADOW_SPREAD);
		graphics.pose().popMatrix();

		graphics.pose().pushMatrix();
		graphics.pose().translate(shiftX, shiftY);
		UiDraw.glass(graphics, winX0, winY0, winX1, winY1, Theme.PANEL_RADIUS);
		float pulse = 0.5f + 0.5f * (float) Math.sin(seconds * 6.2831855f * 1000f / Theme.BACKGROUND_CYCLE_MS);
		UiDraw.roundRectGradient(graphics, winX0 + 1, winY0 + 1, winX1 - 1, winY1 - 1, Theme.PANEL_RADIUS - 1,
				Colors.withAlpha(Theme.ACCENT, Math.round(pulse * Theme.BACKGROUND_DRIFT * 255)),
				Colors.withAlpha(Theme.ACCENT, Math.round((1f - pulse) * Theme.BACKGROUND_DRIFT * 255)));
		if (Theme.PARTICLES > 0) {                      // a few faint particles floating up in the glass
			particles.update(seconds, winX1 - winX0 - 4, winY1 - winY0 - 4);
			UiDraw.beginClip(graphics, winX0 + 2, winY0 + 2, winX1 - 2, winY1 - 2);
			UiDraw.particles(graphics, particles, winX0 + 2, winY0 + 2);
			UiDraw.endClip(graphics);
		}
		graphics.pose().popMatrix();

		drawHeader(graphics);
		drawSearch(graphics, now);
		drawListCard(graphics);
		super.extractRenderState(graphics, mouseX, mouseY, delta);          // the player list itself, on top of its card
		drawStats(graphics);
		drawChatSection(graphics);
		drawModel(graphics, mouseX, mouseY);
		drawToast(graphics, now);
	}

	// ------------------------------------------------------------ header and search

	/** The selected player's face, rank badge and name; before anyone is selected, the title. */
	private void drawHeader(GuiGraphicsExtractor graphics) {
		int left = winX0 + Theme.PADDING;
		int centerY = winY0 + Theme.HEADER_HEIGHT / 2;
		UiDraw.divider(graphics, winX0 + 1, winY0 + Theme.HEADER_HEIGHT, winX1 - 1);

		LobbyPlayer selected = lookup.selected();
		if (selected == null) {
			UiDraw.glowDot(graphics, left + 5, centerY, 3, Theme.ACCENT);
			UiDraw.text(graphics, font, titleText, left + 18, UiFont.centeredY(UiFont.Face.VALUE, winY0, Theme.HEADER_HEIGHT), Theme.TEXT);
			return;
		}
		if (selected != headerFor) {
			buildHeader(selected);
		}

		int x = left;
		if (headerEntry != null) {
			UiDraw.head(graphics, headerEntry.getSkin(), x, centerY - Theme.HEADER_HEAD_SIZE / 2, Theme.HEADER_HEAD_SIZE);
			x += Theme.HEADER_HEAD_SIZE + 10;
		}
		PlayerInfo info = lookup.info();
		if (info != null && info != preparedFor) {
			prepare(info);
		}
		if (info != null && badgeStyle >= 0) {                                      // the rank arrives with the answer
			int top = centerY - Theme.BADGE_HEIGHT / 2;
			UiDraw.roundRect(graphics, x, top, x + badgeWidth, top + Theme.BADGE_HEIGHT, Theme.BADGE_RADIUS, Theme.RANK_FILL[badgeStyle]);
			UiDraw.text(graphics, font, badgeText, x + 5, UiFont.centeredY(UiFont.Face.LABEL, top, Theme.BADGE_HEIGHT), Theme.RANK_TEXT[badgeStyle]);
			x += badgeWidth + 8;
		}
		UiDraw.text(graphics, font, nameText, x, UiFont.centeredY(UiFont.Face.VALUE, winY0, Theme.HEADER_HEIGHT), Theme.TEXT);
	}

	/** The name and face for the header. Runs when a different player is selected. */
	private void buildHeader(LobbyPlayer selected) {
		headerFor = selected;
		nameText = UiFont.sequence(selected.name(), UiFont.Face.VALUE);
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		headerEntry = connection == null ? null : connection.getPlayerInfo(selected.id());
		badgeStyle = -1;                                    // the old player's rank must not show while the new answer is on its way
	}

	/** The search box: a rounded field in our font, with a blinking caret, an accent border while you type, and a hint while empty. */
	private void drawSearch(GuiGraphicsExtractor graphics, long now) {
		if (searchFocused) {
			UiDraw.glow(graphics, searchX0, searchY0, searchX1, searchY1, Theme.ROW_RADIUS, Theme.ACCENT, 3);
		}
		UiDraw.card(graphics, searchX0, searchY0, searchX1, searchY1, Theme.ROW_RADIUS,
				searchFocused ? Theme.CARD_HOVER_FILL : Theme.CARD_FILL, searchFocused ? Theme.ACCENT : Theme.CARD_BORDER);

		int textX = searchX0 + 10;
		int textY = UiFont.centeredY(UiFont.Face.BODY, searchY0, searchY1 - searchY0);
		if (search.isEmpty()) {
			UiDraw.text(graphics, font, hintText, textX, textY, Theme.TEXT_LABEL);
		} else {
			if (search.allSelected()) {
				UiDraw.roundRect(graphics, textX - 2, searchY0 + 4, textX + searchTextWidth + 2, searchY1 - 4, 3, Theme.ACCENT_SELECTED);
			}
			UiDraw.text(graphics, font, searchText, textX, textY, Theme.TEXT);
		}
		boolean caretOn = ((now - openedAt) / 1_000_000L) % Theme.CARET_BLINK_MS < Theme.CARET_BLINK_MS / 2;
		if (searchFocused && caretOn) {
			int caretX = textX + (search.isEmpty() ? 0 : searchTextWidth + 1);
			UiDraw.fill(graphics, caretX, searchY0 + 5, caretX + 1, searchY1 - 5, Theme.ACCENT);
		}
	}

	/** Rebuilds the search text after it changed, and refreshes the player list. */
	private void onSearchChanged(boolean refill) {
		searchText = UiFont.sequence(search.text(), UiFont.Face.BODY);
		searchTextWidth = font.width(searchText);
		if (refill && list != null) {
			list.refill(search.text());
		}
	}

	// ------------------------------------------------------------ list card

	private void drawListCard(GuiGraphicsExtractor graphics) {
		UiDraw.card(graphics, listX0, listY0, listX1, listY1, Theme.CARD_RADIUS, Theme.CARD_FILL, Theme.CARD_BORDER);
		int labelY = UiFont.centeredY(UiFont.Face.LABEL, listY0, Theme.SECTION_HEIGHT);
		UiDraw.label(graphics, font, playersLabel, listX0 + Theme.CARD_PADDING, labelY, Theme.TEXT_LABEL);
		UiDraw.text(graphics, font, playerCountText, listX1 - Theme.CARD_PADDING - playerCountWidth, labelY, Theme.TEXT_LABEL);
		UiDraw.divider(graphics, listX0 + 1, listY0 + Theme.SECTION_HEIGHT - 1, listX1 - 1);

		int messageY = UiFont.centeredY(UiFont.Face.BODY, listY0 + Theme.SECTION_HEIGHT + 4, Theme.ROW_HEIGHT);
		if (players.isEmpty()) {
			UiDraw.text(graphics, font, noPlayersText, listX0 + Theme.CARD_PADDING, messageY, Theme.TEXT_DIM);
		} else if (list != null && list.children().isEmpty()) {
			UiDraw.text(graphics, font, noMatchText, listX0 + Theme.CARD_PADDING, messageY, Theme.TEXT_DIM);
		}
	}

	// ------------------------------------------------------------ stat cards

	/** The three stat cards: Discord (click to copy), times met and networth. */
	private void drawStats(GuiGraphicsExtractor graphics) {
		PlayerInfo info = lookup.selected() == null ? null : lookup.info();
		if (info != null) {
			prepare(info);
		}
		boolean ready = info != null && info.status() != PlayerInfo.Status.ERROR;

		// Discord: lights up under the mouse; the whole card copies the name.
		UiDraw.card(graphics, discordX0, statY0, discordX1, statY1, Theme.CARD_RADIUS,
				Colors.lerp(Theme.CARD_FILL, Theme.CARD_HOVER_FILL, discordHover), Colors.lerp(Theme.CARD_BORDER, Theme.CARD_HOVER_BORDER, discordHover));
		drawStatText(graphics, discordX0, discordLabel, ready ? discordText : dashText, ready ? discordFace : UiFont.Face.VALUE,
				ready && discordLinked ? Theme.TEXT : Theme.TEXT_DIM);
		if (discordHover > 0.01f) {
			int copyX = discordX1 - Theme.CARD_PADDING - copyLabel.width();
			UiDraw.label(graphics, font, copyLabel, copyX, UiFont.topY(UiFont.Face.LABEL, statY0 + 10), Colors.scaleAlpha(Theme.ACCENT_LIGHT, discordHover));
		}

		UiDraw.card(graphics, timesX0, statY0, timesX1, statY1, Theme.CARD_RADIUS, Theme.CARD_FILL, Theme.CARD_BORDER);
		drawStatText(graphics, timesX0, timesLabel, ready ? timesText : dashText, UiFont.Face.VALUE, Theme.TEXT);

		UiDraw.card(graphics, networthX0, statY0, networthX1, statY1, Theme.CARD_RADIUS, Theme.ACCENT_TINT, Theme.ACCENT_LINE);
		boolean hasNetworth = ready && info.networth() != null;
		drawStatText(graphics, networthX0, networthLabel, ready ? networthText : dashText, UiFont.Face.VALUE, hasNetworth ? Theme.ACCENT : Theme.TEXT_DIM);
	}

	/** A stat card's tiny label and its big value. */
	private void drawStatText(GuiGraphicsExtractor graphics, int cardX0, UiFont.Label label, FormattedCharSequence value, UiFont.Face face, int valueColor) {
		UiDraw.label(graphics, font, label, cardX0 + Theme.CARD_PADDING, UiFont.topY(UiFont.Face.LABEL, statY0 + 10), Theme.TEXT_LABEL);
		UiDraw.text(graphics, font, value, cardX0 + Theme.CARD_PADDING, UiFont.centeredY(face, statY0 + 22, 22), valueColor);
	}

	/** Works out the texts for an answer. Runs when the answer or a width changes, never every frame. */
	private void prepare(PlayerInfo info) {
		int key = discordX1 - discordX0 + 1000 * (chatX1 - chatX0);
		if (preparedFor == info && preparedKey == key) {
			return;
		}
		preparedFor = info;
		preparedKey = key;

		errorLines = info.status() == PlayerInfo.Status.ERROR
				? font.split(UiFont.component(String.valueOf(info.error()), UiFont.Face.BODY), chatX1 - chatX0 - 24) : List.of();
		discordFull = info.discord();
		discordLinked = discordFull != null;
		String shownDiscord = discordLinked ? discordFull : PlayerInfoText.discordValue(info);
		int room = discordX1 - discordX0 - 2 * Theme.CARD_PADDING;
		discordFace = UiFont.width(font, shownDiscord, UiFont.Face.VALUE) <= room ? UiFont.Face.VALUE : UiFont.Face.STRONG;     // a long name is set smaller before it is cut
		discordText = UiFont.sequence(UiFont.fit(font, shownDiscord, discordFace, room), discordFace);
		timesText = UiFont.sequence(String.valueOf(info.timesMet()), UiFont.Face.VALUE);
		networthText = UiFont.sequence(PlayerInfoText.networthValue(info), UiFont.Face.VALUE);
		chatCountText = UiFont.sequence(info.chatLogs().size() + (info.chatLogs().size() == 1 ? " message" : " messages"), UiFont.Face.BODY);

		Ranks.Badge badge = Ranks.badge(info.rank());
		badgeStyle = badge == null ? -1 : badge.style().ordinal();
		if (badge != null) {
			badgeText = UiFont.sequence(badge.text(), UiFont.Face.LABEL);
			badgeWidth = font.width(badgeText) + 10;
		}

		List<Block> made = new ArrayList<>();
		int wrapWidth = chatTextWidth();
		for (PlayerInfo.ChatLog log : info.chatLogs()) {
			List<FormattedCharSequence> lines = font.split(UiFont.component(String.valueOf(log.message()), UiFont.Face.BODY), wrapWidth);
			made.add(new Block(UiFont.sequence(PlayerInfoText.logHeader(log), UiFont.Face.LABEL), lines,
					2 * Theme.CHAT_ROW_PAD + Theme.LABEL_LINE + Theme.BODY_LINE * lines.size()));
		}
		blocks = made;
	}

	// ------------------------------------------------------------ chat log

	/** The chat log: a heading with the message count, and a glass box with alternating rows that scrolls smoothly. */
	private void drawChatSection(GuiGraphicsExtractor graphics) {
		PlayerInfo info = lookup.selected() == null ? null : lookup.info();
		int headTextY = UiFont.centeredY(UiFont.Face.LABEL, chatHeadY, Theme.SECTION_HEIGHT);
		UiDraw.label(graphics, font, chatLabel, chatX0 + 2, headTextY, Theme.TEXT_LABEL);
		if (info != null && info.status() == PlayerInfo.Status.FOUND && chatCountText != null) {
			UiDraw.text(graphics, font, chatCountText, chatX1 - 2 - font.width(chatCountText), UiFont.centeredY(UiFont.Face.BODY, chatHeadY, Theme.SECTION_HEIGHT), Theme.TEXT_DIM);
		}
		UiDraw.divider(graphics, chatX0, chatHeadY + Theme.SECTION_HEIGHT - 4, chatX1);

		UiDraw.card(graphics, chatX0, chatY0, chatX1, chatY1, Theme.CARD_RADIUS, Theme.CARD_FILL, Theme.CARD_BORDER);
		chatHeight = 0;
		int textX = chatX0 + 12;
		int textY = chatY0 + 12;
		if (lookup.selected() == null) {
			UiDraw.text(graphics, font, pickText, textX, textY, Theme.TEXT_DIM);
			return;
		}
		if (info == null) {
			UiDraw.text(graphics, font, loadingText, textX, textY, Theme.TEXT_DIM);
			return;
		}
		prepare(info);
		if (info.status() == PlayerInfo.Status.ERROR) {
			for (FormattedCharSequence line : errorLines) {
				UiDraw.text(graphics, font, line, textX, textY, Theme.TEXT_ERROR);
				textY += Theme.BODY_LINE;
			}
			return;
		}
		if (info.status() == PlayerInfo.Status.NOT_IN_DATABASE) {
			UiDraw.text(graphics, font, noDataText, textX, textY, Theme.TEXT_DIM);
			return;
		}
		if (blocks.isEmpty()) {
			UiDraw.text(graphics, font, noChatText, textX, textY, Theme.TEXT_DIM);
			return;
		}
		drawChatRows(graphics);
	}

	private void drawChatRows(GuiGraphicsExtractor graphics) {
		int viewX0 = chatX0 + 6, viewX1 = chatX1 - 6, viewY0 = chatY0 + 6, viewY1 = chatY1 - 6;
		chatViewHeight = viewY1 - viewY0;
		int contentHeight = 0;
		for (Block block : blocks) {
			contentHeight += block.height();
		}
		chatHeight = contentHeight;

		float furthest = Math.max(0, chatHeight - chatViewHeight);
		scrollTarget = Math.max(0f, Math.min(scrollTarget, furthest));
		scrollShown = Ease.approach(scrollShown, scrollTarget, Theme.SCROLL_EASE_PER_SECOND, frameSeconds);

		boolean scrolls = chatHeight > chatViewHeight;
		int rowX1 = viewX1 - (scrolls ? Theme.SCROLLBAR_WIDTH + 4 : 0);
		UiDraw.beginClip(graphics, viewX0, viewY0, viewX1, viewY1);
		int y = viewY0 - Math.round(scrollShown);
		int index = 0;
		for (Block block : blocks) {
			if (y + block.height() >= viewY0 && y <= viewY1) {                      // skip what is off screen
				UiDraw.roundRect(graphics, viewX0, y, rowX1, y + block.height(), Theme.ROW_RADIUS - 2,
						index % 2 == 0 ? Theme.CHAT_ROW_EVEN : Theme.CHAT_ROW_ODD);
				int textX = viewX0 + Theme.CHAT_ROW_PAD + 3;
				UiDraw.text(graphics, font, block.header(), textX, UiFont.centeredY(UiFont.Face.LABEL, y + Theme.CHAT_ROW_PAD, Theme.LABEL_LINE), Theme.TEXT_LABEL);
				int lineTop = y + Theme.CHAT_ROW_PAD + Theme.LABEL_LINE;
				for (FormattedCharSequence line : block.lines()) {
					UiDraw.text(graphics, font, line, textX, UiFont.centeredY(UiFont.Face.BODY, lineTop, Theme.BODY_LINE), Theme.TEXT);
					lineTop += Theme.BODY_LINE;
				}
			}
			y += block.height();
			index++;
		}
		UiDraw.endClip(graphics);

		if (scrolls) {                                  // a thin custom scroll bar, brighter while the mouse is over it
			int thumb = Math.max(14, chatViewHeight * chatViewHeight / chatHeight);
			int thumbY = viewY0 + Math.round((chatViewHeight - thumb) * scrollShown / (chatHeight - chatViewHeight));
			boolean hot = mouseNowX >= viewX1 - 2 * Theme.SCROLLBAR_WIDTH && mouseNowX <= viewX1 + 4 && mouseNowY >= viewY0 && mouseNowY <= viewY1;
			UiDraw.scrollbar(graphics, viewX1 - Theme.SCROLLBAR_WIDTH, viewY0, viewX1, viewY1, thumbY, thumbY + thumb, hot);
		}
	}

	// ------------------------------------------------------------ 3D model and toast

	/** The 3D model in a glass stage at the right, when the window is wide enough. */
	private void drawModel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!showsModel()) {
			return;
		}
		UiDraw.card(graphics, modelX0, statY0, modelX1, chatY1, Theme.CARD_RADIUS, Theme.CARD_FILL, Theme.CARD_BORDER);
		if (!model.hasModel()) {
			UiDraw.text(graphics, font, noPreviewText, modelX0 + (modelX1 - modelX0 - noPreviewWidth) / 2,
					UiFont.centeredY(UiFont.Face.BODY, statY0, chatY1 - statY0), Theme.TEXT_LABEL);
			return;
		}
		UiDraw.gradientV(graphics, modelX0 + 10, chatY1 - 40, modelX1 - 10, chatY1 - 4, Colors.withAlpha(Theme.ACCENT, 0), Colors.withAlpha(Theme.ACCENT, 46));   // a glow on the floor
		model.draw(graphics, modelX0, statY0, modelX1, chatY1, mouseX, mouseY);
	}

	/** "Copied!": a small pill that floats up at the bottom of the window, stays 1.5 seconds and fades out. */
	private void drawToast(GuiGraphicsExtractor graphics, long now) {
		if (toastAt == 0) {
			return;
		}
		long elapsed = (now - toastAt) / 1_000_000L;
		if (elapsed >= Theme.TOAST_MS + Theme.TOAST_FADE_MS) {
			toastAt = 0;
			return;
		}
		float alpha = elapsed < Theme.TOAST_MS ? 1f : 1f - (elapsed - Theme.TOAST_MS) / (float) Theme.TOAST_FADE_MS;
		int rise = Math.round((1f - Ease.outCubic(elapsed / 160f)) * 5f);              // it slides up a few pixels as it appears
		int pillWidth = copiedWidth + 34;
		int x0 = (winX0 + winX1) / 2 - pillWidth / 2;
		int y0 = winY1 - Theme.PADDING - 34 + rise;
		UiDraw.setFade(openFade * Ease.clamp01(alpha));
		UiDraw.shadow(graphics, x0, y0, x0 + pillWidth, y0 + 22, 11, 5);
		UiDraw.card(graphics, x0, y0, x0 + pillWidth, y0 + 22, 11, Theme.TOAST_FILL, Theme.TOAST_BORDER);
		UiDraw.glowDot(graphics, x0 + 12, y0 + 11, 3, Theme.ACCENT);
		UiDraw.text(graphics, font, copiedText, x0 + 22, UiFont.centeredY(UiFont.Face.BODY, y0, 22), Theme.TEXT);
		UiDraw.setFade(openFade);
	}

	// ------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0) {
			boolean inSearch = UiDraw.inside(event.x(), event.y(), searchX0, searchY0, searchX1, searchY1);
			setSearchFocused(inSearch);
			if (inSearch) {
				return true;
			}
			if (discordLinked && lookup.selected() != null && UiDraw.inside(event.x(), event.y(), discordX0, statY0, discordX1, statY1)) {
				minecraft.keyboardHandler.setClipboard(discordFull);
				toastAt = System.nanoTime();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void setSearchFocused(boolean focused) {
		searchFocused = focused;
		if (!focused) {
			search.clearSelection();
		}
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		if (searchFocused) {
			if (search.type(event.codepoint())) {
				onSearchChanged(true);
			}
			return true;
		}
		return super.charTyped(event);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!searchFocused) {
			return super.keyPressed(event);
		}
		if (event.isEscape() || event.isConfirmation()) {
			setSearchFocused(false);                    // the first Escape leaves the box; the next one closes the screen
			return true;
		}
		if (event.key() == InputConstants.KEY_BACKSPACE) {
			if (search.backspace()) {
				onSearchChanged(true);
			}
		} else if (event.isSelectAll()) {
			search.selectAll();
		} else if (event.isPaste()) {
			if (search.paste(minecraft.keyboardHandler.getClipboard())) {
				onSearchChanged(true);
			}
		}
		return true;                                    // while you type, no other key does anything
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= chatX0 && chatHeight > chatViewHeight) {
			scrollTarget = Math.max(0f, scrollTarget - (float) Math.signum(scrollY) * Theme.SCROLL_STEP);      // clamped when drawn
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void removed() {
		UiDraw.releaseTextures();                       // the smooth shapes are textures: give them back
		super.removed();
	}

	@Override
	public boolean isPauseScreen() {
		return false;             // the game keeps running behind it
	}

	// ------------------------------------------------------------ the player list

	/** The scrollable list on the left. It draws no background of its own: the card behind it is the background. */
	private final class PlayerList extends ObjectSelectionList<PlayerList.Row> {

		private double scrollGoal;               // where the wheel sent the list
		private double scrollDrawn;              // where it is drawn now
		private double scrollLastSet;            // what we last gave the list, to notice when the scroll bar moved it instead

		PlayerList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, Theme.ROW_HEIGHT);
		}

		/** Shows the players whose name matches the search, keeping the selected player selected if they are still listed. */
		void refill(String query) {
			clearEntries();
			LobbyPlayer chosen = lookup.selected();
			for (LobbyPlayer player : players) {
				if (PlayerInfoText.matches(player.name(), query)) {
					Row row = new Row(player);
					addEntry(row);
					if (chosen != null && chosen.id().equals(player.id())) {
						setSelected(row);
					}
				}
			}
			scrollGoal = scrollDrawn = scrollLastSet = 0;
			setScrollAmount(0);
		}

		@Override
		public int getRowWidth() {
			return getWidth() - 16;                // room for the scroll bar
		}

		/** The scroll bar goes inside the list's card, not on its edge. */
		@Override
		protected int scrollBarX() {
			return getX() + getWidth() - Theme.SCROLLBAR_WIDTH - 4;
		}

		/** The wheel sets a goal and the list eases to it. */
		@Override
		public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
			if (!isMouseOver(mouseX, mouseY) || !scrollable()) {
				return false;
			}
			scrollGoal = Math.max(0, Math.min(maxScrollAmount(), scrollGoal - Math.signum(scrollY) * Theme.SCROLL_STEP));
			return true;
		}

		@Override
		public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
			if (Math.abs(scrollAmount() - scrollLastSet) > 0.5) {
				scrollGoal = scrollDrawn = scrollAmount();                // moved by the scroll bar, so follow it instead
			}
			scrollDrawn = Ease.approach((float) scrollDrawn, (float) scrollGoal, Theme.SCROLL_EASE_PER_SECOND, frameSeconds);
			setScrollAmount(scrollDrawn);
			scrollLastSet = scrollAmount();
			super.extractWidgetRenderState(graphics, mouseX, mouseY, delta);
		}

		/** The list's scroll bar in the same slim style as the chat box's, centered in the lane the game keeps for it. */
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

		/** The selected row: a soft accent fill and the accent bar, which slides in from the row that was selected before. */
		@Override
		protected void extractSelection(GuiGraphicsExtractor graphics, Row entry, int color) {
			int x = entry.getX(), y = entry.getY(), w = entry.getWidth(), h = entry.getHeight();
			UiDraw.roundRect(graphics, x, y, x + w, y + h, Theme.ROW_RADIUS, Theme.ACCENT_SELECTED);
			int barTop = y + 6 + Math.round(barOffset);
			int barBottom = barTop + h - 12;
			UiDraw.glow(graphics, x + 1, barTop, x + 1 + Theme.ACCENT_BAR_WIDTH, barBottom, 1, Theme.ACCENT, 4);
			UiDraw.pill(graphics, x + 1, barTop, x + 1 + Theme.ACCENT_BAR_WIDTH, barBottom, Theme.ACCENT);
		}

		private final class Row extends ObjectSelectionList.Entry<Row> {
			private final LobbyPlayer player;
			private final net.minecraft.client.multiplayer.PlayerInfo tabEntry;     // the game's record of them, which holds their skin
			private final FormattedCharSequence nameSeq;
			private float hover;                   // 0 = mouse away, 1 = mouse over; eases between

			Row(LobbyPlayer player) {
				this.player = player;
				ClientPacketListener connection = Minecraft.getInstance().getConnection();
				this.tabEntry = connection == null ? null : connection.getPlayerInfo(player.id());
				this.nameSeq = UiFont.sequence(player.name(), UiFont.Face.STRONG);
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
				hover = Ease.approach(hover, hovered ? 1f : 0f, 3000f / Theme.HOVER_MS, frameSeconds);
				if (hover > 0f) {
					UiDraw.roundRect(graphics, getX(), getY(), getX() + getWidth(), getY() + getHeight(), Theme.ROW_RADIUS,
							Colors.scaleAlpha(Theme.ROW_HOVER, hover));
				}
				int textX = getX() + 12;
				if (tabEntry != null) {                                    // their face, from their current skin
					UiDraw.head(graphics, tabEntry.getSkin(), textX, getY() + (getHeight() - Theme.HEAD_SIZE) / 2, Theme.HEAD_SIZE);
					textX += Theme.HEAD_SIZE + 8;
				}
				UiDraw.text(graphics, font, nameSeq, textX, UiFont.centeredY(UiFont.Face.STRONG, getY(), getHeight()),
						Colors.lerp(Theme.TEXT_DIM, Theme.TEXT, Math.max(hover, getSelected() == this ? 1f : 0f)));
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				Row previous = getSelected();
				if (previous != null && previous != this) {
					barOffset = previous.getY() + barOffset - getY();        // the bar starts where it was drawn and slides here
				}
				setSelected(this);
				scrollTarget = scrollShown = 0f;
				lookup.select(player);
				model.select(player);
				return true;
			}

			@Override
			public Component getNarration() {
				return Component.literal(player.name());
			}
		}
	}
}
