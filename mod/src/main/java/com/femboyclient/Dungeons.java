package com.femboyclient;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the dungeon modules (ported from OdinClient 1.2.5.beta7) need to know about the run you are in: the floor, whether you
 * are in the boss room, your teammates and their classes, and the room you are standing in.
 *
 * The room is found like Odin finds it: the column of blocks at the middle of the room's 32x32 cell is turned into a number
 * (its "core") and looked up in rooms.json; a 1x1 room's rotation comes from the blue terracotta in one of its corners. The
 * cores in rooms.json are Odin's own 26.x ones (block names, not the 1.8 block ids), copied from Odin's 26.2 branch.
 */
public final class Dungeons {

	/** A dungeon class, with the colour Odin gives it. */
	public enum DungeonClass {
		ARCHER(0xFFFFAA00), BERSERK(0xFFAA0000), HEALER(0xFFFF55FF), MAGE(0xFF5555FF), TANK(0xFF00AA00);

		public final int color;

		DungeonClass(int color) {
			this.color = color;
		}

		/** "Archer", the way Hypixel writes it. */
		public String display() {
			return name().charAt(0) + name().substring(1).toLowerCase();
		}
	}

	/** A player in your run. The entity is null while they are too far away to be loaded. */
	public record Teammate(String name, DungeonClass clazz, boolean dead, Player entity) {
	}

	/** The four ways a room can face. dx and dz lead from the room's middle to its blue terracotta corner. */
	public enum Rotation {
		NORTH(15, 15), SOUTH(-15, -15), WEST(15, -15), EAST(-15, 15);

		public final int dx, dz;

		Rotation(int dx, int dz) {
			this.dx = dx;
			this.dz = dz;
		}

		public Rotation rotateY() {
			return switch (this) {
				case NORTH -> EAST;
				case EAST -> SOUTH;
				case SOUTH -> WEST;
				case WEST -> NORTH;
			};
		}

		public Rotation rotateYCCW() {
			return switch (this) {
				case NORTH -> WEST;
				case EAST -> NORTH;
				case SOUTH -> EAST;
				case WEST -> SOUTH;
			};
		}
	}

	/** The room you are in: its name from rooms.json, the middle of its cell, and its rotation (null if not found yet). */
	public record Room(String name, String type, int x, int z, Rotation rotation) {

		/** Odin's Vec2.addRotationCoords: {@code dist} blocks from (x, z) toward the way the room faces. */
		public static int[] offset(int x, int z, Rotation rotation, int dist) {
			return switch (rotation) {
				case NORTH -> new int[] {x, z + dist};
				case WEST -> new int[] {x + dist, z};
				case SOUTH -> new int[] {x, z - dist};
				case EAST -> new int[] {x - dist, z};
			};
		}
	}

	private record RoomData(String name, String type, String shape, List<Integer> cores) {
	}

	private static final Map<Integer, RoomData> CORES = loadRooms();
	private static final Pattern FLOOR = Pattern.compile("The Catacombs \\((\\w+)\\)$");
	private static final Pattern TAB_PLAYER = Pattern.compile("^\\[(\\d+)] (?:\\[\\w+] )*(\\w+) .*?\\((\\w+)(?: (\\w+))*\\)$");
	private static final int ROOM_RETRY_TICKS = 10;

	private static ClientLevel level;
	private static String floor;                      // "F7", "M3", "E", or null
	private static boolean inBoss;
	private static List<Teammate> teammates = List.of();
	private static Room room;
	private static int roomCellX = Integer.MIN_VALUE, roomCellZ = Integer.MIN_VALUE, roomRetry;
	private static int ticks;
	private static final List<java.util.function.Consumer<Room>> ROOM_LISTENERS = new ArrayList<>();

	private Dungeons() {
	}

