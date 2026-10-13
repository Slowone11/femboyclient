package com.femboyclient;

/**
 * Every color, size, font and timing of the player screen in one place. Change a number here and the whole screen follows.
 * Colors are ARGB ({@code 0xAARRGGBB}); sizes are in GUI pixels (the game scales them with the GUI scale); times are in
 * milliseconds. Only constants live here: nothing in this class draws or allocates.
 *
 * The look is a frosted-glass dashboard: dark translucent panels over a blurred game, thin white borders, one pink accent,
 * white values and gray-pink labels.
 *
 * The one thing that lives outside this file is the size of each font, because the game reads it from
 * {@code assets/femboyclient/font/<name>.json}. The {@code FONT_*_SIZE} numbers below must match those files (a test checks it).
 */
public final class Theme {

	private Theme() {
	}

	// ------------------------------------------------------------ colors

	/** The accent: the selected row, the networth card, the caret, the scroll thumb, an "on" switch. #FF4FA6 */
	public static final int ACCENT = 0xFFFF4FA6;
	public static final int ACCENT_LIGHT = 0xFFFF8AC8;
	/** The accent at 12% and 30%, for fills and lines that should only tint. */
	public static final int ACCENT_TINT = 0x1FFF4FA6;
	public static final int ACCENT_LINE = 0x4DFF4FA6;
	/** The accent at 18%: the selected row. */
	public static final int ACCENT_SELECTED = 0x2EFF4FA6;

	/** The glass panel: a near-black plum at 75%. */
	public static final int PANEL = 0xBF170D16;
	/** The panel's border: white at 12%. */
	public static final int BORDER = 0x1FFFFFFF;
	/** The sheen over the panel, brightest at the top and gone by the bottom. */
	public static final int SHEEN_TOP = 0x16FFFFFF;
	public static final int SHEEN_BOTTOM = 0x00FFFFFF;
	/** The game behind the window, dimmed on top of the blur. */
	public static final int BACKDROP = 0x99120A11;

	/** Cards and fields inside the panel: a faint white fill and border, a little brighter under the mouse. */
	public static final int CARD_FILL = 0x0DFFFFFF;
	public static final int CARD_BORDER = 0x14FFFFFF;
	public static final int CARD_HOVER_FILL = 0x1AFFFFFF;
	public static final int CARD_HOVER_BORDER = 0x40FFFFFF;
	public static final int ROW_HOVER = 0x0FFFFFFF;
	public static final int CHAT_ROW_EVEN = 0x0AFFFFFF;
	public static final int CHAT_ROW_ODD = 0x04FFFFFF;
	public static final int DIVIDER = 0x14FFFFFF;

	/** Values and names: white. */
	public static final int TEXT = 0xFFFFFFFF;
	/** Small labels and hints: gray-pink at 60%. */
	public static final int TEXT_LABEL = 0x99B79AAE;
	/** Secondary text: the same gray-pink at 75%. */
	public static final int TEXT_DIM = 0xBFB79AAE;
	public static final int TEXT_ERROR = 0xFFFF6B7A;
	/** Done, enough: the planner's "1/1 ✓". The rank badges' green. */
	public static final int TEXT_OK = 0xFF55E07A;

	/** Shadows are black, at this opacity right under the shape, fading smoothly to nothing over the spread. */
	public static final int SHADOW_COLOR = 0x000000;
	public static final int SHADOW_ALPHA = 84;
	public static final int GLOW_ALPHA = 90;

	public static final int SCROLLBAR_TRACK = 0x14FFFFFF;
	public static final int SCROLLBAR_THUMB = 0xB3FF4FA6;
	public static final int SCROLLBAR_THUMB_HOT = ACCENT;

	/** An "off" switch: a plain neutral gray, so only "on" ever looks like the brand's pink. */
	public static final int SWITCH_OFF = 0xFF706B78;

	/** The "Copied!" toast. */
	public static final int TOAST_FILL = 0xE6241022;
	public static final int TOAST_BORDER = 0x40FFFFFF;

	/** Rank badges, in the order of {@link Ranks.Style}: green, aqua, gold, red, blue, dark green. Fill, then the text on it. */
	public static final int[] RANK_FILL = {0xFF55E07A, 0xFF4FD8F0, 0xFFFFB02E, 0xFFE5484D, 0xFF4E6BFF, 0xFF1F9D55};
	public static final int[] RANK_TEXT = {0xFF06210F, 0xFF04222A, 0xFF2B1A00, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};

