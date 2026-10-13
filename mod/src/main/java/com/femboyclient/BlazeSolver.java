package com.femboyclient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Blaze solver (a copy of OdinClient 1.2.5.beta7's): the blazes are boxed in the order to kill them, lowest health first
 * (highest first in Lower Blaze), the first green, the second orange and the rest faint white, with lines from one to the next.
 * A purple-named blaze counts a quarter of its health, like Odin's.
 */
final class BlazeSolver {

	private static final Pattern NAME = Pattern.compile("^\\[Lv15] Blaze [\\d,]+/([\\d,]+)❤$");
	private static final int FIRST = 0xFF00FF00, SECOND = 0xFFFFA500, OTHER = 0x4DFFFFFF;
	private static final TextColor PURPLE = TextColor.fromLegacyFormat(ChatFormatting.DARK_PURPLE);

	private static final List<ArmorStand> blazes = new ArrayList<>();
	private static final Map<ArmorStand, Integer> hp = new HashMap<>();
	private static boolean lower;

	private BlazeSolver() {
	}

	static void onRoomEnter(Dungeons.Room room) {
		if (room != null && room.name().equals("Lower Blaze")) {
			lower = true;
		}
	}

	static void tick(Minecraft mc) {
		blazes.removeIf(Entity::isRemoved);
		boolean added = false;
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (!(entity instanceof ArmorStand stand) || blazes.contains(stand) || stand.getCustomName() == null) {
				continue;
			}
			Component name = stand.getCustomName();
			Matcher m = NAME.matcher(ScoreboardReader.strip(name.getString()));
			if (!m.find()) {
				continue;
			}
			int health;
			try {
				health = Integer.parseInt(m.group(1).replace(",", ""));
			} catch (NumberFormatException e) {
				continue;
			}
			if (purple(name)) {
				health /= 4;
			}
			hp.put(stand, health);
			blazes.add(stand);
			added = true;
		}
		if (added) {
			Comparator<ArmorStand> byHp = Comparator.comparingInt(hp::get);
			blazes.sort(lower ? byHp.reversed() : byHp);
		}
	}

	private static boolean purple(Component name) {
		Optional<Boolean> found = name.visit((Style style, String text) ->
				PURPLE.equals(style.getColor()) && !text.isEmpty() ? Optional.of(true) : Optional.empty(), Style.EMPTY);
		return found.orElse(false) || name.getString().contains("§5");
	}

	static void render(FemboyClientConfig config) {
		for (int index = 0; index < blazes.size(); index++) {
			ArmorStand entity = blazes.get(index);
			int color = index == 0 ? FIRST : index == 1 ? SECOND : OTHER;
			Vec3 at = entity.position();
			AABB box = new AABB(-0.5, -2, -0.5, 0.5, 0, 0.5).move(at);
			WorldDraw.box(box, color, config.puzzleBlazeStyle != 1, config.puzzleBlazeStyle != 0, 2F, true);
			if (config.puzzleBlazeLineNext && index > 0 && index <= config.puzzleBlazeLines) {
				WorldDraw.line(blazes.get(index - 1).position(), box.getCenter(), color, 1F, false);
			}
		}
	}

	static void reset() {
		blazes.clear();
		hp.clear();
		lower = false;
	}
}
