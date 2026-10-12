package com.femboyclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Connects {@link HollowsWalker} to the real game: its key (J by default, changeable in Options > Controls > Femboy Client), the
 * player, the blocks, the movement keys it holds, mining (the drill in the first hotbar slot, the attack key) and teleporting
 * (the Aspect of the Void in the second, the use key). This is the only
 * place Hollows Walk touches the game. It only ever sends "warp cn" as a command. Client thread only.
 *
 * It also feeds {@link HollowsTunnels} the whole time you are in the Hollows, walking or not, so the tunnels of everything the map
 * has scanned are known when the walk starts. That only reads blocks the game already holds.
 */
final class HollowsWalkGame implements HollowsWalker.Game, HollowsPath.Blocks {

	static final HollowsWalker WALKER = new HollowsWalker();

	private static final HollowsWalkGame GAME = new HollowsWalkGame();

	/** Blocks that hurt to walk into or stand on. */
	private static final Set<String> DANGER = Set.of("lava", "fire", "soul_fire", "magma_block", "cactus", "sweet_berry_bush",
			"powder_snow", "campfire", "soul_campfire", "wither_rose");

	/**
	 * Blocks of the Hollows a drill breaks quickly: hard stone, ores, mithril and titanium. Gemstones (stained glass) are counted
	 * apart, they take longer. Everything else solid is taken as unbreakable, like the blocks of a structure.
	 */
	private static final Set<String> STONE = Set.of("stone", "granite", "diorite", "andesite", "polished_diorite", "prismarine",
			"prismarine_bricks", "dark_prismarine", "light_blue_wool", "gray_wool", "cyan_terracotta", "coal_ore", "iron_ore",
			"gold_ore", "redstone_ore", "lapis_ore", "diamond_ore", "emerald_ore");

	private static KeyMapping key;

	/** The tunnels learned in this lobby. */
	static final HollowsTunnels TUNNELS = new HollowsTunnels();

	/** The ways out of the Nucleus, learned from you walking them, kept in femboyclient/hollows-exits.txt. */
	static final HollowsExits EXITS = new HollowsExits(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
			.resolve("femboyclient").resolve("hollows-exits.txt"), text -> GAME.say(text));

	static final long LEARN_BUDGET_NANOS = 3_000_000L;   // time per tick for reading chunks into the tunnels
	static final int LEARN_TICKS = 10;                   // loaded chunks are looked over this often when nothing is waiting
	static final int REFRESH_TICKS = 100;                // the chunks around you are read again this often, for tunnels mined since

	private static ClientLevel learnedLevel;
	private static final Map<Long, Integer> learned = new HashMap<>();
	private static int learnTicks;
	private static boolean learnBehind;

	/** What each kind of block is to a walking player, worked out once per block state. */
	private static final Map<BlockState, HollowsPath.Cell> KINDS = new IdentityHashMap<>();

	private final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
	private boolean holding;
	private boolean attacking;
	private boolean sprintSet;

	// The view turns every frame, not once a tick: the walker says where it should be at the end of the tick, and each frame takes
	// its share of the way there. The game's own view of the player reads the rotation as it is, so steps of a tick would show.
	private boolean steering;
	private float fromYaw, toYaw, fromPitch, toPitch;

	private HollowsWalkGame() {
	}

