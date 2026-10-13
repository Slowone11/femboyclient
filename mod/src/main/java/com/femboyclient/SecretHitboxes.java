package com.femboyclient;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.WallSkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Set;
import java.util.UUID;

/**
 * Secret Hitboxes (a copy of OdinClient 1.2.5.beta7's): levers, chests and wither essence skulls get a full block hitbox, and
 * buttons one that covers the whole face of the block they are on, so they are easy to aim at and click. Only the box you aim
 * at changes (what the game outlines and clicks), on your screen; walking into them is unchanged, except chests, which Odin
 * made full blocks for walking too. The levers of the Floor 7 lights device keep their own box, like Odin's.
 */
public final class SecretHitboxes {

	/** The wither essence skull's owner. Hypixel shows a different one to 26.x clients than to 1.8 ones, so both are kept. */
	private static final Set<UUID> ESSENCE = Set.of(UUID.fromString("2865274b-3097-394e-8149-ec629c72d850"),
			UUID.fromString("26bb1a8d-7c66-31c6-82d5-a9c04c94fb02"));

	private static final VoxelShape SKULL = Block.box(4, 0, 4, 12, 8, 12);
	private static final VoxelShape WALL_NORTH = Block.box(4, 4, 8, 12, 12, 16), WALL_SOUTH = Block.box(4, 4, 0, 12, 12, 8),
			WALL_WEST = Block.box(8, 4, 4, 16, 12, 12), WALL_EAST = Block.box(0, 4, 4, 8, 12, 12);

	private SecretHitboxes() {
	}

	private static boolean on() {
		FemboyClientConfig config = FemboyClientConfig.get();
		return config.enabled && config.secretHitboxes;
	}

	/** The box to use instead of the block's own, or null to keep its own. Called for every shape the game asks for. */
	public static VoxelShape shape(BlockState state, BlockGetter level, BlockPos pos) {
		if (!(level instanceof ClientLevel)) {
			return null;              // the game's start-up caches and the singleplayer server keep the real shapes
		}
		Block block = state.getBlock();
		if (!(block instanceof LeverBlock || block instanceof ButtonBlock || block instanceof ChestBlock
				|| block instanceof AbstractSkullBlock) || !on()) {
			return null;
		}
		FemboyClientConfig config = FemboyClientConfig.get();
		if (block instanceof LeverBlock) {
			boolean lightsDevice = pos.getX() >= 58 && pos.getX() <= 62 && pos.getY() >= 133 && pos.getY() <= 136 && pos.getZ() == 142;
			return config.secretHitboxesLever && !lightsDevice ? Shapes.block() : null;
		}
		if (block instanceof ButtonBlock) {
			return config.secretHitboxesButton ? button(state) : null;
		}
		if (block instanceof ChestBlock) {
			return config.secretHitboxesChests ? Shapes.block() : null;
		}
		return config.secretHitboxesEssence && isEssence(level, pos) ? Shapes.block() : null;
	}

	/** An essence skull keeps its own small box for walking into, like Odin's. Null for every other block. */
	public static VoxelShape collision(BlockState state, BlockGetter level, BlockPos pos) {
		if (!(level instanceof ClientLevel) || !(state.getBlock() instanceof AbstractSkullBlock) || !on()
				|| !FemboyClientConfig.get().secretHitboxesEssence || !isEssence(level, pos)) {
			return null;
		}
		if (!(state.getBlock() instanceof WallSkullBlock)) {
			return SKULL;
		}
		return switch (state.getValue(WallSkullBlock.FACING)) {
			case SOUTH -> WALL_SOUTH;
			case WEST -> WALL_WEST;
			case EAST -> WALL_EAST;
			default -> WALL_NORTH;
		};
	}

	/** Odin's button box: the whole face, 1/16 deep when pressed and 2/16 when not. */
	private static VoxelShape button(BlockState state) {
		double d = state.getValue(ButtonBlock.POWERED) ? 1 : 2;
		AttachFace face = state.getValue(ButtonBlock.FACE);
		if (face == AttachFace.FLOOR) {
			return Block.box(0, 0, 0, 16, d, 16);
		}
		if (face == AttachFace.CEILING) {
			return Block.box(0, 16 - d, 0, 16, 16, 16);
		}
		Direction facing = state.getValue(ButtonBlock.FACING);
		return switch (facing) {
			case EAST -> Block.box(0, 0, 0, d, 16, 16);
			case WEST -> Block.box(16 - d, 0, 0, 16, 16, 16);
			case SOUTH -> Block.box(0, 0, 0, 16, 16, d);
			default -> Block.box(0, 0, 16 - d, 16, 16, 16);
		};
	}

	/** True if the skull at this spot is a wither essence. */
	public static boolean isEssence(BlockGetter level, BlockPos pos) {
		if (!(level.getBlockEntity(pos) instanceof SkullBlockEntity skull) || skull.getOwnerProfile() == null) {
			return false;
		}
		var profile = skull.getOwnerProfile().partialProfile();
		return profile != null && ESSENCE.contains(profile.id());
	}
}
