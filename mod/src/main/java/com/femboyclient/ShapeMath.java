package com.femboyclient;

/**
 * The pixels of the smooth, anti-aliased shapes the player screen is built from, worked out with distances so that every edge
 * fades over one pixel instead of being a staircase. Each shape is a small square picture that is cut into nine pieces when it
 * is drawn (four corners, four edges and a middle that stretches), so one picture serves a shape of any size.
 *
 * The pictures hold only opacity (0 to 255); the screen tints them with any color. No Minecraft classes are used, so this can be
 * tested on its own.
 */
public final class ShapeMath {

	private ShapeMath() {
	}

	/** The width of a nine-slice picture whose corners are {@code corner} pixels: two corners and a 2-pixel middle. */
	public static int size(int corner) {
		return 2 * corner + 2;
	}

	/** How far {@code (x, y)} is outside a rounded box (negative inside), in pixels. The box is centered on {@code (cx, cy)}. */
	static double distance(double x, double y, double cx, double cy, double halfWidth, double halfHeight, double radius) {
		double qx = Math.abs(x - cx) - (halfWidth - radius);
		double qy = Math.abs(y - cy) - (halfHeight - radius);
		double outside = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
		return outside + Math.min(Math.max(qx, qy), 0) - radius;
	}

	/** Opacity of a pixel from its distance to the edge: 1 well inside, 0 well outside, a one-pixel ramp across the edge. */
	static double coverage(double distance) {
		return Math.max(0, Math.min(1, 0.5 - distance));
	}

	/** A solid rounded rectangle that fills the whole picture, with the given corner radius. */
	public static int[] fill(int radius) {
		int size = size(radius);
		int[] alpha = new int[size * size];
		double half = size / 2.0;
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				alpha[y * size + x] = toByte(coverage(distance(x + 0.5, y + 0.5, half, half, half, half, radius)));
			}
		}
		return alpha;
	}

	/** Only the outline of a rounded rectangle, {@code thickness} pixels wide, on the picture's edge. */
	public static int[] ring(int radius, int thickness) {
		int size = size(radius);
		int[] alpha = new int[size * size];
		double half = size / 2.0;
		int t = Math.max(1, thickness);
		double innerRadius = Math.max(0, radius - t);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				double outer = coverage(distance(x + 0.5, y + 0.5, half, half, half, half, radius));
				double inner = coverage(distance(x + 0.5, y + 0.5, half, half, half - t, half - t, innerRadius));
				alpha[y * size + x] = toByte(outer - inner);
			}
		}
		return alpha;
	}

	/**
	 * A soft shadow: fully opaque inside a rounded rectangle and fading smoothly to nothing over {@code spread} pixels outside it.
	 * The picture is bigger than the shape by {@code spread} on every side, so its corners are {@code radius + spread}.
	 */
	public static int[] shadow(int radius, int spread) {
		return soft(radius, spread, false);
	}

	/**
	 * A soft glow round a rounded rectangle: empty inside the shape (so it does not tint what is drawn over it), brightest right at
	 * the edge and fading smoothly to nothing over {@code spread} pixels outside. Same size rules as {@link #shadow}.
	 */
	public static int[] glow(int radius, int spread) {
		return soft(radius, spread, true);
	}

	private static int[] soft(int radius, int spread, boolean hollow) {
		int corner = radius + Math.max(1, spread);
		int size = size(corner);
		int[] alpha = new int[size * size];
		double half = size / 2.0;
		double shapeHalf = half - Math.max(1, spread);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				double d = distance(x + 0.5, y + 0.5, half, half, shapeHalf, shapeHalf, radius);
				double t = Math.max(0, Math.min(1, d / Math.max(1, spread)));
				double smooth = t * t * (3 - 2 * t);                     // an eased ramp, so there are no visible bands
				double value = 1 - smooth;
				if (hollow) {
					value *= Math.max(0, Math.min(1, d + 0.5));          // 0 inside the shape, 1 outside, a one-pixel ramp at the edge
				}
				alpha[y * size + x] = toByte(value);
			}
		}
		return alpha;
	}

	private static int toByte(double value) {
		return (int) Math.round(Math.max(0, Math.min(1, value)) * 255);
	}
}
