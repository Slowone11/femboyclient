package com.femboyclient;

import net.minecraft.gizmos.GizmoProperties;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Boxes, lines and text in the world for the ported Odin modules, drawn with the game's own gizmos. Call these from the client
 * tick: the game draws what a tick queued until the next tick. {@code depth} false draws through walls, like Odin's.
 */
final class WorldDraw {

	private WorldDraw() {
	}

	/** Odin's Renderer.drawBox: a filled box, an outline, or both (an alpha of 0 leaves that part out). */
	static void box(AABB box, int argb, boolean fill, boolean outline, float lineWidth, boolean depth) {
		GizmoStyle style;
		if (fill && outline) {
			style = GizmoStyle.strokeAndFill(argb, lineWidth, argb);
		} else if (fill) {
			style = GizmoStyle.fill(argb);
		} else if (outline) {
			style = GizmoStyle.stroke(argb, lineWidth);
		} else {
			return;
		}
		top(Gizmos.cuboid(box, style), depth);
	}

	static void line(Vec3 from, Vec3 to, int argb, float width, boolean depth) {
		top(Gizmos.line(from, to, argb, width), depth);
	}

	/** Text facing the camera. Odin's scale (0.05 for name tags) is turned into the gizmo's. Colour codes (§) work. */
	static void text(String text, Vec3 at, int argb, float odinScale, boolean depth) {
		top(Gizmos.billboardText(text, at, TextGizmo.Style.forColorAndCentered(argb).withScale(odinScale * 10)), depth);
	}

	private static void top(GizmoProperties gizmo, boolean depth) {
		if (!depth) {
			gizmo.setAlwaysOnTop();
		}
	}
}