	/** Registers the key. Call once at start-up, after {@link PlayerInfoKey#register} (which makes the key category). */
	static void register() {
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key." + FemboyClientMod.MOD_ID + ".hollows_walk",
				InputConstants.KEY_J, PlayerInfoKey.category));
	}

	/** Called every client tick: the key switches it on and off, then the walker takes its step. Never throws. */
	static void tick(Minecraft mc) {
		try {
			HollowsWalker.Coverage map = new HollowsWalker.Coverage() {
				@Override
				public boolean covered(int chunkX, int chunkZ) {
					return FemboyClientMod.MANSION_FINDER.isCovered(chunkX, chunkZ);
				}

				@Override
				public int reach() {
					return viewReach(mc);
				}
			};
			while (key != null && key.consumeClick()) {
				if (mc.player == null) {
					continue;
				}
				if (!WALKER.active() && !FemboyClientMod.HOLLOWS_SYNC.scanning()) {
					GAME.say("only works in the Crystal Hollows.");
				} else {
					WALKER.toggle(GAME, map);
				}
			}
			if (mc.player == null) {
				if (WALKER.active()) {
					WALKER.stop(GAME, "you left the game");
				}
				return;
			}
			learn(mc);
			WALKER.tick(GAME, map, FemboyClientMod.HOLLOWS_SYNC.scanning());
		} catch (RuntimeException e) {
			FemboyClientMod.LOGGER.warn("hollows walk failed, stopping it", e);
			try {
				WALKER.stop(GAME, "something went wrong, see the game log");
			} catch (RuntimeException ignored) {
				GAME.release();
			}
		}
	}

	/** True when the game holds this chunk. (The level's own hasChunk says yes to every chunk on the client.) */
	private static boolean loaded(ClientLevel level, int chunkX, int chunkZ) {
		return level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) != null;
	}

	private static int viewReach;
	private static long viewReachAt;

	/**
	 * How far round you the chunks are all loaded, in chunks: the biggest ring that has no chunk missing (outside the Hollows does
	 * not count). Unlike the farthest chunk the game still holds, this is what the server sends wherever you stand. Once a second.
	 */
	private static int viewReach(Minecraft mc) {
		long now = System.nanoTime();
		if (now - viewReachAt < 1_000_000_000L || mc.level == null || mc.player == null) {
			return viewReach;
		}
		viewReachAt = now;
		int px = mc.player.getBlockX() >> 4, pz = mc.player.getBlockZ() >> 4;
		int ring = 1;
		search:
		for (; ring <= 16; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				int step = Math.abs(dx) == ring ? 1 : 2 * ring;
				for (int dz = -ring; dz <= ring; dz += step) {
					if (HollowsTunnels.inside(px + dx, pz + dz) && !loaded(mc.level, px + dx, pz + dz)) {
						break search;
					}
				}
			}
		}
		viewReach = ring - 1;
		return viewReach;
	}

	/**
	 * Reads loaded chunks of the Hollows into {@link #TUNNELS}, nearest first, each again when the game loads it anew, and the ones
	 * around you every {@link #REFRESH_TICKS}. Forgets them all in a new lobby.
	 */
	private static void learn(Minecraft mc) {
		ClientLevel level = mc.level;
		boolean here = FemboyClientMod.HOLLOWS_SYNC.scanning() && level != null;
		if (!here || level != learnedLevel) {
			if (learnedLevel != null) {
				TUNNELS.reset();
				learned.clear();
			}
			learnedLevel = here ? level : null;
			if (!here) {
				return;
			}
		}
		EXITS.observe(mc.player.getX(), mc.player.getY(), mc.player.getZ());
		int playerChunkX = mc.player.getBlockX() >> 4, playerChunkZ = mc.player.getBlockZ() >> 4;
		learnTicks++;
		if (learnTicks % REFRESH_TICKS == 0) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					learned.remove(MansionFinder.key(playerChunkX + dx, playerChunkZ + dz));
				}
			}
			learnBehind = true;
		}
		if (learnBehind || learnTicks % LEARN_TICKS == 0) {
			learnBehind = readChunks(level, playerChunkX, playerChunkZ, Math.min(32, mc.options.getEffectiveRenderDistance()) + 1);
		}
	}

	/** Reads the loaded chunks not read yet, nearest first. True when it ran out of time with some left. */
	private static boolean readChunks(ClientLevel level, int playerChunkX, int playerChunkZ, int radius) {
		long stop = System.nanoTime() + LEARN_BUDGET_NANOS;
		for (int ring = 0; ring <= radius; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				int step = Math.abs(dx) == ring ? 1 : 2 * ring;
				for (int dz = -ring; dz <= ring; dz += Math.max(1, step)) {
					int chunkX = playerChunkX + dx, chunkZ = playerChunkZ + dz;
					if (!HollowsTunnels.inside(chunkX, chunkZ)) {
						continue;
					}
					LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
					if (chunk == null) {
						continue;
					}
					long key = MansionFinder.key(chunkX, chunkZ);
					Integer identity = System.identityHashCode(chunk);
					if (identity.equals(learned.get(key))) {
						continue;
					}
					if (System.nanoTime() > stop) {
						return true;
					}
					TUNNELS.record(chunkX, chunkZ, read(level, chunk, chunkX, chunkZ));
					learned.put(key, identity);
				}
			}
		}
		return false;
	}

	/** One chunk as {@link HollowsTunnels#record} takes it. */
	private static byte[][] read(ClientLevel level, LevelChunk chunk, int chunkX, int chunkZ) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		LevelChunkSection[] sections = chunk.getSections();
		byte[][] out = new byte[HollowsTunnels.SECTIONS][];
		for (int k = 0; k < HollowsTunnels.SECTIONS; k++) {
			int baseY = (HollowsTunnels.LOW_SECTION + k) * 16;
			int i = (baseY - chunk.getMinY()) >> 4;
			if (i < 0 || i >= sections.length || sections[i].hasOnlyAir()) {
				continue;                                     // nothing but air
			}
			LevelChunkSection section = sections[i];
			byte[] codes = new byte[4096];
			for (int ly = 0; ly < 16; ly++) {
				for (int lz = 0; lz < 16; lz++) {
					for (int lx = 0; lx < 16; lx++) {
						BlockState state = section.getBlockState(lx, ly, lz);
						HollowsPath.Cell cell = state.isAir() ? HollowsPath.Cell.OPEN
								: kind(level, state, pos.set(chunkX * 16 + lx, baseY + ly, chunkZ * 16 + lz));
						codes[(ly << 8) | (lz << 4) | lx] = HollowsTunnels.code(cell);
					}
				}
			}
			out[k] = codes;
		}
		return out;
	}

	/** What a block is to a walking player: in the way, ground, open, water, or something that hurts. */
	private static HollowsPath.Cell kind(ClientLevel level, BlockState state, BlockPos pos) {
		HollowsPath.Cell cell = KINDS.get(state);
		if (cell == null) {
			String name = MinecraftChunks.name(state.getBlock());
			if (DANGER.contains(name)) {
				cell = HollowsPath.Cell.DANGER;
			} else {
				VoxelShape shape = state.getCollisionShape(level, pos);
				if (shape.isEmpty()) {
					cell = state.getFluidState().isEmpty() ? HollowsPath.Cell.OPEN : HollowsPath.Cell.WATER;
				} else if (shape.max(Direction.Axis.Y) > 1.0) {
					cell = HollowsPath.Cell.TALL;
				} else if (STONE.contains(name)) {
					cell = HollowsPath.Cell.STONE;
				} else if (name.endsWith("_stained_glass") || name.endsWith("_stained_glass_pane")) {
					cell = HollowsPath.Cell.GEM;
				} else if (shape.max(Direction.Axis.Y) <= 0.5 || name.endsWith("_stairs")) {
					cell = HollowsPath.Cell.LOW;                  // slabs, carpets, stairs: walked onto without a jump
				} else {
					cell = HollowsPath.Cell.SOLID;
				}
			}
			KINDS.put(state, cell);
		}
		return cell;
	}

	/** Every frame (a HUD element that draws nothing): turns the view its share of the way through this tick. Never throws. */
	static void frame(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		try {
			GAME.turnView(delta.getGameTimeDeltaPartialTick(true));
		} catch (RuntimeException e) {
			// the next tick sets the rotation anyway
		}
	}

	private void turnView(float partialTick) {
		LocalPlayer player = player();
		if (!steering || player == null) {
			return;
		}
		float t = Math.max(0, Math.min(1, partialTick));
		player.setYRot(fromYaw + (toYaw - fromYaw) * t);
		player.setXRot(fromPitch + (toPitch - fromPitch) * t);
	}

	/** Stops the walk, e.g. on disconnect. */
	static void stop(String reason) {
		WALKER.stop(GAME, reason);
	}

	private static Minecraft mc() {
		return Minecraft.getInstance();
	}

	private static LocalPlayer player() {
		return mc().player;
	}

	@Override
	public double x() {
		return player().getX();
	}

	@Override
	public double y() {
		return player().getY();
	}

	@Override
	public double z() {
		return player().getZ();
	}

	/** Where the view is turning to, not where this frame has got to, so the walker's next turn starts from its last one. */
	@Override
	public float yaw() {
		return steering ? toYaw : player().getYRot();
	}

	@Override
	public float pitch() {
		return steering ? toPitch : player().getXRot();
	}

	@Override
	public boolean onGround() {
		return player().onGround();
	}

	@Override
	public boolean blocked() {
		return player().horizontalCollision;
	}

	@Override
	public boolean climbing() {
		return player().onClimbable();
	}

	@Override
	public boolean inWater() {
		return player().isInWater();
	}

	@Override
	public boolean busy() {
		return McCompat.screen(mc()) != null;
	}

	@Override
	public HollowsPath.Blocks blocks() {
		return this;
	}

	@Override
	public HollowsTunnels tunnels() {
		return TUNNELS;
	}

	@Override
	public HollowsExits exits() {
		return EXITS;
	}

	@Override
	public HollowsPath.Cell at(int x, int y, int z) {
		ClientLevel level = mc().level;
		if (level == null || level.isOutsideBuildHeight(y) || !loaded(level, x >> 4, z >> 4)) {
			return HollowsPath.Cell.UNLOADED;
		}
		at.set(x, y, z);
		return kind(level, level.getBlockState(at), at);
	}

	@Override
	public void steer(float yaw, float pitch, boolean forward, boolean jump, boolean sprint) {
		LocalPlayer player = player();
		if (steering) {
			// Where the last tick was headed is where this one starts, whether or not every frame got there.
			player.setYRot(toYaw);
			player.setXRot(toPitch);
		}
		fromYaw = player.getYRot();
		fromPitch = player.getXRot();
		toYaw = yaw;
		toPitch = Math.max(-90, Math.min(90, pitch));
		steering = true;
		var options = mc().options;
		options.keyUp.setDown(forward);
		options.keyJump.setDown(jump);
		// Sprint is set on the player, not through its key: with "Sprint: Toggle" in the options, pressing the key every tick would
		// switch sprinting on and off.
		if (sprint) {
			player.setSprinting(true);
			sprintSet = true;
		} else if (sprintSet) {
			player.setSprinting(false);
			sprintSet = false;
		}
		holding = true;
	}

	@Override
	public void release() {
		steering = false;
		if (attacking) {
			attack(false);
		}
		if (!holding) {
			return;
		}
		holding = false;
		var options = mc().options;
		options.keyUp.setDown(false);
		options.keyJump.setDown(false);
		LocalPlayer player = player();
		if (sprintSet && player != null) {
			player.setSprinting(false);
		}
		sprintSet = false;
	}

	@Override
	public void use() {
		KeyMapping.click(InputConstants.getKey(mc().options.keyUse.saveString()));
	}

	@Override
	public void attack(boolean down) {
		mc().options.keyAttack.setDown(down);
		attacking = down;
	}

	@Override
	public void selectSlot(int slot) {
		if (player().getInventory().getSelectedSlot() != slot) {
			player().getInventory().setSelectedSlot(slot);
		}
	}

	@Override
	public long lookingAt() {
		if (mc().hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
				&& hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
			BlockPos pos = hit.getBlockPos();
			return HollowsPath.pack(pos.getX(), pos.getY(), pos.getZ());
		}
		return Long.MIN_VALUE;
	}

	@Override
	public void log(String text) {
		FemboyClientMod.LOGGER.info("hollows walk: {}", text);
	}

	@Override
	public void command(String command) {
		ClientPacketListener connection = mc().getConnection();
		if (connection != null) {
			connection.sendCommand(command);
		}
	}

	@Override
	public void say(String text) {
		Chat.showLater(Component.literal("[Hollows Walk] ").withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(Component.literal(text).withStyle(ChatFormatting.WHITE)));
	}

	@Override
	public long millis() {
		return System.currentTimeMillis();
	}
}
