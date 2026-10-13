package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Teammate Highlight (a copy of OdinClient 1.2.5.beta7's): in a dungeon, your teammates get an outline and a name tag in the
 * colour of their class (Archer orange, Berserk dark red, Healer pink, Mage blue, Tank green), the tag with the class's first
 * letter when Show Class is on. When Visible off outlines them only while a wall is between you, and draws the tags through
 * walls. In Boss off hides it all in the boss room. The outline is the game's own glowing outline, so its width is the game's.
 */
public final class TeammatesHighlight {

	private TeammatesHighlight() {
	}

	private static boolean shouldRender() {
		FemboyClientConfig config = FemboyClientConfig.get();
		return config.enabled && config.teammatesHighlight && Dungeons.inDungeons() && (config.teammatesInBoss || !Dungeons.inBoss());
	}

	private static Dungeons.Teammate teammate(Entity entity) {
		if (!(entity instanceof Player)) {
			return null;
		}
		for (Dungeons.Teammate one : Dungeons.teammates()) {
			if (one.entity() == entity) {
				return one;
			}
		}
		return null;
	}

	/** True if the game should draw a glowing outline around this entity. Called by the mixin for every entity drawn. */
	public static boolean outlined(Entity entity) {
		if (!(entity instanceof Player) || !FemboyClientConfig.get().teammatesOutline || !shouldRender()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (entity == mc.player || teammate(entity) == null) {
			return false;
		}
		return FemboyClientConfig.get().teammatesWhenVisible || mc.player == null || !mc.player.hasLineOfSight(entity);
	}

	/** The outline colour for this entity (a teammate's class colour), or -1 to keep the game's. */
	public static int outlineColor(Entity entity) {
		if (!outlined(entity)) {
			return -1;
		}
		Dungeons.Teammate one = teammate(entity);
		return one == null ? -1 : one.clazz().color & 0xFFFFFF;
	}

	/** Called every client tick: queues the name tags. */
	public static void tick(Minecraft mc) {
		FemboyClientConfig config = FemboyClientConfig.get();
		if (!config.teammatesName || mc.player == null || !shouldRender()) {
			return;
		}
		for (Dungeons.Teammate one : Dungeons.teammates()) {
			Player entity = one.entity();
			if (entity == null || entity == mc.player || entity.isRemoved() || entity.distanceToSqr(mc.player) >= 2333) {
				continue;
			}
			String text = config.teammatesShowClass ? one.name() + " §e[" + one.clazz().name().charAt(0) + "]" : one.name();
			WorldDraw.text(text, new Vec3(entity.getX(), entity.getY() + 2.6, entity.getZ()), one.clazz().color, 0.05F,
					config.teammatesWhenVisible);
		}
	}
}
