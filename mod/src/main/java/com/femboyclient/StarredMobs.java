package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Starred Mobs: in a dungeon, the mobs with a star (✯) in their name, the ones a room needs killed, are shown through walls,
 * as a see-through coloured box (Chams) or the game's glowing outline (Outline).
 * <p>
 * Hypixel does not name the mob itself: the name, star and health float above it on an invisible armor stand. So every tick
 * the armor stands with a ✯ are found, and under each one the closest living mob (not another armor stand, not you, not a
 * real player) whose body reaches up to the name. The chams are queued every frame at the mob's in-between-ticks position,
 * so they move as smoothly as the mob; the outline is the game's own. Only what you see changes: nothing is sent.
 */
public final class StarredMobs {

	/** The star Hypixel puts in the name of a mob a room needs killed. */
	static final String STAR = "✯";

	/** How far below its name a starred mob's feet can be: a Fel (an enderman) is the tallest, near 3 blocks. */
	private static final double REACH_DOWN = 3.5;

	/** How far to the side of its name a starred mob can stand. */
	private static final double REACH_SIDE = 0.8;

	/** Without a frame drawn by {@link #extract} for this long, the chams are queued from the tick instead. */
	private static final long FRAME_FALLBACK_NANOS = 500_000_000L;

	private static final List<Entity> MOBS = new ArrayList<>();
	private static final Set<Entity> MOB_SET = new HashSet<>();
	private static long lastFrameAt;
	private static boolean warned;

	private StarredMobs() {
	}

	/** Hooks up the per-frame drawing, when this game's Fabric API has the event. Call once during client init. */
	public static void register() {
		try {
			net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents.END_EXTRACTION.register(
					context -> extract(context.deltaTracker().getGameTimeDeltaPartialTick(false)));
		} catch (LinkageError e) {
			FemboyClientMod.LOGGER.info("Starred Mobs: no per-frame drawing on this game, the chams follow the tick");
		}
	}

	/** Whether the module is on and you are in a dungeon. */
	private static boolean active() {
		FemboyClientConfig config = FemboyClientConfig.get();
		return config.enabled && config.starredMobs && Dungeons.inDungeons();
	}

	/** Called every client tick: finds the starred mobs, and queues the chams when no frame did. */
	public static void tick(Minecraft mc) {
		MOBS.clear();
		MOB_SET.clear();
		ClientLevel level = mc.level;
		if (level == null || mc.player == null || !active()) {
			return;
		}
		try {
			for (Entity entity : level.entitiesForRendering()) {
				if (entity instanceof ArmorStand stand && starred(stand.getCustomName())) {
					Entity mob = mobUnder(mc, level, stand);
					if (mob != null && MOB_SET.add(mob)) {
						MOBS.add(mob);
					}
				}
			}
			if (System.nanoTime() - lastFrameAt > FRAME_FALLBACK_NANOS) {
				drawChams(1f);
			}
		} catch (RuntimeException e) {
			if (!warned) {
				warned = true;
				FemboyClientMod.LOGGER.warn("Starred Mobs failed, it will keep trying", e);
			}
			MOBS.clear();
			MOB_SET.clear();
		}
	}

	/** Called every frame: queues the chams at each mob's in-between-ticks position, for this frame only. */
	private static void extract(float partialTick) {
		if (MOBS.isEmpty()) {
			lastFrameAt = System.nanoTime();
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		try (AutoCloseable scope = McCompat.perFrameGizmos(mc)) {
			if (scope != null) {
				drawChams(partialTick);
				lastFrameAt = System.nanoTime();
			}
		} catch (Exception e) {
			if (!warned) {
				warned = true;
				FemboyClientMod.LOGGER.warn("Starred Mobs could not draw this frame", e);
			}
		}
	}

	/** A see-through box around each mob, on top of the world, in Chams mode. */
	private static void drawChams(float partialTick) {
		FemboyClientConfig config = FemboyClientConfig.get();
		if (config.starredMobsMode != 0) {
			return;
		}
		GizmoStyle style = GizmoStyle.fill(Colors.withAlpha(config.starredMobsColor, 0x66));
		for (Entity mob : MOBS) {
			if (mob.isRemoved()) {
				continue;
			}
			Vec3 at = mob.getPosition(partialTick);
			double half = mob.getBbWidth() / 2.0;
			AABB box = new AABB(at.x - half, at.y, at.z - half, at.x + half, at.y + mob.getBbHeight(), at.z + half);
			Gizmos.cuboid(box, style).setAlwaysOnTop();
		}
	}

	/** Whether an armor stand's name has the star. */
	static boolean starred(Component name) {
		return name != null && name.getString().contains(STAR);
	}

	/**
	 * The mob a starred name belongs to: of the living mobs whose body is under the name, the one whose head is closest to it.
	 * Not an armor stand, not you, and not a real player (a teammate, or anyone with a real account's id); Hypixel's own
	 * player-shaped mobs (Shadow Assassin, Lost Adventurer) are kept.
	 */
	private static Entity mobUnder(Minecraft mc, ClientLevel level, ArmorStand stand) {
		double x = stand.getX(), y = stand.getY(), z = stand.getZ();
		AABB below = new AABB(x - REACH_SIDE, y - REACH_DOWN, z - REACH_SIDE, x + REACH_SIDE, y + 0.5, z + REACH_SIDE);
		Entity best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Entity entity : level.getEntities(stand, below)) {
			if (!(entity instanceof LivingEntity living) || entity instanceof ArmorStand || entity == mc.player || !living.isAlive()
					|| realPlayer(entity)) {
				continue;
			}
			double dx = entity.getX() - x, dy = entity.getY() + entity.getBbHeight() - y, dz = entity.getZ() - z;
			double distance = dx * dx + dy * dy + dz * dz;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = entity;
			}
		}
		return best;
	}

	/** A real person: a teammate, or a player with a real account's id (version 4); Hypixel's fake players use version 2. */
	private static boolean realPlayer(Entity entity) {
		if (!(entity instanceof Player)) {
			return false;
		}
		for (Dungeons.Teammate one : Dungeons.teammates()) {
			if (one.entity() == entity) {
				return true;
			}
		}
		return entity.getUUID().version() == 4;
	}

	/** True if the game should draw a glowing outline around this entity (Outline mode). Called for every entity drawn. */
	public static boolean outlined(Entity entity) {
		return !MOB_SET.isEmpty() && FemboyClientConfig.get().starredMobsMode == 1 && MOB_SET.contains(entity);
	}

	/** The outline colour for this entity, or -1 to keep the game's. */
	public static int outlineColor(Entity entity) {
		return outlined(entity) ? FemboyClientConfig.get().starredMobsColor & 0xFFFFFF : -1;
	}

	/** How many starred mobs are shown now, for the G menu. */
	static int count() {
		return MOBS.size();
	}
}
