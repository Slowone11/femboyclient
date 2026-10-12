package com.femboyclient;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/**
 * The MansionFinder map in a corner of the screen: the Crystal Hollows seen from straight above, north always up. It shows the four
 * regions, the ground scanned so far (the scan covers every height of a chunk, so a flat map is enough), what the game has loaded round
 * you, the Mansions found with a gold ring, and you. It is drawn only while you are in the Crystal Hollows, and you place it from its
 * screen in the G menu.
 *
 * Everything on it is found by this game (see {@link HollowsSync}); you are drawn from the game itself, so your dot never lags.
 */
final class HollowsHud {

	static final int BG = 0xF0120A11;
	static final int PANEL = 0xF01A121C;
	static final int LINE = 0xFF2A1E30;
	static final int TEXT = 0xFFE6E9EF;
	static final int DIM = 0xFF8B93A5;
	static final int ACCENT = 0xFFFF4FA6;
	static final int BEST_FILL = 0xF0241018;
	static final int COVERED = 0x2846C46A;              // scanned ground: soft green
	static final int SCAN_EDGE = 0xC846C46A;            // and the clean line round it
	static final int SLICE = 0x22FFFFFF;                // the chunks scanned at the height you stand at
	static final int WINDOW = 0xB05AA9E6;               // what the game has loaded round you

	static final int PAD = 4;
	static final int HEADER = 10;
	static final int STRIP = 13;
	static final int ROW = 10;
	static final int MAX_ROWS = 6;
	static final int FOOT = 9;
	static final int MIN_SIZE = 90, MAX_SIZE = 320;

	/** The line at the bottom: where you are, how much is scanned, and how far the loaded chunks reach. Set each frame. */
	private static String scanFoot = "";

	private static volatile List<HollowsMapModel.Structure> local = List.of();

	private HollowsHud() {
	}

	/** What this game has found. Client thread. */
	static void setLocal(List<HollowsMapModel.Structure> found) {
		local = found;
	}

	/** The Mansions this game has found in this lobby. */
	static HollowsMapModel.Model model() {
		return HollowsMapModel.mansionsOnly(local);
	}

	// ------------------------------------------------------------------ layout

	static int panelWidth(int size) {
		return size + 2 * PAD;
	}

	static int rows(HollowsMapModel.Model m, int size) {
		return size >= 110 ? Math.min(MAX_ROWS, m.structures().size()) : 0;
	}

	static int panelHeight(HollowsMapModel.Model m, int size) {
		boolean strip = HollowsMapModel.bestGrotto(m.structures()) != null;
		int rows = rows(m, size);
		return PAD + HEADER + size + PAD + (strip ? STRIP + 3 : 0) + (rows > 0 ? rows * ROW + 3 : 0) + FOOT;
	}

	// ------------------------------------------------------------------ the HUD element