	/**
	 * SkyBlock rarities, as rank-style badges (fill, then the text on it): the colors players know (Legendary gold, Epic purple),
	 * softened to sit next to the pink and plum. Same order as {@link #RARITIES}.
	 */
	public static final String[] RARITIES = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL",
			"VERY SPECIAL", "ULTIMATE", "ADMIN"};
	public static final int[] RARITY_FILL = {0xFFE4DDE6, 0xFF55E07A, 0xFF4E6BFF, 0xFFB45CFF, 0xFFFFB02E, 0xFFFF6BD6, 0xFF4FD8F0,
			0xFFE5484D, 0xFFE5484D, 0xFFC4304A, 0xFF9E1F2E};
	public static final int[] RARITY_TEXT = {0xFF2A2230, 0xFF06210F, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF2B1A00, 0xFF3A0A2E, 0xFF04222A,
			0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF};

	/** Links in wiki text, and a link under the mouse. */
	public static final int TEXT_LINK = ACCENT_LIGHT;
	public static final int TEXT_LINK_HOVER = ACCENT;

	// ------------------------------------------------------------ fonts

	/** The fonts are files in {@code assets/femboyclient/font/}: Inter, drawn anti-aliased at 8x resolution by the game. */
	public static final String FONT_NAMESPACE = "femboyclient";
	public static final String FONT_LABEL = "label";        // SemiBold: small ALL-CAPS labels
	public static final String FONT_BODY = "body";          // Regular: chat text, hints
	public static final String FONT_STRONG = "strong";      // SemiBold: names and headings
	public static final String FONT_VALUE = "value";        // SemiBold: the big numbers in the stat cards

	/** Each font's size in GUI pixels. Must equal the "size" in its json file. */
	public static final float FONT_LABEL_SIZE = 8f;
	public static final float FONT_BODY_SIZE = 9f;
	public static final float FONT_STRONG_SIZE = 10f;
	public static final float FONT_VALUE_SIZE = 15f;

	/** How tall a capital letter is compared to the font size (Inter's is 0.727). Used to center text in boxes. */
	public static final float CAP_HEIGHT_RATIO = 0.727f;
	/** The game draws every font with its baseline this many pixels below the y it is given. */
	public static final int GLYPH_BASELINE = 7;
	/** How tall a line of body text and of a small label is, including the space between lines. */
	public static final int BODY_LINE = 12;
	public static final int LABEL_LINE = 10;

	// ------------------------------------------------------------ sizes

	public static final int PANEL_RADIUS = 12;
	public static final int CARD_RADIUS = 10;
	public static final int ROW_RADIUS = 8;
	public static final int BADGE_RADIUS = 4;

	public static final int WINDOW_WIDTH = 620;
	public static final int WINDOW_HEIGHT = 300;
	/** Space inside the panel and between its parts. */
	public static final int PADDING = 14;
	public static final int GAP = 8;
	public static final int HEADER_HEIGHT = 50;
	public static final int LIST_WIDTH = 158;
	public static final int ROW_HEIGHT = 28;
	public static final int HEAD_SIZE = 18;
	public static final int HEADER_HEAD_SIZE = 28;
	public static final int SEARCH_WIDTH = 150;
	public static final int SEARCH_HEIGHT = 22;
	public static final int STAT_CARD_HEIGHT = 50;
	public static final int CARD_PADDING = 10;
	public static final int SECTION_HEIGHT = 24;
	public static final int BADGE_HEIGHT = 14;
	public static final int ACCENT_BAR_WIDTH = 3;
	public static final int SCROLLBAR_WIDTH = 3;
	/** Borders are this many real screen pixels thick, whatever the GUI scale, so they stay hairlines. */
	public static final int BORDER_PX = 1;
	public static final int CHAT_ROW_PAD = 5;

	/** The window's shadow spreads this far (GUI pixels) and is nudged down a little, like light from above. */
	public static final int SHADOW_SPREAD = 26;
	public static final int SHADOW_OFFSET_Y = 8;
	/** The 3D model's stage, at the right of the stat cards, when there is room for it. */
	public static final int MODEL_COLUMN_WIDTH = 86;
	public static final int MODEL_MIN_RIGHT_WIDTH = 396;
	public static final int MODEL_SCALE = 90;

	/** The Wiki Bar's page panel, right of the inventory: at most this wide; narrower than MIN it goes compact, than COMPACT_MIN hidden. */
	public static final int WIKI_PANEL_MAX_WIDTH = 260;
	public static final int WIKI_PANEL_MIN_WIDTH = 150;
	public static final int WIKI_PANEL_COMPACT_MIN_WIDTH = 70;
	public static final int WIKI_HEADER_HEIGHT = 56;
	public static final int WIKI_ICON_BUTTON = 16;
	public static final int WIKI_IMAGE_SIZE = 32;
	/** One row of the Wiki Bar's suggestions. */
	public static final int SUGGESTION_ROW_HEIGHT = 18;

	// ------------------------------------------------------------ motion

	/** The window slides up to this many pixels against the mouse. */
	public static final float PARALLAX_PIXELS = 3.0f;
	public static final float SHADOW_PARALLAX = 0.4f;
	/** The player model leans up to this many degrees towards the mouse, and turns at this speed. */
	public static final float MODEL_FOLLOW_DEGREES = 35.0f;
	public static final float MODEL_SPIN_DEGREES_PER_SECOND = 24.0f;
	/** How often the model's render state is rebuilt to pick up a skin that finished loading. */
	public static final int MODEL_REFRESH_MS = 500;

	/** The window fades in and grows from this fraction of its size over this long. */
	public static final int OPEN_MS = 240;
	public static final float OPEN_START_SCALE = 0.95f;
	/** Rows and cards fade their highlight in and out this fast. */
	public static final int HOVER_MS = 140;
	/** The accent bar slides to the selected row in this time. */
	public static final int BAR_SLIDE_MS = 180;
	/** Scrolling eases towards where the wheel sent it: the larger, the faster (per second). */
	public static final float SCROLL_EASE_PER_SECOND = 14.0f;
	public static final int SCROLL_STEP = 22;
	/** "Copied!" stays this long, then fades out over the second time. */
	public static final int TOAST_MS = 1500;
	public static final int TOAST_FADE_MS = 300;
	/** The Wiki Bar waits this long after the last key before asking the wiki for suggestions. */
	public static final int SEARCH_DEBOUNCE_MS = 300;
	/** The search caret blinks with this period. */
	public static final int CARET_BLINK_MS = 1000;
	/** The panel's colors drift slowly through one cycle in this time, and a few faint particles float up behind the cards. */
	public static final int BACKGROUND_CYCLE_MS = 10000;
	public static final float BACKGROUND_DRIFT = 0.10f;
	public static final int PARTICLES = 12;
	public static final int PARTICLE_MAX_ALPHA = 46;
	public static final int PARTICLE_COLOR = ACCENT_LIGHT;
}
