package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Tic Tac Toe solver (a copy of OdinClient 1.2.5.beta7's, itself from Floppa Client and Skytils): reads the board from the
 * item frames (maps with an X or an O) on the iron wall, works out the best move with alpha-beta search, and outlines that
 * button in green.
 */
final class TicTacToe {

	private static BlockPos topLeft;
	private static Direction roomFacing;
	private static Board board;
	private static final Map<Integer, ItemFrame> mapped = new HashMap<>();
	private static BlockPos bestMove;

	private TicTacToe() {
	}

	/** The map's colour at its middle: 114 is an X, 33 an O, anything else is not a mark. */
	private static int mark(Minecraft mc, ItemFrame frame) {
		ItemStack item = frame.getItem();
		if (item.isEmpty() || item.getItem() != Items.FILLED_MAP) {
			return -1;
		}
		MapItemSavedData data = MapItem.getSavedData(item, mc.level);
		return data == null ? -1 : data.colors[8256] & 255;
	}

	private static boolean isMarkFrame(Minecraft mc, Entity entity) {
		if (!(entity instanceof ItemFrame frame) || frame.getRotation() != 0) {
			return false;
		}
		BlockPos pos = frame.getPos();
		if (pos.getY() < 70 || pos.getY() > 72) {
			return false;
		}
		int color = mark(mc, frame);
		if (color != 114 && color != 33) {
			return false;
		}
		return mc.level.getBlockState(pos.relative(frame.getDirection().getOpposite())).getBlock() == Blocks.IRON_BLOCK;
	}

	static void tick(Minecraft mc) {
		if (!Dungeons.roomName().contains("Tic Tac Toe")) {
			bestMove = null;
			return;
		}
		List<ItemFrame> frames = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (isMarkFrame(mc, entity)) {
				frames.add((ItemFrame) entity);
			}
		}
		try {
			if (topLeft == null || roomFacing == null || board == null) {
				for (ItemFrame frame : frames) {
					int[] spot = spot(mc, frame);
					if (spot == null) {
						continue;
					}
					int row = spot[0], column = spot[1];
					Board.State owner = mark(mc, frame) == 114 ? Board.State.X : Board.State.O;
					if (board == null) {
						topLeft = frame.getPos().above(row).relative(frame.getDirection().getClockWise(), column);
						roomFacing = frame.getDirection().getOpposite();
						board = new Board();
					}
					board.place(column, row, owner);
					mapped.put(row * Board.WIDTH + column, frame);
				}
				if (board != null) {
					board.turn = frames.size() % 2 == 0 ? Board.State.X : Board.State.O;
				}
			} else if (!board.gameOver) {
				board.turn = frames.size() % 2 == 0 ? Board.State.X : Board.State.O;
				if (board.turn == Board.State.O) {
					for (ItemFrame frame : frames) {
						if (mapped.containsValue(frame)) {
							continue;
						}
						int[] spot = spot(mc, frame);
						if (spot == null) {
							continue;
						}
						Board.State owner = mark(mc, frame) == 114 ? Board.State.X : Board.State.O;
						board.place(spot[1], spot[0], owner);
						mapped.put(spot[0] * Board.WIDTH + spot[1], frame);
					}
					AlphaBeta.run(board);
					int move = board.algorithmBestMove;
					if (move != -1) {
						int column = move % Board.WIDTH, row = move / Board.WIDTH;
						bestMove = topLeft.below(row).relative(roomFacing.getClockWise(), column);
					}
				} else {
					bestMove = null;
				}
			} else {
				bestMove = null;
			}
		} catch (RuntimeException e) {
			bestMove = null;
		}
	}

	/** {row, column} of a frame on the board, or null. */
	private static int[] spot(Minecraft mc, ItemFrame frame) {
		BlockPos pos = frame.getPos();
		BlockPos behind = pos.relative(frame.getDirection().getOpposite());
		int row = switch (pos.getY()) {
			case 72 -> 0;
			case 71 -> 1;
			case 70 -> 2;
			default -> -1;
		};
		if (row < 0) {
			return null;
		}
		int column;
		if (mc.level.getBlockState(behind.relative(frame.getDirection().getCounterClockWise())).getBlock() != Blocks.IRON_BLOCK) {
			column = 2;
		} else if (mc.level.getBlockState(behind.relative(frame.getDirection().getClockWise())).getBlock() != Blocks.IRON_BLOCK) {
			column = 0;
		} else {
			column = 1;
		}
		return new int[] {row, column};
	}

	static void render() {
		if (bestMove != null) {
			WorldDraw.box(new AABB(bestMove), 0xFF00FF00, false, true, 2F, true);
		}
	}

	static void reset() {
		topLeft = null;
		roomFacing = null;
		board = null;
		bestMove = null;
		mapped.clear();
	}

	/** The board and its rules, from Skytils. */
	static final class Board {

		enum State { BLANK, X, O }

		static final int WIDTH = 3;

		private final State[][] cells = new State[WIDTH][WIDTH];
		State turn = State.X;
		State winner;
		Set<Integer> availableMoves = new HashSet<>();
		private int moveCount;
		boolean gameOver;
		int algorithmBestMove = -1;

		Board() {
			for (State[] row : cells) {
				java.util.Arrays.fill(row, State.BLANK);
			}
			winner = State.BLANK;
			for (int i = 0; i < WIDTH * WIDTH; i++) {
				availableMoves.add(i);
			}
		}

		boolean move(int index) {
			return set(index % WIDTH, index / WIDTH, turn);
		}

		boolean place(int x, int y, State player) {
			return set(x, y, player);
		}

		private boolean set(int x, int y, State player) {
			if (gameOver) {
				throw new IllegalStateException("TicTacToe is over. No moves can be played.");
			}
			if (cells[y][x] != State.BLANK) {
				return false;
			}
			cells[y][x] = player;
			moveCount++;
			availableMoves.remove(y * WIDTH + x);
			if (moveCount == WIDTH * WIDTH) {
				winner = State.BLANK;
				gameOver = true;
			}
			checkRow(y);
			checkColumn(x);
			checkDiagonalFromTopLeft(x, y);
			checkDiagonalFromTopRight(x, y);
			turn = turn == State.X ? State.O : State.X;
			return true;
		}

		private void checkRow(int row) {
			for (int i = 1; i < WIDTH; i++) {
				if (cells[row][i] != cells[row][i - 1]) {
					break;
				}
				if (i == WIDTH - 1) {
					winner = turn;
					gameOver = true;
				}
			}
		}

		private void checkColumn(int column) {
			for (int i = 1; i < WIDTH; i++) {
				if (cells[i][column] != cells[i - 1][column]) {
					break;
				}
				if (i == WIDTH - 1) {
					winner = turn;
					gameOver = true;
				}
			}
		}

		private void checkDiagonalFromTopLeft(int x, int y) {
			if (x != y) {
				return;
			}
			for (int i = 1; i < WIDTH; i++) {
				if (cells[i][i] != cells[i - 1][i - 1]) {
					break;
				}
				if (i == WIDTH - 1) {
					winner = turn;
					gameOver = true;
				}
			}
		}

		private void checkDiagonalFromTopRight(int x, int y) {
			if (WIDTH - 1 - x != y) {
				return;
			}
			for (int i = 1; i < WIDTH; i++) {
				if (cells[WIDTH - 1 - i][i] != cells[WIDTH - i][i - 1]) {
					break;
				}
				if (i == WIDTH - 1) {
					winner = turn;
					gameOver = true;
				}
			}
		}

		Board copy() {
			Board board = new Board();
			for (int i = 0; i < WIDTH; i++) {
				board.cells[i] = cells[i].clone();
			}
			board.turn = turn;
			board.winner = winner;
			board.availableMoves = new HashSet<>(availableMoves);
			board.moveCount = moveCount;
			board.gameOver = gameOver;
			return board;
		}
	}

	/** Alpha-beta search over the whole game, from Skytils. */
	static final class AlphaBeta {

		private AlphaBeta() {
		}

		static int run(Board board) {
			return search(board.turn, board, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0);
		}

		private static int search(Board.State player, Board board, double alpha, double beta, int ply) {
			ply++;
			if (board.gameOver) {
				return score(player, board, ply);
			}
			return board.turn == player ? max(player, board, alpha, beta, ply) : min(player, board, alpha, beta, ply);
		}

		private static int max(Board.State player, Board board, double alpha, double beta, int ply) {
			int best = -1;
			for (int move : board.availableMoves) {
				Board next = board.copy();
				next.move(move);
				int score = search(player, next, alpha, beta, ply);
				if (score > alpha) {
					alpha = score;
					best = move;
				}
				if (alpha >= beta) {
					break;
				}
			}
			if (best != -1) {
				board.algorithmBestMove = best;
			}
			return (int) alpha;
		}

		private static int min(Board.State player, Board board, double alpha, double beta, int ply) {
			int best = -1;
			for (int move : board.availableMoves) {
				Board next = board.copy();
				next.move(move);
				int score = search(player, next, alpha, beta, ply);
				if (score < beta) {
					beta = score;
					best = move;
				}
				if (alpha >= beta) {
					break;
				}
			}
			if (best != -1) {
				board.algorithmBestMove = best;
			}
			return (int) beta;
		}

		private static int score(Board.State player, Board board, int ply) {
			Board.State opponent = player == Board.State.X ? Board.State.O : Board.State.X;
			if (board.gameOver && board.winner == player) {
				return 10 - ply;
			}
			if (board.gameOver && board.winner == opponent) {
				return -10 + ply;
			}
			return 0;
		}
	}
}