	/** Called by the game every frame. Draws nothing unless you are in the Hollows and MansionFinder is on. */
	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		FemboyClientConfig config = FemboyClientConfig.get();
		if (!config.enabled || !config.hud || mc.player == null || HollowsMapScreen.open
				|| !FemboyClientMod.HOLLOWS.inHollowsWorld() || FemboyClientMod.HOLLOWS.level() != mc.level) {
			return;
		}
		UiDraw.setScale(mc.getWindow().getGuiScale());
		UiDraw.setFade(1f);
		HollowsMapModel.Model shown = model();
		draw(g, mc.font, config.hudX, config.hudY, config.hudSize, shown,
				mc.player.getBlockX(), mc.player.getBlockY(), mc.player.getBlockZ(), mc.player.getName().getString(),
				FemboyClientMod.HOLLOWS.lobbyCode(), false);
		if (McCompat.screen(mc) instanceof net.minecraft.client.gui.screens.ChatScreen) {
			// With the chat open the map can be dragged (see HollowsMapDrag): its edge lights up and the controls are written under it.
			int w = panelWidth(config.hudSize), h = panelHeight(shown, config.hudSize);
			UiDraw.border(g, config.hudX, config.hudY, config.hudX + w, config.hudY + h, 4, ACCENT);
			text(g, mc.font, HollowsMapDrag.HINT, config.hudX + 1, config.hudY + h + 3, DIM, 0.5f);
		}
		scanFoot = "x" + mc.player.getBlockX() + " y" + mc.player.getBlockY() + " z" + mc.player.getBlockZ() + " · "
					+ FemboyClientMod.MANSION_FINDER.coveredCount() * 100 / MansionFinder.totalChunks() + "% scanned · reach "
					+ FemboyClientMod.MANSION_FINDER.reach();
	}

	// ------------------------------------------------------------------ drawing

	private static void text(GuiGraphicsExtractor g, Font font, String s, int x, int y, int color, float scale) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		g.text(font, s, 0, 0, color, false);
		g.pose().popMatrix();
	}

	private static int width(Font font, String s, float scale) {
		return Math.round(font.width(s) * scale);
	}

	private static void dot(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
		UiDraw.roundRect(g, cx - r, cy - r, cx + r, cy + r, r, color);
	}

	/** The flat map: world x and z to the screen, north up. {@code mx, my} is the map's top-left corner. */
	private record Flat(int mx, int my, int size) {
		int x(int worldX) {
			return mx + (int) Math.round(HollowsMapModel.project(worldX, size));
		}

		int y(int worldZ) {
			return my + (int) Math.round(HollowsMapModel.project(worldZ, size));
		}
	}

	/** A rectangle of the world, filled. Drawn in world units so thin strips are not lost to rounding. */
	private static void area(GuiGraphicsExtractor g, Flat f, int x0, int z0, int x1, int z1, int color) {
		float scale = f.size() / (float) (HollowsMapModel.HIGH - HollowsMapModel.LOW);
		g.pose().pushMatrix();
		g.pose().translate(f.mx(), f.my());
		g.pose().scale(scale, scale);
		g.fill(x0 - HollowsMapModel.LOW, z0 - HollowsMapModel.LOW, x1 - HollowsMapModel.LOW, z1 - HollowsMapModel.LOW, color);
		g.pose().popMatrix();
	}

	/** A one pixel line on the screen, from any point to any other. */
	private static void line(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int color) {
		double dx = x1 - x0, dy = y1 - y0;
		float length = (float) Math.hypot(dx, dy);
		if (length < 1f) {
			return;
		}
		g.pose().pushMatrix();
		g.pose().translate(x0, y0);
		g.pose().rotate((float) Math.atan2(dy, dx));
		g.fill(0, 0, Math.round(length), 1, color);
		g.pose().popMatrix();
	}

	/** A line between two points of the world. */
	private static void line(GuiGraphicsExtractor g, Flat f, int x0, int z0, int x1, int z1, int color) {
		line(g, f.x(x0), f.y(z0), f.x(x1), f.y(z1), color);
	}

	/** The outline of a rectangle of the world. */
	private static void outline(GuiGraphicsExtractor g, Flat f, int x0, int z0, int x1, int z1, int color) {
		line(g, f, x0, z0, x1, z0, color);
		line(g, f, x1, z0, x1, z1, color);
		line(g, f, x1, z1, x0, z1, color);
		line(g, f, x0, z1, x0, z0, color);
	}

	/** The scanned ground as strips and edge lines in world coordinates, rebuilt only when the scan changes (not every frame). */
	private static final class ScanShape {
		int version = -1, layer = -2;
		final List<int[]> floor = new java.util.ArrayList<>();      // {x0, z0, x1, z1}
		final List<int[]> slice = new java.util.ArrayList<>();      // the same, for the layer you stand in
		final List<int[]> edges = new java.util.ArrayList<>();      // {x0, z0, x1, z1}: a line, one of them equal
	}

	private static final ScanShape SHAPE = new ScanShape();

	private static void rebuild(ScanShape shape, MansionFinder scanner, int layer) {
		int lo = MansionFinder.LOW_CHUNK, hi = MansionFinder.HIGH_CHUNK;
		shape.floor.clear();
		shape.slice.clear();
		shape.edges.clear();
		for (int cz = lo; cz <= hi; cz++) {
			for (int cx = lo; cx <= hi; cx++) {
				if (!scanner.isCovered(cx, cz)) {
					continue;
				}
				int end = cx;
				while (end < hi && scanner.isCovered(end + 1, cz)) {
					end++;
				}
				shape.floor.add(new int[] {cx * 16, cz * 16, end * 16 + 16, cz * 16 + 16});
				cx = end;
			}
		}
		if (layer >= 0) {
			for (int cz = lo; cz <= hi; cz++) {
				for (int cx = lo; cx <= hi; cx++) {
					if ((scanner.layers(cx, cz) >> layer & 1) == 0) {
						continue;
					}
					int end = cx;
					while (end < hi && (scanner.layers(end + 1, cz) >> layer & 1) != 0) {
						end++;
					}
					shape.slice.add(new int[] {cx * 16, cz * 16, end * 16 + 16, cz * 16 + 16});
					cx = end;
				}
			}
		}
		// The edge: a line along every side of a scanned chunk that has no scanned neighbour. Sides in a row are one line.
		for (int cz = lo; cz <= hi; cz++) {
			for (int side = 0; side < 2; side++) {                 // the north side, then the south side
				int neighbour = side == 0 ? cz - 1 : cz + 1, z = (side == 0 ? cz : cz + 1) * 16;
				for (int cx = lo; cx <= hi; cx++) {
					if (!scanner.isCovered(cx, cz) || scanner.isCovered(cx, neighbour)) {
						continue;
					}
					int end = cx;
					while (end < hi && scanner.isCovered(end + 1, cz) && !scanner.isCovered(end + 1, neighbour)) {
						end++;
					}
					shape.edges.add(new int[] {cx * 16, z, end * 16 + 16, z});
					cx = end;
				}
			}
		}
		for (int cx = lo; cx <= hi; cx++) {
			for (int side = 0; side < 2; side++) {                 // the west side, then the east side
				int neighbour = side == 0 ? cx - 1 : cx + 1, x = (side == 0 ? cx : cx + 1) * 16;
				for (int cz = lo; cz <= hi; cz++) {
					if (!scanner.isCovered(cx, cz) || scanner.isCovered(neighbour, cz)) {
						continue;
					}
					int end = cz;
					while (end < hi && scanner.isCovered(cx, end + 1) && !scanner.isCovered(neighbour, end + 1)) {
						end++;
					}
					shape.edges.add(new int[] {x, cz * 16, x, end * 16 + 16});
					cz = end;
				}
			}
		}
	}

	/**
	 * Where you have scanned: one soft green patch with a clean edge round it. The chunks scanned at the height you stand at are lit a
	 * little more, so you can see what is still missing where you are.
	 */
	private static void drawScan(GuiGraphicsExtractor g, Flat f, int playerY) {
		MansionFinder scanner = FemboyClientMod.MANSION_FINDER;
		int mine = MansionFinder.layerOf(playerY);
		int version = scanner.version();
		if (SHAPE.version != version || SHAPE.layer != mine) {
			rebuild(SHAPE, scanner, mine);
			SHAPE.version = version;
			SHAPE.layer = mine;
		}
		for (int[] r : SHAPE.floor) {
			area(g, f, r[0], r[1], r[2], r[3], COVERED);
		}
		for (int[] r : SHAPE.slice) {
			area(g, f, r[0], r[1], r[2], r[3], SLICE);
		}
		for (int[] e : SHAPE.edges) {
			line(g, f, e[0], e[1], e[2], e[3], SCAN_EDGE);
		}
	}

	/** What the game has loaded round you right now, the part being scanned this moment: its outline. */
	private static void drawWindow(GuiGraphicsExtractor g, Flat f, int playerX, int playerZ) {
		int reach = FemboyClientMod.MANSION_FINDER.reach();
		if (reach <= 0) {
			return;
		}
		int pcx = Math.floorDiv(playerX, 16), pcz = Math.floorDiv(playerZ, 16);
		int x0 = Math.max(202, (pcx - reach) * 16), x1 = Math.min(823, (pcx + reach + 1) * 16);
		int z0 = Math.max(202, (pcz - reach) * 16), z1 = Math.min(823, (pcz + reach + 1) * 16);
		outline(g, f, x0, z0, x1, z1, WINDOW);
	}

	/** The map: the Hollows from above with north up, the scanned ground, the Mansions with their footprint, and you. */
	private static void drawMap(GuiGraphicsExtractor g, Font font, int mx, int my, int size, List<HollowsMapModel.Structure> all,
			int playerX, int playerY, int playerZ, String myName) {
		Flat f = new Flat(mx, my, size);
		g.enableScissor(mx, my, mx + size, my + size);

		// The floor, the two lines that make the four regions, and the Nucleus as a dim square in the middle.
		area(g, f, 202, 202, 823, 823, 0x30000000 | (LINE & 0xFFFFFF));
		area(g, f, 202, 512, 823, 514, LINE);
		area(g, f, 512, 202, 514, 823, LINE);
		area(g, f, 466, 466, 560, 560, 0x50000000 | (DIM & 0xFFFFFF));
		drawScan(g, f, playerY);
		outline(g, f, 202, 202, 823, 823, DIM);
		text(g, font, "N", mx + size / 2 - width(font, "N", 0.6f) / 2, my + 2, DIM, 0.6f);

		drawWindow(g, f, playerX, playerZ);

		for (HollowsMapModel.Structure s : all) {
			drawStructure(g, font, f, s, all);
		}
		if (playerX > 0 || playerZ > 0) {
			int px = f.x(playerX), py = f.y(playerZ);
			dot(g, px, py, 3, BG);
			dot(g, px, py, 2, 0xFFFFFFFF);
			text(g, font, myName + " y" + playerY, px + 4, py - 8, TEXT, 0.5f);
		}
		g.disableScissor();
	}

	/** A structure's footprint from above, its outline (gold for a Mansion) and its name over it. */
	private static void drawStructure(GuiGraphicsExtractor g, Font font, Flat f, HollowsMapModel.Structure s, List<HollowsMapModel.Structure> all) {
		int color = 0xFF000000 | HollowsMapModel.colour(s.name());
		int edgeColor = s.starred() ? ACCENT : color;
		int hx = Math.max(2, s.sizeX() / 2), hz = Math.max(2, s.sizeZ() / 2);
		int x0 = s.x() - hx, x1 = s.x() + hx, z0 = s.z() - hz, z1 = s.z() + hz;

		area(g, f, x0, z0, x1, z1, (color & 0xFFFFFF) | 0x50000000);
		outline(g, f, x0, z0, x1, z1, edgeColor);
		String label = HollowsMapModel.label(s, all) + (s.isGrotto() && s.jasper() > 0 ? " · " + s.jasper() : "");
		text(g, font, label, f.x(s.x()) - width(font, label, 0.55f) / 2, f.y(z0) - 7, TEXT, 0.55f);
	}

	/**
	 * Draws the whole panel with its top-left corner at (x, y).
	 *
	 * @param size    the map's width and height, in GUI pixels
	 * @param myName  the name shown next to your own dot
	 * @param code    the server code of this lobby ("m174D") for the heading, or null
	 * @param editing true while you place it: the edge is lit and a made-up lobby shows when nothing has been found
	 */
	static void draw(GuiGraphicsExtractor g, Font font, int x, int y, int size, HollowsMapModel.Model m,
			int playerX, int playerY, int playerZ, String myName, String code, boolean editing) {
		if (editing && m.isEmpty()) {
			m = HollowsMapModel.demo();
		}
		List<HollowsMapModel.Structure> all = m.structures();
		int width = panelWidth(size), height = panelHeight(m, size);

		UiDraw.roundRect(g, x, y, x + width, y + height, 4, PANEL);
		UiDraw.border(g, x, y, x + width, y + height, 4, editing ? ACCENT : LINE);
		text(g, font, "MANSIONFINDER", x + PAD + 1, y + PAD, DIM, 0.6f);
		if (code != null) {
			text(g, font, code, x + width - PAD - 1 - width(font, code, 0.75f), y + PAD - 1, ACCENT, 0.75f);
		}

		int mx = x + PAD, my = y + PAD + HEADER;
		g.fill(mx, my, mx + size, my + size, BG);
		UiDraw.border(g, mx, my, mx + size, my + size, 0, LINE);

		drawMap(g, font, mx, my, size, all, playerX, playerY, playerZ, myName);

		int below = my + size + PAD;
		HollowsMapModel.Structure best = HollowsMapModel.bestGrotto(all);
		if (best != null) {
			UiDraw.roundRect(g, x + PAD, below, x + width - PAD, below + STRIP, 3, BEST_FILL);
			UiDraw.border(g, x + PAD, below, x + width - PAD, below + STRIP, 3, ACCENT);
			String head = best.isMansion() ? "Mansion grotto: " : "Most jasper: ";
			String where = best.x() + ", " + best.y() + ", " + best.z();
			text(g, font, head, x + PAD + 3, below + 3, TEXT, 0.6f);
			text(g, font, where, x + PAD + 3 + width(font, head, 0.6f), below + 3, ACCENT, 0.6f);
			String jasper = best.jasper() + " jasper";
			text(g, font, jasper, x + width - PAD - 3 - width(font, jasper, 0.5f), below + 4, DIM, 0.5f);
			below += STRIP + 3;
		}

		int rows = rows(m, size);
		String foot = editing ? "scanning: 289 chunks loaded · reach 8" : "scanning: " + scanFoot;
		text(g, font, foot, x + PAD + 1, y + height - FOOT + 1, DIM, 0.5f);
		List<HollowsMapModel.Structure> ordered = HollowsMapModel.listOrder(all);
		for (int i = 0; i < rows; i++) {
			HollowsMapModel.Structure s = ordered.get(i);
			int ry = below + i * ROW;
			if (i > 0) {
				g.fill(x + PAD, ry - 1, x + width - PAD, ry, 0x802A3140);
			}
			dot(g, x + PAD + 3, ry + 4, 2, 0xFF000000 | HollowsMapModel.colour(s.name()));
			text(g, font, HollowsMapModel.label(s, all) + (s.starred() ? " ★" : ""), x + PAD + 9, ry + 2,
					s.starred() ? ACCENT : TEXT, 0.6f);
			String coords = s.x() + ", " + s.y() + ", " + s.z();
			text(g, font, coords, x + width - PAD - 1 - width(font, coords, 0.55f), ry + 2, DIM, 0.55f);
		}
	}
}