	private static Map<Integer, RoomData> loadRooms() {
		Map<Integer, RoomData> cores = new HashMap<>();
		try (var in = Dungeons.class.getResourceAsStream("/femboyclient/odin/rooms.json")) {
			if (in == null) {
				return cores;
			}
			List<RoomData> rooms = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
					new TypeToken<List<RoomData>>() { }.getType());
			for (RoomData data : rooms) {
				for (int core : data.cores()) {
					cores.put(core, data);
				}
			}
		} catch (Exception e) {
			FemboyClientMod.LOGGER.warn("could not read the dungeon room list", e);
		}
		return cores;
	}

	/** Called when you walk into a different room (or out of every known room: then with null). */
	public static void onRoomEnter(java.util.function.Consumer<Room> listener) {
		ROOM_LISTENERS.add(listener);
	}

	public static boolean inDungeons() {
		return "Dungeons".equals(FemboyClientMod.PLAYER_TRACKER.knownWorld());
	}

	public static boolean inBoss() {
		return inDungeons() && inBoss;
	}

	/** The floor number (7 for F7 and M7, 0 for the Entrance), or -1 when not known. */
	public static int floorNumber() {
		if (floor == null) {
			return -1;
		}
		return floor.equals("E") ? 0 : Character.getNumericValue(floor.charAt(floor.length() - 1));
	}

	public static boolean isFloor(int number) {
		return floorNumber() == number;
	}

	/** The Floor 7 boss phase from your height (1 to 5), or 0 when you are not in the Floor 7 boss. */
	public static int f7Phase(Minecraft mc) {
		if (!isFloor(7) || !inBoss() || mc.player == null) {
			return 0;
		}
		double y = mc.player.getY();
		return y > 210 ? 1 : y > 155 ? 2 : y > 100 ? 3 : y > 45 ? 4 : 5;
	}

	public static List<Teammate> teammates() {
		return teammates;
	}

	/** The room you are in, or null when it is not known (not in a dungeon, in the boss, or not scanned yet). */
	public static Room room() {
		return room;
	}

	public static String roomName() {
		return room == null ? "Unknown" : room.name();
	}

	/** Called every client tick. Never throws. */
	public static void tick(Minecraft mc) {
		try {
			if (mc.level != level) {
				level = mc.level;
				floor = null;
				inBoss = false;
				teammates = List.of();
				setRoom(null);
				roomCellX = roomCellZ = Integer.MIN_VALUE;
			}
			if (level == null || mc.player == null || !inDungeons()) {
				return;
			}
			if (ticks++ % 10 == 0) {
				readSidebar(mc);
				readTabList(mc);
			}
			inBoss = bossFromPosition(mc);
			scanRoom(mc);
		} catch (RuntimeException e) {
			FemboyClientMod.LOGGER.debug("dungeon state failed", e);
		}
	}

	private static void readSidebar(Minecraft mc) {
		if (floor != null) {
			return;
		}
		for (String line : ScoreboardReader.read(mc).lines()) {
			Matcher m = FLOOR.matcher(line.trim());
			if (m.find()) {
				floor = m.group(1);
				return;
			}
		}
	}

	/** Odin's boss check: each floor's boss room is past a known corner of the map. */
	private static boolean bossFromPosition(Minecraft mc) {
		double x = mc.player.getX(), z = mc.player.getZ();
		int number = floorNumber();
		return switch (number) {
			case 1 -> x > -71 && z > -39;
			case 2, 3, 4 -> x > -39 && z > -39;
			case 5, 6 -> x > -39 && z > -7;
			case 7 -> x > -7 && z > -7;
			default -> false;
		};
	}

	private static void readTabList(Minecraft mc) {
		var connection = mc.getConnection();
		if (connection == null) {
			return;
		}
		Map<String, Teammate> previous = new HashMap<>();
		for (Teammate one : teammates) {
			previous.put(one.name(), one);
		}
		List<Teammate> found = new ArrayList<>();
		for (PlayerInfo info : connection.getListedOnlinePlayers()) {
			if (info.getTabListDisplayName() == null) {
				continue;
			}
			Matcher m = TAB_PLAYER.matcher(ScoreboardReader.strip(info.getTabListDisplayName().getString()).trim());
			if (!m.find()) {
				continue;
			}
			String name = m.group(2), clazz = m.group(3);
			DungeonClass known = classNamed(clazz);
			if (known == null && previous.containsKey(name)) {
				known = previous.get(name).clazz();          // DEAD or EMPTY: keep the class they had
			}
			if (known == null) {
				continue;
			}
			found.add(new Teammate(name, known, clazz.equals("DEAD"), playerNamed(mc, name)));
		}
		teammates = List.copyOf(found);
	}

	private static DungeonClass classNamed(String name) {
		for (DungeonClass clazz : DungeonClass.values()) {
			if (clazz.name().equalsIgnoreCase(name)) {
				return clazz;
			}
		}
		return null;
	}

	private static Player playerNamed(Minecraft mc, String name) {
		for (Player player : mc.level.players()) {
			if (player.getName().getString().equals(name)) {
				return player;
			}
		}
		return null;
	}

	// ------------------------------------------------------------ rooms

	private static void scanRoom(Minecraft mc) {
		if (inBoss) {
			setRoom(null);
			return;
		}
		int cellX = (int) Math.floor((mc.player.getX() + 200) / 32), cellZ = (int) Math.floor((mc.player.getZ() + 200) / 32);
		boolean moved = cellX != roomCellX || cellZ != roomCellZ;
		boolean incomplete = room == null || room.rotation() == null;
		if (!moved && (!incomplete || roomRetry++ < ROOM_RETRY_TICKS)) {
			return;
		}
		roomRetry = 0;
		roomCellX = cellX;
		roomCellZ = cellZ;
		int x = -185 + cellX * 32, z = -185 + cellZ * 32;

		LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
		if (chunk == null) {
			setRoom(null);
			return;
		}
		int[] coreAndTop = core(chunk, x, z);
		RoomData data = CORES.get(coreAndTop[0]);
		if (data == null) {
			setRoom(null);
			return;
		}
		Rotation rotation = "1x1".equals(data.shape()) ? rotation(x, coreAndTop[1], z, data.name()) : null;
		Room found = new Room(data.name(), data.type(), x, z, rotation);
		if (!found.equals(room)) {
			setRoom(found);
		}
	}

	private static void setRoom(Room now) {
		Room before = room;
		room = now;
		if (before != now && (before == null || !before.equals(now))) {
			for (var listener : ROOM_LISTENERS) {
				listener.accept(now);
			}
		}
	}

	/** Odin's WorldScan.getRoomCore: the blocks of one column, top to bottom, hashed. Returns {core, highest block y}. */
	private static int[] core(LevelChunk chunk, int x, int z) {
		StringBuilder text = new StringBuilder(1024);
		boolean foundHighest = false;
		int highest = 0, bedrock = 0;
		for (int y = 140; y >= 12; y--) {
			BlockState state = chunk.getBlockState(new BlockPos(x, y, z));
			if (!foundHighest) {
				if (!state.isAir() && state.getBlock() != Blocks.GOLD_BLOCK) {
					foundHighest = true;
					highest = y;
				} else {
					text.append('0');
				}
			}
			if (foundHighest) {
				if (state.isAir() && bedrock >= 2 && y < 69) {
					text.append("0".repeat(y - 11));
					break;
				}
				if (state.getBlock() == Blocks.BEDROCK) {
					bedrock++;
				} else {
					bedrock = 0;
					if (state.getBlock() == Blocks.OAK_PLANKS || state.getBlock() == Blocks.TRAPPED_CHEST || state.getBlock() == Blocks.CHEST) {
						continue;
					}
				}
				text.append(state.getBlock());
			}
		}
		return new int[] {text.toString().hashCode(), highest};
	}

	/** A 1x1 room faces the way whose corner holds blue terracotta at the room's roof height. Fairy rooms always face south. */
	private static Rotation rotation(int x, int top, int z, String name) {
		if ("Fairy".equals(name)) {
			return Rotation.SOUTH;
		}
		for (Rotation rotation : Rotation.values()) {
			if (level.getBlockState(new BlockPos(x + rotation.dx, top, z + rotation.dz)).getBlock() == Blocks.DYED_TERRACOTTA.pick(net.minecraft.world.item.DyeColor.BLUE)) {
				return rotation;
			}
		}
		return null;
	}
}
