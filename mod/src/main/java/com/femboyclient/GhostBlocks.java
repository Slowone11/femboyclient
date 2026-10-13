package com.femboyclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ghost Blocks (a copy of OdinClient 1.2.5.beta7's): while the key is held, the block you look at (up to Range away) turns into
 * air on your screen only, once every Speed milliseconds. Buttons, chests, trapped chests and levers are never removed, and
 * skulls only with Ghost Skulls on (wither essence never). The server still has the block, so it comes back when the server
 * sends it again. F7 Ghost Blocks also opens Odin's fixed shortcuts in the Floor 7 boss, by phase, once a second.
 */
public final class GhostBlocks {

	public static final HandMan.Range SPEED = new HandMan.Range(0, 300, 10);          // milliseconds
	public static final HandMan.Range RANGE = new HandMan.Range(45, 800, 5);          // tenths of a block

	private static final Set<Block> BLACKLIST = Set.of(Blocks.STONE_BUTTON, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.LEVER);

	private static long lastGhost, lastPreBlocks;

	private GhostBlocks() {
	}

	/** Called every client tick. */
	public static void tick(Minecraft mc) {
		FemboyClientConfig config = FemboyClientConfig.get();
		if (!config.enabled || !config.ghostBlocks || mc.player == null || mc.level == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastGhost >= config.ghostBlocksSpeed && McCompat.screen(mc) == null
				&& (!config.ghostBlocksOnlyDungeon || Dungeons.inDungeons())
				&& config.ghostBlocksKey >= 0 && InputConstants.isKeyDown(mc.getWindow(), config.ghostBlocksKey)) {
			lastGhost = now;
			HitResult hit = mc.player.pick(config.ghostBlocksRange / 10.0, 1F, false);
			if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
				toAir(mc, block.getBlockPos(), config);
			}
		}
		if (config.ghostBlocksF7 && now - lastPreBlocks >= 1000) {
			lastPreBlocks = now;
			preBlocks(mc);
		}
	}

	private static void toAir(Minecraft mc, BlockPos pos, FemboyClientConfig config) {
		BlockState state = mc.level.getBlockState(pos);
		Block block = state.getBlock();
		if (state.isAir() || BLACKLIST.contains(block)) {
			return;
		}
		if (block instanceof AbstractSkullBlock && (!config.ghostBlocksSkulls || SecretHitboxes.isEssence(mc.level, pos))) {
			return;
		}
		mc.level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
	}

	private static void preBlocks(Minecraft mc) {
		int phase = Dungeons.f7Phase(mc);
		if (phase == 0) {
			return;
		}
		for (BlockPos pos : BLOCKS.getOrDefault(phase, List.of())) {
			mc.level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		for (BlockPos pos : ENDER_CHESTS.getOrDefault(phase, List.of())) {
			mc.level.setBlock(pos, Blocks.ENDER_CHEST.defaultBlockState(), Block.UPDATE_ALL);
		}
		for (BlockPos pos : GLASS.getOrDefault(phase, List.of())) {
			mc.level.setBlock(pos, Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	private static BlockPos p(int x, int y, int z) {
		return new BlockPos(x, y, z);
	}

	/** Every block from x0 to x1 (both included) at this y and z. */
	private static List<BlockPos> row(int x0, int x1, int y, int z) {
		return java.util.stream.IntStream.rangeClosed(x0, x1).mapToObj(x -> p(x, y, z)).toList();
	}

	private static List<BlockPos> join(List<?>... parts) {
		List<BlockPos> all = new java.util.ArrayList<>();
		for (List<?> part : parts) {
			for (Object one : part) {
				all.add((BlockPos) one);
			}
		}
		return List.copyOf(all);
	}

	private static final Map<Integer, List<BlockPos>> ENDER_CHESTS = Map.of(
			1, List.of(p(77, 221, 35), p(77, 221, 34), p(77, 221, 33)),
			2, List.of(p(101, 169, 46), p(100, 169, 46), p(99, 169, 46)),
			3, List.of(p(56, 114, 111)));

	private static final Map<Integer, List<BlockPos>> GLASS = Map.of(
			1, List.of(p(77, 221, 36), p(78, 221, 36)),
			2, List.of(p(102, 169, 47)),
			3, List.of(p(55, 114, 110), p(55, 114, 111)));

	private static final Map<Integer, List<BlockPos>> BLOCKS = Map.of(
			1, join(java.util.stream.IntStream.rangeClosed(210, 220).mapToObj(y -> p(88, y, 61)).toList(),
					List.of(p(77, 220, 35), p(78, 220, 35))),
			2, join(row(88, 95, 167, 41), row(88, 95, 166, 41), row(88, 95, 165, 41),
					row(88, 95, 167, 40), row(88, 95, 166, 40), row(88, 95, 165, 40),
					List.of(p(101, 168, 47), p(101, 168, 46), p(101, 167, 47), p(101, 166, 47), p(101, 167, 46), p(101, 166, 46))),
			3, join(java.util.stream.IntStream.rangeClosed(52, 58).mapToObj(z -> p(51, 114, z)).toList(),
					java.util.stream.IntStream.rangeClosed(52, 58).mapToObj(z -> p(51, 115, z)).toList(),
					List.of(p(56, 113, 111), p(56, 112, 110), p(56, 111, 110))),
			4, List.of(p(54, 64, 72), p(54, 64, 73), p(54, 63, 73), p(54, 64, 74), p(54, 63, 74)));
}
