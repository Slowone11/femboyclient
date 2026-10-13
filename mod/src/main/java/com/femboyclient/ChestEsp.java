package com.femboyclient;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.Set;

/**
 * Chest ESP (a copy of OdinClient's): every chest and trapped chest in loaded chunks is shown through walls, as a see-through
 * coloured block (Chams) or a box outline (Outline). Only Dungeon / Only Crystal Hollows limit it to those worlds; Hide Clicked
 * stops showing a chest once you have right clicked it, until you change worlds. Drawn with the game's own gizmo boxes, kept on
 * top of the world. Only what you see changes: nothing is sent to the server.
 */
public final class ChestEsp {

	/** The colours the G menu offers, with their names. */
	public static final String[] COLOR_NAMES = {"Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Pink", "White"};
	public static final int[] COLORS = {0xFFFF0000, 0xFFFF8800, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF3366FF, 0xFFFF4FA6, 0xFFFFFFFF};

	private static final double INSET = 1 / 16.0, HEIGHT = 14 / 16.0;      // a chest is a little smaller than a block
	private static final Set<BlockPos> CLICKED = new HashSet<>();
	private static ClientLevel clickedIn;

	private ChestEsp() {
	}

	/** Remembers chests you right click, for Hide Clicked. */
	public static void register() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() && level.getBlockEntity(hit.getBlockPos()) instanceof ChestBlockEntity) {
				CLICKED.add(hit.getBlockPos().immutable());
			}
			return InteractionResult.PASS;
		});
	}

	/** Called every client tick: queues one box per chest, which the game draws until the next tick. */
	public static void tick(Minecraft mc) {
		FemboyClientConfig config = FemboyClientConfig.get();
		ClientLevel level = mc.level;
		if (level != clickedIn) {
			CLICKED.clear();
			clickedIn = level;
		}
		if (!config.enabled || !config.chestEsp || level == null || mc.player == null || !inWorld(config)) {
			return;
		}
		GizmoStyle style = config.chestEspMode == 0
				? GizmoStyle.fill(Colors.withAlpha(config.chestEspColor, 0x66))
				: GizmoStyle.stroke(config.chestEspColor, 2F);
		int radius = mc.options.getEffectiveRenderDistance();
		int cx = mc.player.chunkPosition().x(), cz = mc.player.chunkPosition().z();
		for (int x = cx - radius; x <= cx + radius; x++) {
			for (int z = cz - radius; z <= cz + radius; z++) {
				LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				for (BlockEntity entity : chunk.getBlockEntities().values()) {
					if (entity instanceof ChestBlockEntity && !(config.chestEspHideClicked && CLICKED.contains(entity.getBlockPos()))) {
						BlockPos p = entity.getBlockPos();
						AABB box = new AABB(p.getX() + INSET, p.getY(), p.getZ() + INSET, p.getX() + 1 - INSET, p.getY() + HEIGHT, p.getZ() + 1 - INSET);
						Gizmos.cuboid(box, style).setAlwaysOnTop();
					}
				}
			}
		}
	}

	private static boolean inWorld(FemboyClientConfig config) {
		String world = FemboyClientMod.PLAYER_TRACKER.knownWorld();
		if (config.chestEspOnlyDungeon && !"Dungeons".equals(world)) {
			return false;
		}
		return !config.chestEspOnlyHollows || "Crystal Hollows".equals(world);
	}

	/** Which of {@link #COLORS} this is, or 0 (red) for any other colour. */
	public static int colorIndex(int argb) {
		for (int i = 0; i < COLORS.length; i++) {
			if (COLORS[i] == argb) {
				return i;
			}
		}
		return 0;
	}
}
