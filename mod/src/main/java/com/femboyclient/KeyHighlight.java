package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;

/**
 * Key Highlight (a copy of OdinClient's): in a dungeon, a box is drawn through walls around the Wither Key (black) and the
 * Blood Key (red), until it is picked up. Not in the boss room. The keys are the armour stands Hypixel names "Wither Key" and
 * "Blood Key"; the box is the block above the stand, where the key floats. Only what you see changes.
 */
public final class KeyHighlight {

	public static final HandMan.Range THICKNESS = new HandMan.Range(0, 200, 1);     // tenths, like Odin's 0.1 steps

	private static final int WITHER = 0xFF000000, BLOOD = 0xFFFF0000;

	private static boolean inBoss;
	private static ClientLevel bossLevel;

	private KeyHighlight() {
	}

	/** A "[BOSS] ..." line in chat means the boss fight has started, so the keys stop being shown. */
	public static void onChatLine(String line) {
		if (line.startsWith("[BOSS] ")) {
			inBoss = true;
			bossLevel = Minecraft.getInstance().level;
		}
	}

	/** Called every client tick: queues a box per key, which the game draws until the next tick. */
	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != bossLevel) {
			inBoss = false;          // a new world (the next run) is never in the boss yet
			bossLevel = level;
		}
		FemboyClientConfig config = FemboyClientConfig.get();
		if (!config.enabled || !config.keyHighlight || level == null || inBoss
				|| !"Dungeons".equals(FemboyClientMod.PLAYER_TRACKER.knownWorld())) {
			return;
		}
		float width = config.keyHighlightThickness / 10F;
		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof ArmorStand) || entity.isRemoved() || entity.getCustomName() == null) {
				continue;
			}
			String name = ScoreboardReader.strip(entity.getCustomName().getString());
			int color = name.equals("Wither Key") ? WITHER : name.equals("Blood Key") ? BLOOD : 0;
			if (color != 0) {
				double x = entity.getX() - 0.5, y = entity.getY() + 1, z = entity.getZ() - 0.5;
				Gizmos.cuboid(new AABB(x, y, z, x + 1, y + 1, z + 1), GizmoStyle.stroke(color, width)).setAlwaysOnTop();
			}
		}
	}
}
