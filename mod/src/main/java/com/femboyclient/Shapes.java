package com.femboyclient;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * The smooth shapes of the player screen as game textures. Each shape (a solid rounded rectangle, an outline, a soft shadow) is
 * made once from {@link ShapeMath}, registered with the game's texture manager, and drawn in nine pieces tinted with any color.
 * That makes an edge one anti-aliased pixel wide at the screen's real resolution, and costs nine quads however big the shape is.
 *
 * The textures are made when first needed (on the render thread), kept while the screen is open and released by {@link #release}.
 * Drawing allocates nothing.
 */
final class Shapes {

	/** One made picture: where it lives, how big its corners are, and how big the whole picture is. */
	record Slice(Identifier id, int corner, int size) {
	}

	/** Which of the three bands of a nine-slice to draw. */
	enum Part { TOP, MIDDLE, BOTTOM, ALL }

	private static final Map<Long, Slice> CACHE = new HashMap<>();
	private static int made;

	private Shapes() {
	}

	/** A solid rounded rectangle with this corner radius (in screen pixels). */
	static Slice fill(int radius) {
		return get(0, radius, 0, 0);
	}

	/** An outline of this radius and thickness (in screen pixels). */
	static Slice ring(int radius, int thickness) {
		return get(1, radius, thickness, 0);
	}

	/** A soft shadow that spreads this far past a shape of this radius (in screen pixels). */
	static Slice shadow(int radius, int spread) {
		return get(2, radius, spread, 0);
	}

	/** A soft glow round a shape: like a shadow, but empty under the shape itself. */
	static Slice glow(int radius, int spread) {
		return get(3, radius, spread, 0);
	}

	private static Slice get(int kind, int radius, int a, int b) {
		long key = ((long) kind << 48) | ((long) radius << 32) | ((long) a << 16) | b;
		Slice slice = CACHE.get(key);
		if (slice == null) {
			slice = create(kind, radius, a);
			CACHE.put(key, slice);
		}
		return slice;
	}

	private static Slice create(int kind, int radius, int a) {
		int[] alpha;
		int corner;
		switch (kind) {
			case 1 -> {
				alpha = ShapeMath.ring(radius, a);
				corner = radius;
			}
			case 2 -> {
				alpha = ShapeMath.shadow(radius, a);
				corner = radius + Math.max(1, a);
			}
			case 3 -> {
				alpha = ShapeMath.glow(radius, a);
				corner = radius + Math.max(1, a);
			}
			default -> {
				alpha = ShapeMath.fill(radius);
				corner = radius;
			}
		}
		int size = ShapeMath.size(corner);

		NativeImage image = new NativeImage(size, size, false);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				image.setPixelABGR(x, y, (alpha[y * size + x] << 24) | 0x00FFFFFF);       // white with the shape's opacity; tinted when drawn
			}
		}
		int number = made++;
		DynamicTexture texture = new DynamicTexture(() -> "femboyclient shape " + number, image);
		texture.upload();
		Identifier id = Identifier.fromNamespaceAndPath(Theme.FONT_NAMESPACE, "shape/" + number);
		Minecraft.getInstance().getTextureManager().register(id, texture);
		return new Slice(id, corner, size);
	}

	/**
	 * Draws a slice stretched over a rectangle: the four corners at their own size, the four edges and the middle stretched. The
	 * rectangle must be at least two corners wide and tall. {@code color} tints and sets the opacity.
	 */
	static void draw(GuiGraphicsExtractor g, Slice s, int x0, int y0, int x1, int y1, int color, Part part) {
		int c = s.corner();
		int w = x1 - x0, h = y1 - y0;
		int size = s.size();
		int midW = w - 2 * c, midH = h - 2 * c;
		int rightU = c + 2;                                            // where the right-hand corner starts in the picture

		if (part == Part.TOP || part == Part.ALL) {
			row(g, s, x0, y0, w, c, 0, midW, rightU, color);
		}
		if ((part == Part.MIDDLE || part == Part.ALL) && midH > 0) {
			g.blit(RenderPipelines.GUI_TEXTURED, s.id(), x0, y0 + c, 0f, c, c, midH, c, 2, size, size, color);
			if (midW > 0) {
				g.blit(RenderPipelines.GUI_TEXTURED, s.id(), x0 + c, y0 + c, c, c, midW, midH, 2, 2, size, size, color);
			}
			g.blit(RenderPipelines.GUI_TEXTURED, s.id(), x1 - c, y0 + c, rightU, c, c, midH, c, 2, size, size, color);
		}
		if (part == Part.BOTTOM || part == Part.ALL) {
			row(g, s, x0, y1 - c, w, c, c + 2, midW, rightU, color);
		}
	}

	/** One band of three pieces: left corner, stretched middle, right corner. */
	private static void row(GuiGraphicsExtractor g, Slice s, int x, int y, int w, int c, int v, int midW, int rightU, int color) {
		int size = s.size();
		g.blit(RenderPipelines.GUI_TEXTURED, s.id(), x, y, 0f, v, c, c, c, c, size, size, color);
		if (midW > 0) {
			g.blit(RenderPipelines.GUI_TEXTURED, s.id(), x + c, y, c, v, midW, c, 2, c, size, size, color);
		}
		g.blit(RenderPipelines.GUI_TEXTURED, s.id(), x + w - c, y, rightU, v, c, c, c, c, size, size, color);
	}

	/** Releases every texture made so far. Call when the screen closes. */
	static void release() {
		Minecraft minecraft = Minecraft.getInstance();
		for (Slice slice : CACHE.values()) {
			minecraft.getTextureManager().release(slice.id());
		}
		CACHE.clear();
	}
}
