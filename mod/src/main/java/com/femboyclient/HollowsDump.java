package com.femboyclient;

import com.google.gson.stream.JsonWriter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Dumps every block the game has loaded round you to a file, from the MansionFinder screen's "Dump nearby blocks" button, so
 * what a structure is made of can be studied. It only reads what the game already holds in memory and writes it to your own
 * disk; nothing is sent anywhere.
 *
 * The file is gzip JSON: {player: {x, y, z}, radius, minY, chunks: [{x, z, missing?, cols: [256 columns]}], palette: [block names]}.
 * A column is x + 16 * z inside its chunk, and is a flat list of runs of non-air blocks: y from, y to (both included), palette index.
 * Client thread only.
 */
final class HollowsDump {

	static final int DEFAULT_RADIUS = 8, MAX_RADIUS = 16;

	private HollowsDump() {
	}

	/** What a dump wrote. */
	record Result(Path file, int chunks, int missing, long runs, int kinds) {
	}

	/** Dumps the chunks within {@code radius} of the one the player stands in. */
	static Result write(Minecraft mc, int radius) throws IOException {
		ClientLevel level = mc.level;
		if (level == null || mc.player == null) {
			throw new IOException("not in a world");
		}
		radius = Math.max(0, Math.min(MAX_RADIUS, radius));
		Path dir = FabricLoader.getInstance().getGameDir().resolve("femboyclient").resolve("dumps");
		Files.createDirectories(dir);
		String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path file = dir.resolve("hollows-" + mc.player.getName().getString() + "-" + stamp + ".json.gz");

		int px = mc.player.getBlockX(), py = mc.player.getBlockY(), pz = mc.player.getBlockZ();
		int pcx = Math.floorDiv(px, 16), pcz = Math.floorDiv(pz, 16);
		Map<Block, Integer> ids = new HashMap<>();
		List<String> palette = new ArrayList<>();
		int chunks = 0, missing = 0, minY = 0;
		long runs = 0;

		try (JsonWriter out = new JsonWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8))) {
			out.beginObject();
			out.name("player").beginObject().name("x").value(px).name("y").value(py).name("z").value(pz).endObject();
			out.name("radius").value(radius);
			out.name("chunks").beginArray();
			for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
				for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
					LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
					out.beginObject().name("x").value(cx).name("z").value(cz);
					if (chunk == null) {
						missing++;
						out.name("missing").value(true).endObject();
						continue;
					}
					chunks++;
					minY = chunk.getMinY();
					out.name("cols").beginArray();
					LevelChunkSection[] sections = chunk.getSections();
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							runs += column(out, sections, chunk.getMinY(), x, z, ids, palette);
						}
					}
					out.endArray().endObject();
				}
			}
			out.endArray();
			out.name("minY").value(minY);
			out.name("palette").beginArray();
			for (String name : palette) {
				out.value(name);
			}
			out.endArray();
			out.endObject();
		}
		return new Result(file, chunks, missing, runs, palette.size());
	}

	/** One column as a flat list of runs: y from, y to, palette index. Returns how many runs. */
	private static long column(JsonWriter out, LevelChunkSection[] sections, int minY, int x, int z, Map<Block, Integer> ids,
			List<String> palette) throws IOException {
		out.beginArray();
		long runs = 0;
		int start = 0, end = 0, current = -1;
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			boolean empty = section.hasOnlyAir();
			for (int y = 0; y < 16; y++) {
				int id = -1;
				if (!empty) {
					Block block = section.getBlockState(x, y, z).getBlock();
					Integer known = ids.get(block);
					if (known == null) {
						String name = BuiltInRegistries.BLOCK.getKey(block).getPath();
						known = name.equals("air") || name.equals("cave_air") || name.equals("void_air") ? -1 : palette.size();
						if (known >= 0) {
							palette.add(name);
						}
						ids.put(block, known);
					}
					id = known;
				}
				int wy = minY + i * 16 + y;
				if (id == current && id >= 0 && wy == end + 1) {
					end = wy;
					continue;
				}
				if (current >= 0) {
					out.value(start).value(end).value(current);
					runs++;
				}
				current = id;
				start = end = wy;
			}
		}
		if (current >= 0) {
			out.value(start).value(end).value(current);
			runs++;
		}
		out.endArray();
		return runs;
	}
}
