package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chunks the client has loaded, read for {@link MansionFinder}. Purely passive: it reads data the game already holds in
 * memory and sends nothing. Client thread only.
 *
 * Which kinds of block a chunk holds is read from each section's block palette, which lists every kind once with its count,
 * so a whole chunk takes a few microseconds instead of a visit to each of its 40,000 blocks. Only when a chunk already looks
 * like a structure are its blocks visited, to find where the marker blocks are.
 */
public final class MinecraftChunks implements MansionFinder.Chunks {

	/** The name of each kind of block, worked out once: "mossy_cobblestone". */
	private static final Map<Block, String> NAMES = new HashMap<>();

	/** Blocks for a set of names, worked out once: checking a block against a set of Blocks is a lookup, not a string compare. */
	private static final Map<Set<String>, Set<Block>> BLOCK_SETS = new HashMap<>();

	private static Set<Block> blocksOf(Set<String> kinds) {
		return BLOCK_SETS.computeIfAbsent(Set.copyOf(kinds), names -> {
			Set<Block> out = new HashSet<>();
			for (String name : names) {
				BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(name)).ifPresent(out::add);
			}
			return out;
		});
	}

	private static final Set<Block> JASPER_BLOCKS = blocksOf(MansionFinder.JASPER);

	private final Minecraft mc;

	public MinecraftChunks(Minecraft mc) {
		this.mc = mc;
	}

	static String name(Block block) {
		return NAMES.computeIfAbsent(block, b -> BuiltInRegistries.BLOCK.getKey(b).getPath());
	}

	private static boolean isAir(String name) {
		return name.equals("air") || name.equals("cave_air") || name.equals("void_air");
	}

	private LevelChunk chunk(int chunkX, int chunkZ) {
		ClientLevel level = mc.level;
		return level == null ? null : level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
	}

	@Override
	public List<Long> loaded(int chunkX, int chunkZ, int radius) {
		int limit = Math.min(radius, mc.options.getEffectiveRenderDistance());
		List<Long> out = new ArrayList<>();
		for (int ring = 0; ring <= limit; ring++) {
			for (int dx = -ring; dx <= ring; dx++) {
				// The edge columns are walked whole, the ones between only at their two ends: just the ring's own chunks.
				int step = Math.abs(dx) == ring ? 1 : 2 * ring;
				for (int dz = -ring; dz <= ring; dz += step) {
					if (chunk(chunkX + dx, chunkZ + dz) != null) {
						out.add(MansionFinder.key(chunkX + dx, chunkZ + dz));
					}
				}
			}
		}
		return out;
	}

	@Override
	public Integer identity(int chunkX, int chunkZ) {
		LevelChunk chunk = chunk(chunkX, chunkZ);
		return chunk == null ? null : System.identityHashCode(chunk);
	}

	@Override
	public boolean hasJasper(int chunkX, int chunkZ) {
		LevelChunk chunk = chunk(chunkX, chunkZ);
		if (chunk == null) {
			return false;
		}
		for (LevelChunkSection section : chunk.getSections()) {
			// maybeHas reads the section's palette (a handful of entries), never its 4096 blocks.
			if (!section.hasOnlyAir() && section.maybeHas((BlockState state) -> JASPER_BLOCKS.contains(state.getBlock()))) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Set<String> present(int chunkX, int chunkZ, Set<String> kinds) {
		LevelChunk chunk = chunk(chunkX, chunkZ);
		Set<String> out = new HashSet<>();
		if (chunk == null) {
			return out;
		}
		LevelChunkSection[] sections = chunk.getSections();
		for (String kind : kinds) {
			Set<Block> one = blocksOf(Set.of(kind));
			for (LevelChunkSection section : sections) {
				// maybeHas reads the section's palette (a handful of entries), never its 4096 blocks.
				if (!section.hasOnlyAir() && section.maybeHas((BlockState state) -> one.contains(state.getBlock()))) {
					out.add(kind);
					break;
				}
			}
		}
		return out;
	}

	@Override
	public List<int[]> locateBands(int chunkX, int chunkZ, Set<String> kinds) {
		LevelChunk chunk = chunk(chunkX, chunkZ);
		if (chunk == null) {
			return List.of();
		}
		Set<Block> wanted = blocksOf(kinds);
		java.util.TreeMap<Integer, int[]> rows = new java.util.TreeMap<>();       // height -> {minX, minZ, maxX, maxZ, count}
		forEachBlock(chunk, chunkX, chunkZ, wanted, (wx, wy, wz) -> {
			int[] row = rows.get(wy);
			if (row == null) {
				rows.put(wy, new int[] {wx, wz, wx, wz, 1});
			} else {
				row[0] = Math.min(row[0], wx);
				row[1] = Math.min(row[1], wz);
				row[2] = Math.max(row[2], wx);
				row[3] = Math.max(row[3], wz);
				row[4]++;
			}
		});
		// Heights close together are one grotto; a real gap in height is another grotto stacked above or below it.
		List<int[]> bands = new ArrayList<>();
		int[] band = null;
		int last = 0;
		for (Map.Entry<Integer, int[]> entry : rows.entrySet()) {
			int y = entry.getKey();
			int[] row = entry.getValue();
			if (band == null || y - last > MansionFinder.BAND_GAP) {
				band = new int[] {row[0], y, row[1], row[2], y, row[3], 0};
				bands.add(band);
			}
			band[0] = Math.min(band[0], row[0]);
			band[2] = Math.min(band[2], row[1]);
			band[3] = Math.max(band[3], row[2]);
			band[5] = Math.max(band[5], row[3]);
			band[4] = y;
			band[6] += row[4];
			last = y;
		}
		return bands;
	}

	@Override
	public Set<String> kindsBetween(int chunkX, int chunkZ, int lowY, int highY) {
		LevelChunk chunk = chunk(chunkX, chunkZ);
		Set<String> out = new java.util.HashSet<>();
		if (chunk == null) {
			return out;
		}
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			int baseY = chunk.getMinY() + i * 16;
			if (sections[i].hasOnlyAir() || baseY + 15 < lowY || baseY > highY) {
				continue;
			}
			sections[i].getStates().count((state, count) -> {
				String name = name(state.getBlock());
				if (!isAir(name)) {
					out.add(name);
				}
			});
		}
		return out;
	}

	@Override
	public int layers(int chunkX, int chunkZ) {
		LevelChunk chunk = chunk(chunkX, chunkZ);
		if (chunk == null) {
			return 0;
		}
		int mask = 0;
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			int layer = MansionFinder.layerOf(chunk.getMinY() + i * 16);
			if (!sections[i].hasOnlyAir() && layer >= 0 && layer < MansionFinder.LAYERS) {
				mask |= 1 << layer;
			}
		}
		return mask;
	}

	@Override
	public Map<String, Integer> blocks(int chunkX, int chunkZ) {
		LevelChunk chunk = chunk(chunkX, chunkZ);
		if (chunk == null) {
			return null;
		}
		Map<String, Integer> kinds = new HashMap<>();
		for (LevelChunkSection section : chunk.getSections()) {
			if (section.hasOnlyAir()) {
				continue;
			}
			section.getStates().count((state, count) -> {
				String name = name(state.getBlock());
				if (!isAir(name)) {
					kinds.merge(name, count, Integer::sum);
				}
			});
		}
		return kinds;
	}

	@Override
	public int[] locate(int chunkX, int chunkZ, Set<String> kinds) {
		LevelChunk chunk = chunk(chunkX, chunkZ);
		if (chunk == null) {
			return null;
		}
		int[][] box = {null};
		forEachBlock(chunk, chunkX, chunkZ, blocksOf(kinds), (wx, wy, wz) -> {
			if (box[0] == null) {
				box[0] = new int[] {wx, wy, wz, wx, wy, wz};
			} else {
				int[] b = box[0];
				b[0] = Math.min(b[0], wx);
				b[1] = Math.min(b[1], wy);
				b[2] = Math.min(b[2], wz);
				b[3] = Math.max(b[3], wx);
				b[4] = Math.max(b[4], wy);
				b[5] = Math.max(b[5], wz);
			}
		});
		return box[0];
	}

	/** A block position in the world. */
	private interface BlockVisitor {
		void visit(int x, int y, int z);
	}

	/** Calls {@code visitor} with the world position of every block of the chunk that is one of {@code wanted}, bottom to top. */
	private static void forEachBlock(LevelChunk chunk, int chunkX, int chunkZ, Set<Block> wanted, BlockVisitor visitor) {
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section.hasOnlyAir() || !section.maybeHas((BlockState state) -> wanted.contains(state.getBlock()))) {
				continue;
			}
			int baseY = chunk.getMinY() + i * 16;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (!wanted.contains(section.getBlockState(x, y, z).getBlock())) {
							continue;
						}
						visitor.visit(chunkX * 16 + x, baseY + y, chunkZ * 16 + z);
					}
				}
			}
		}
	}
}
