package com.femboyclient;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Drawing helpers for the player screen. Every rounded shape is drawn at the screen's real resolution (not in chunky GUI pixels):
 * a shape is an anti-aliased texture from {@link Shapes}, drawn in nine pieces at the size of the screen's own pixels, so corners
 * are smooth curves, borders are hairlines and shadows fade without steps. Coordinates given to these methods are still GUI
 * pixels, like the rest of the game's screens; they are converted inside.
 *
 * Nothing here allocates: every method works with ints and ready-made textures and texts. Colors and sizes come from {@link Theme}.
 *
 * One fade level ({@link #setFade}) multiplies the opacity of everything drawn through these helpers, which is how the window
 * fades in. The screen sets it at the start of a frame and puts it back to 1 at the end.
 */
public final class UiDraw {

	/** Text with less alpha than this is not drawn: the game treats a nearly invisible text color as fully opaque. */
	private static final int MIN_TEXT_ALPHA = 8;
	/** No shape needs a corner bigger than this many screen pixels. */
	private static final int MAX_RADIUS = 200;

	private static float fade = 1f;
	private static int scale = 1;                // screen pixels per GUI pixel (the GUI scale)

	private UiDraw() {
	}

	/** Sets the opacity multiplier (0 = invisible, 1 = normal) for everything drawn until it is changed. */
	public static void setFade(float level) {
		fade = Ease.clamp01(level);
	}

	/** Tells the helpers the GUI scale, so shapes can be drawn in real screen pixels. Call at the start of every frame. */
	public static void setScale(int guiScale) {
		scale = Math.max(1, guiScale);
	}

	/** Frees the shape textures. Call when the screen closes. */
	public static void releaseTextures() {
		Shapes.release();
	}

	/** A color with the current fade applied. */
	private static int faded(int color) {
		return fade >= 1f ? color : Colors.scaleAlpha(color, fade);
	}

	/** From here until {@link #endDevice}, coordinates are screen pixels instead of GUI pixels. */
	private static void beginDevice(GuiGraphicsExtractor g) {
		if (scale > 1) {
			g.pose().pushMatrix();
			g.pose().scale(1f / scale, 1f / scale);
		}
	}

	private static void endDevice(GuiGraphicsExtractor g) {
		if (scale > 1) {
			g.pose().popMatrix();
		}
	}

	/** The corner radius, in screen pixels, that fits a shape this size: at most half of each side. */
	private static int fitRadius(int radius, int width, int height) {
		return Math.max(0, Math.min(Math.min(radius, MAX_RADIUS), Math.min(width, height) / 2));
	}

	// ------------------------------------------------------------ text

	/** A ready-made text, flat (no drop shadow, which would look like Minecraft), with the fade applied. */
	public static void text(GuiGraphicsExtractor g, Font font, FormattedCharSequence text, int x, int y, int color) {
		int shown = faded(color);
		if (Colors.alpha(shown) >= MIN_TEXT_ALPHA) {
			g.text(font, text, x, y, shown, false);
		}
	}

	/** A letter-spaced label (one text). {@code y} is where a text in the label face is drawn (see {@link UiFont#topY}). */
	public static void label(GuiGraphicsExtractor g, Font font, UiFont.Label label, int x, int y, int color) {
		int shown = faded(color);
		if (Colors.alpha(shown) >= MIN_TEXT_ALPHA) {
			g.text(font, label.text(), x, y, shown, false);
		}
	}

	// ------------------------------------------------------------ plain rectangles

	/** A filled rectangle in GUI pixels, with the fade applied. */
	public static void fill(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		g.fill(x0, y0, x1, y1, faded(color));
	}

	/** A hairline (one screen pixel) horizontal divider. */
	public static void divider(GuiGraphicsExtractor g, int x0, int y, int x1) {
		beginDevice(g);
		g.fill(x0 * scale, y * scale, x1 * scale, y * scale + 1, faded(Theme.DIVIDER));
		endDevice(g);
	}

	/** A vertical gradient, top color to bottom color, in GUI pixels. */
	public static void gradientV(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int top, int bottom) {
		g.fillGradient(x0, y0, x1, y1, faded(top), faded(bottom));
	}

	// ------------------------------------------------------------ smooth rounded shapes

	/** A filled rounded rectangle with smooth, anti-aliased corners. {@code radius} is in GUI pixels. */
	public static void roundRect(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int color) {
		color = faded(color);
		if (x1 <= x0 || y1 <= y0 || Colors.alpha(color) == 0) {
			return;
		}
		beginDevice(g);
		deviceRound(g, x0 * scale, y0 * scale, x1 * scale, y1 * scale, radius * scale, color, Shapes.Part.ALL);
		endDevice(g);
	}

	/** A fully rounded pill (the ends are half circles), for scroll bars and the accent bar. */
	public static void pill(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		color = faded(color);
		if (x1 <= x0 || y1 <= y0 || Colors.alpha(color) == 0) {
			return;
		}
		beginDevice(g);
		int width = (x1 - x0) * scale, height = (y1 - y0) * scale;
		deviceRound(g, x0 * scale, y0 * scale, x1 * scale, y1 * scale, Math.min(width, height) / 2, color, Shapes.Part.ALL);
		endDevice(g);
	}

	/**
	 * An ON/OFF switch: the brand's pink accent track when on, a plain neutral gray track when off, a little brighter
	 * under the mouse, with a round knob that slides to the side matching the state — same as the website's own
	 * toggle switches, so on/off reads at a glance instead of only by track color.
	 */
	public static void toggleSwitch(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, boolean on, boolean hover) {
		pill(g, x0, y0, x1, y1, Colors.withAlpha(on ? Theme.ACCENT : Theme.SWITCH_OFF, hover ? 235 : 195));
		border(g, x0, y0, x1, y1, (y1 - y0) / 2, Colors.withAlpha(Theme.TEXT, 25));

		int knob = (y1 - y0) - 6;
		int knobY0 = y0 + 3;
		int knobX0 = on ? x1 - 3 - knob : x0 + 3;
		pill(g, knobX0, knobY0, knobX0 + knob, knobY0 + knob, Colors.withAlpha(Theme.TEXT, on ? 255 : 210));
	}

	// ------------------------------------------------------------ hit testing

	/** True if (x, y) is inside the box: on its top-left edge counts, on its bottom-right edge does not. */
	public static boolean inside(double x, double y, int x0, int y0, int x1, int y1) {
		return x >= x0 && x < x1 && y >= y0 && y < y1;
	}

	/** Draws a rounded rectangle in screen pixels. The color already has the fade applied. */
	private static void deviceRound(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int color, Shapes.Part part) {
		int r = fitRadius(radius, x1 - x0, y1 - y0);
		if (r <= 0) {
			g.fill(x0, y0, x1, y1, color);
			return;
		}
		Shapes.draw(g, Shapes.fill(r), x0, y0, x1, y1, color, part);
	}

	/**
	 * A rounded rectangle whose color changes from {@code top} to {@code bottom}: the two rounded caps take the end colors and the
	 * straight part between them is a true gradient.
	 */
	public static void roundRectGradient(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int top, int bottom) {
		if (x1 <= x0 || y1 <= y0) {
			return;
		}
		top = faded(top);
		bottom = faded(bottom);
		beginDevice(g);
		int X0 = x0 * scale, Y0 = y0 * scale, X1 = x1 * scale, Y1 = y1 * scale;
		int r = fitRadius(radius * scale, X1 - X0, Y1 - Y0);
		if (r <= 0) {
			g.fillGradient(X0, Y0, X1, Y1, top, bottom);
		} else {
			Shapes.Slice slice = Shapes.fill(r);
			float h = Y1 - Y0;
			int capTop = Colors.lerp(top, bottom, r / h), capBottom = Colors.lerp(top, bottom, 1f - r / h);
			Shapes.draw(g, slice, X0, Y0, X1, Y1, capTop, Shapes.Part.TOP);
			Shapes.draw(g, slice, X0, Y0, X1, Y1, capBottom, Shapes.Part.BOTTOM);
			g.fillGradient(X0, Y0 + r, X1, Y1 - r, capTop, capBottom);
		}
		endDevice(g);
	}

	/** A hairline border round a rounded rectangle ({@link Theme#BORDER_PX} screen pixels thick). Draw it over the fill. */
	public static void border(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int color) {
		color = faded(color);
		if (x1 - x0 < 2 || y1 - y0 < 2 || Colors.alpha(color) == 0) {
			return;
		}
		beginDevice(g);
		int X0 = x0 * scale, Y0 = y0 * scale, X1 = x1 * scale, Y1 = y1 * scale;
		int r = fitRadius(radius * scale, X1 - X0, Y1 - Y0);
		if (r <= 0) {
			int t = Theme.BORDER_PX;
			g.fill(X0, Y0, X1, Y0 + t, color);
			g.fill(X0, Y1 - t, X1, Y1, color);
			g.fill(X0, Y0 + t, X0 + t, Y1 - t, color);
			g.fill(X1 - t, Y0 + t, X1, Y1 - t, color);
		} else {
			Shapes.draw(g, Shapes.ring(r, Theme.BORDER_PX), X0, Y0, X1, Y1, color, Shapes.Part.ALL);
		}
		endDevice(g);
	}

	/** A rounded card: a fill and a hairline border. */
	public static void card(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int fill, int border) {
		roundRect(g, x0, y0, x1, y1, radius, fill);
		border(g, x0, y0, x1, y1, radius, border);
	}

	/** A card that lights up under the mouse: the plain look, or the hover fill and border. */
	public static void hoverCard(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, boolean hover) {
		card(g, x0, y0, x1, y1, radius,
				hover ? Theme.CARD_HOVER_FILL : Theme.CARD_FILL, hover ? Theme.CARD_HOVER_BORDER : Theme.CARD_BORDER);
	}

	/** A chip or button that can be switched on: tinted in its accent colour when selected, else the hover card look. */
	public static void accentCard(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int accent,
			boolean selected, boolean hover) {
		if (selected) {
			card(g, x0, y0, x1, y1, radius, Colors.withAlpha(accent, 60), accent | 0xFF000000);
		} else {
			hoverCard(g, x0, y0, x1, y1, radius, hover);
		}
	}

	/** The window's frosted glass: the translucent panel, a sheen fading down from the top edge, and the border. */
	public static void glass(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius) {
		roundRect(g, x0, y0, x1, y1, radius, Theme.PANEL);
		beginDevice(g);
		int X0 = x0 * scale, Y0 = y0 * scale, X1 = x1 * scale, Y1 = y1 * scale;
		int r = fitRadius(radius * scale, X1 - X0, Y1 - Y0);
		int sheenTop = faded(Theme.SHEEN_TOP), sheenBottom = faded(Theme.SHEEN_BOTTOM);
		if (r > 0) {
			Shapes.draw(g, Shapes.fill(r), X0, Y0, X1, Y1, sheenTop, Shapes.Part.TOP);       // the rounded top edge catches the light
			g.fillGradient(X0, Y0 + r, X1, Y0 + (Y1 - Y0) * 2 / 3, sheenTop, sheenBottom);
		}
		endDevice(g);
		border(g, x0, y0, x1, y1, radius, Theme.BORDER);
	}

	// ------------------------------------------------------------ shadows and glow

	/**
	 * A wide, soft drop shadow under a rounded rectangle: one anti-aliased picture that fades smoothly to nothing. Draw it before
	 * the shape it shadows.
	 *
	 * @param depth how far the shadow spreads, in GUI pixels; {@link Theme#SHADOW_SPREAD} is the wide window shadow
	 */
	public static void shadow(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int depth) {
		soft(g, x0, y0 + Theme.SHADOW_OFFSET_Y, x1, y1 + Theme.SHADOW_OFFSET_Y, radius, depth, Colors.withAlpha(Theme.SHADOW_COLOR, Theme.SHADOW_ALPHA), false);
	}

	/** A soft colored glow round a rounded rectangle. Draw it before the shape. */
	public static void glow(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int rgb, int depth) {
		soft(g, x0, y0, x1, y1, radius, depth, Colors.withAlpha(rgb, Theme.GLOW_ALPHA), true);
	}

	/** A small round dot with a soft glow, for status. {@code cx, cy} is its center. */
	public static void glowDot(GuiGraphicsExtractor g, int cx, int cy, int radius, int rgb) {
		glow(g, cx - radius, cy - radius, cx + radius, cy + radius, radius, rgb, radius + 2);
		roundRect(g, cx - radius, cy - radius, cx + radius, cy + radius, radius, rgb | 0xFF000000);
	}

	private static void soft(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int radius, int depth, int color, boolean hollow) {
		color = faded(color);
		if (x1 <= x0 || y1 <= y0 || Colors.alpha(color) == 0) {
			return;
		}
		beginDevice(g);
		int X0 = x0 * scale, Y0 = y0 * scale, X1 = x1 * scale, Y1 = y1 * scale;
		int r = fitRadius(radius * scale, X1 - X0, Y1 - Y0);
		int spread = Math.max(1, depth * scale);
		Shapes.draw(g, hollow ? Shapes.glow(r, spread) : Shapes.shadow(r, spread), X0 - spread, Y0 - spread, X1 + spread, Y1 + spread, color, Shapes.Part.ALL);
		endDevice(g);
	}

	// ------------------------------------------------------------ backdrop, particles, faces

	/**
	 * Blurs everything drawn so far (the game behind the screen) and dims it. The game can only blur the whole screen, once per
	 * frame, and only what is under the next layer, so call this first, before drawing any panel; the panels are translucent and
	 * let the blur show through.
	 */
	public static void blurBackdrop(GuiGraphicsExtractor g, int width, int height) {
		g.blurBeforeThisStratum();
		fill(g, 0, 0, width, height, Theme.BACKDROP);
		g.nextStratum();                                              // what follows is drawn on top of the blur
	}

	/** The particles of a {@link ParticleField}, as tiny soft squares. {@code originX/originY} is where the field's top-left corner is. */
	public static void particles(GuiGraphicsExtractor g, ParticleField field, int originX, int originY) {
		for (int i = 0; i < field.count(); i++) {
			int alpha = Math.round(field.alpha(i) * Theme.PARTICLE_MAX_ALPHA);
			if (alpha > 0) {
				int px = originX + Math.round(field.x(i)), py = originY + Math.round(field.y(i)), size = Math.round(field.size(i));
				fill(g, px, py, px + size, py + size, Colors.withAlpha(Theme.PARTICLE_COLOR, alpha));
			}
		}
	}

	/** A player's face from their skin (with the hat layer), with the fade applied. */
	public static void head(GuiGraphicsExtractor g, PlayerSkin skin, int x, int y, int size) {
		PlayerFaceExtractor.extractRenderState(g, skin, x, y, size, faded(0xFFFFFFFF));
	}

	// ------------------------------------------------------------ scroll bars and clipping

	/**
	 * A slim scroll bar: a faint pill for the track and a pill for the thumb that brightens while the mouse is over the bar.
	 *
	 * @param hot true while the mouse is over the bar or dragging it
	 */
	public static void scrollbar(GuiGraphicsExtractor g, int x0, int trackTop, int x1, int trackBottom, int thumbTop, int thumbBottom, boolean hot) {
		pill(g, x0, trackTop, x1, trackBottom, Theme.SCROLLBAR_TRACK);
		pill(g, x0, thumbTop, x1, thumbBottom, hot ? Theme.SCROLLBAR_THUMB_HOT : Theme.SCROLLBAR_THUMB);
	}

	/** Everything drawn until {@link #endClip} is cut off outside this rectangle, for scroll areas. Clips nest. */
	public static void beginClip(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		g.enableScissor(x0, y0, x1, y1);
	}

	/** Ends the most recent {@link #beginClip}. */
	public static void endClip(GuiGraphicsExtractor g) {
		g.disableScissor();
	}
}
