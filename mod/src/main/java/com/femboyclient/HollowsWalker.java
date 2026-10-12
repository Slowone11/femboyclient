package com.femboyclient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Hollows Walk: walks you around the Crystal Hollows by itself so {@link MansionFinder} gets to see every chunk. Switched on and
 * off with its key (J by default); nothing else stops it except leaving the Hollows.
 *
 * It scans the Hollows a quadrant at a time (north-west Jungle, north-east Mithril Deposits, south-east Precursor Remnants,
 * south-west Goblin Holdout): it leaves the Nucleus into one, scans most of it, then warps back to the Nucleus and does the next.
 *
 * It goes for the spot in the quadrant that brings the most chunks not scanned yet into view for the distance (the server sends
 * every chunk within its view distance, so getting that close is enough). So it heads for the big areas not scanned, not for
 * scraps. A quadrant is done when only scraps are left in it.
 *
 * It gets there the way a miner does: it walks where a cave goes the right way, and otherwise mines a tunnel straight towards the
 * spot ({@link HollowsPath} with digging: stone is cheap to break, gemstone dearer, the blocks of a structure cannot be broken and
 * are gone round). Mining uses the drill in the first hotbar slot. A block that does not break within {@link #MINE_TIMEOUT_MS} is
 * remembered as unbreakable and gone round. {@link HollowsTunnels} remembers the blocks of every chunk that loaded, so the plan
 * also knows what is a little out of view. A chunk it makes no headway towards within {@link #STUCK_MS} is left out for a while.
 *
 * Where it can it teleports instead of walking, with the Aspect of the Void in the second hotbar slot: a right-click sends you
 * {@link #HOP_BLOCKS} blocks the way you look, so it looks at a spot of the path that far ahead whose way there is open and that
 * has ground under it, and clicks. It learns how far a teleport really goes (transmission tuners make it longer), and walks for a
 * while when the clicks do not teleport (no mana).
 *
 * It walks the way a person does: it looks at a point a couple of blocks ahead along the path, which glides along as you walk, so
 * the view turns smoothly round bends; the turn eases in and out (never a snap), it looks up before a jump, and it sprints on
 * straight stretches without switching sprint on and off every step. Slabs and stairs are walked up, only a real step is jumped.
 *
 * When it keeps getting stuck it warps to the Nucleus and goes into the same quadrant again. The tunnels out of the Nucleus are
 * hard to find from inside it, so it leaves by a way out someone has walked from the spawn ({@link HollowsExits}) into the
 * quadrant, when one has been learned, and walks that way exactly as it was walked: down the middle, up the stairs.
 *
 * The game is reached through {@link Game} and {@link Coverage}, so all of this is testable without Minecraft. Client thread only.
 */
final class HollowsWalker {

	/** What the walker needs of the game. */
	interface Game {
		double x();

		double y();

		double z();

		float yaw();

		float pitch();

		boolean onGround();

		/** Walked into a wall this tick. */
		boolean blocked();

		/** In a ladder or vines, where walking into a wall climbs. */
		boolean climbing();

		boolean inWater();

		/** A menu or the chat is open: the walker lets go of the keys and waits. */
		boolean busy();

		/** The blocks as they are now, loaded chunks only. */
		HollowsPath.Blocks blocks();

		/** The tunnels learned in this lobby. */
		HollowsTunnels tunnels();

		/** The ways out of the Nucleus learned so far. */
		HollowsExits exits();

		/** Turns the view to this yaw and pitch over the next tick, and holds (or lets go of) these keys until the next call. */
		void steer(float yaw, float pitch, boolean forward, boolean jump, boolean sprint);

		/** Lets go of every key the walker holds and of the view. */
		void release();

		/** A line for the game log, so a walk can be looked into afterwards. */
		void log(String text);

		/** Right-clicks once with the item in hand. */
		void use();

		/** Holds (or lets go of) the attack button, which mines the block under the crosshair. */
		void attack(boolean down);

		/** Picks a hotbar slot, 0 for the first. */
		void selectSlot(int slot);

		/** The block under the crosshair ({@link HollowsPath#pack}), or {@link Long#MIN_VALUE} when there is none. */
		long lookingAt();

		void command(String command);

		void say(String text);

		long millis();
	}

	/** What has been scanned in this lobby, from {@link MansionFinder}. */
	interface Coverage {
		boolean covered(int chunkX, int chunkZ);

		/** How far the server sends chunks, in chunks; 0 when not known yet. */
		int reach();
	}

	static final String WARP_COMMAND = "warp cn";
	static final int PLAN_NODES_PER_TICK = 2500;     // path positions looked at per tick, so planning never stalls the game
	static final long REPLAN_MS = 6_000;             // a path that stops short of its goal is planned again this often
	static final int MIN_AHEAD = 8;                  // and sooner when fewer positions than this are left on it,
	static final long MIN_REPLAN_MS = 500;           // but not more often than this
	static final long STUCK_MS = 20_000;             // no closer to the chunk in this long: it is left out
	static final double PROGRESS_BLOCKS = 3;         // what counts as closer
	static final long STILL_MS = 2_500;              // not moved at all in this long: plan again from here
	static final int FAILURES_BEFORE_WARP = 3;       // chunks given up on in a row before warping to the Nucleus
	static final int DEFAULT_REACH = 4;              // view distance assumed until the scanner has measured it
	static final long WARP_WAIT_MS = 10_000;         // how long a warp may take
	static final long WARP_COOLDOWN_MS = 20_000;     // and the least time between two
	static final double WARP_MOVED = 24;             // a warp has happened once you are this far from where you stood
	static final int NUCLEUS_X = 513, NUCLEUS_Z = 513;
	static final double NUCLEUS_RADIUS = 48;         // this close to the middle of the Nucleus, there is no need to warp there
	static final int SECTORS = 8;
	static final String[] SECTOR_NAMES = {"west", "north-west", "north", "north-east", "east", "south-east", "south", "south-west"};
	static final double OFF_LEVEL_Y = 1.5;           // this far below the path (above it, past a drop): another level, plan again
	static final int BLOCKED_TICKS = 8;              // pressed against something this long: jump, it is a lip the plan did not see
	static final int DONE_PERCENT = 80;              // a quadrant this much scanned is done
	static final int MIN_GAIN = 4;                   // a spot that brings fewer chunks than this into view is a scrap: the quadrant is done
	static final double GAIN_COST = 48;              // walk added to every spot's cost when weighing chunks gained against the walk
	static final String[] QUADRANT_NAMES = {"north-west (Jungle)", "north-east (Mithril Deposits)",
			"south-east (Precursor Remnants)", "south-west (Goblin Holdout)"};
	static final int[] QUADRANT_EXIT = {1, 3, 5, 7};  // the direction (see sectorOf) of the way out of the Nucleus into each
	static final int DRILL_SLOT = 0;                 // the drill is in the first hotbar slot
	static final int AOTV_SLOT = 1;                  // the Aspect of the Void in the second
	static final double HOP_BLOCKS = 8;              // how far its teleport goes, until a teleport shows it goes further
	static final double HOP_MIN = 6;                 // a teleport is only worth it to a spot at least this far
	static final double HOP_FALL = 3;                // and only with ground at most this far under where it lands
	static final long HOP_WAIT_MS = 600;             // a click that has not teleported you in this long did not work
	static final long HOPS_OFF_MS = 15_000;          // after 3 of those in a row (no mana), walk this long
	static final long MINE_TIMEOUT_MS = 8_000;       // a block not broken in this long cannot be: it is gone round instead
	static final long AT_GOAL_MS = 4_000;            // close enough to a chunk this long and it has not loaded: it is left out
	static final double EYE = 1.62;                  // the eyes are this far above the feet
	static final double TURN_COST = 96;
	static final double AHEAD_COS = 0.5;             // targets within 60 degrees of the way it is going come first              // a chunk straight behind the way you are heading counts this many blocks more

	// How it walks and looks.
	static final int TRACK_AHEAD = 24;               // positions of the path looked at to find where you are on it
	static final double LAST_REACHED = 0.5;          // the end of a path is reached this close (across)
	static final double EDGE_LOOK = 0.8;             // blocks ahead checked for ground before stepping forward
	static final double LOOKAHEAD = 2.2;             // blocks ahead along the path the view aims at
	static final float MAX_TURN = 22;                // degrees per tick the view turns at most
	static final float TURN_EASE = 0.5f;             // how quickly the turning speed follows what is wanted (1 = at once)
	static final float WOBBLE = 1f;                  // degrees the aim drifts either side of the path
	static final float JUMP_LOOK_UP = 12;            // degrees it looks up before jumping up a block
	static final float WALK_ANGLE = 40;              // walks while the path is within this angle of the view, else turns first
	static final float SPRINT_START = 12, SPRINT_STOP = 35;  // sprint starts when this straight, stops when the path bends this much

	private final Random random;
	private boolean active;
	private Long target;
	private List<Long> path;
	private int index;
	private HollowsPath search;
	private List<Long> keep;                         // the part of the old path walked while the new plan is worked out
	private long plannedAt;
	private int failures;
	private final Set<Long> skipped = new HashSet<>();
	private double bestDistance;
	private long bestAt;
	private double lastX, lastZ;
	private long stillSince;
	private long pauseUntil;
	private boolean warping;
	private long warpAt;
	private long lastWarpAt = Long.MIN_VALUE / 2;
	private double warpFromX, warpFromZ;
	private int quadrant = -1;                        // the quadrant being scanned
	private final Set<Integer> doneQuadrants = new HashSet<>();
	private boolean warpWanted;                       // done with a quadrant: warp to the Nucleus for the next one
	private boolean replanNow;                        // plan again at once (new target, a way out followed to its end)
	private boolean pathComplete;                     // the path being walked reaches its goal, so it is walked to the end
	private double headingX, headingZ;               // the way it is going, so it sweeps on instead of turning back and forth
	private long mining = Long.MIN_VALUE, miningSince;
	private final Set<Long> unbreakable = new HashSet<>();
	private long atGoalSince;
	private boolean hopping;                          // clicked, waiting for the teleport
	private long hopAt, nextHopAt, hopsOffUntil;
	private double hopFromX, hopFromY, hopFromZ, hopBlocks = HOP_BLOCKS;
	private int hopFailures;
	private List<Long> exitTrail;                    // the way out of the Nucleus being followed, or null
	private int exitIndex, bestExitIndex;

	private float turnShare;                          // share of the angle left that is turned per tick; differs a little per walk
	private float yawSpeed;
	private float wobble, wobbleTarget;
	private int wobbleTicks;
	private float pitchTarget;
	private int pitchTicks;
	private boolean sprinting;

	HollowsWalker() {
		this(new Random());
	}

	HollowsWalker(Random random) {
		this.random = random;
	}

	boolean active() {
		return active;
	}

	/** The chunk being walked to, as a {@link MansionFinder#key}, or null. For the map. */
	Long target() {
		return target;
	}

	void toggle(Game game, Coverage map) {
		if (active) {
			stop(game, null);
		} else {
			start(game, map);
		}
	}

	void start(Game game, Coverage map) {
		active = true;
		target = null;
		path = null;
		search = null;
		failures = 0;
		skipped.clear();
		warping = false;
		quadrant = -1;
		doneQuadrants.clear();
		warpWanted = false;
		stillSince = game.millis();
		pauseUntil = game.millis() + 1000;              // a moment for the chunks around to come in before setting off
		turnShare = 0.26f + random.nextFloat() * 0.12f;
		yawSpeed = 0;
		wobble = 0;
		wobbleTicks = 0;
		pitchTicks = 0;
		sprinting = false;
		headingX = 0;
		headingZ = 0;
		mining = Long.MIN_VALUE;
		unbreakable.clear();
		exitTrail = null;
		game.say("on. Walking to the chunks not scanned yet.");
		if (nearNucleus(game)) {
			quadrant = nextQuadrant(map);
		} else {
			quadrant = quadrantOf(Math.floorDiv((int) Math.floor(game.x()), 16), Math.floorDiv((int) Math.floor(game.z()), 16));
		}
		enterQuadrant(game, map);
	}

	/** Stops and lets go of the keys. The reason is said in chat, when there is one. */
	void stop(Game game, String reason) {
		if (!active) {
			return;
		}
		active = false;
		target = null;
		path = null;
		search = null;
		warping = false;
		game.release();
		game.say(reason == null ? "off." : "off: " + reason + ".");
	}

	/** Call every client tick. Does nothing while off. */
	void tick(Game game, Coverage map, boolean inHollows) {
		if (!active) {
			return;
		}
		if (!inHollows) {
			stop(game, "you left the Crystal Hollows");
			return;
		}
		if (game.busy()) {
			letGo(game);
			return;
		}
		long now = game.millis();
		lastGame = game;
		watchJumps(game);
		if (warping) {
			letGo(game);
			if (Math.hypot(game.x() - warpFromX, game.z() - warpFromZ) > WARP_MOVED) {
				warping = false;
				skipped.clear();                          // from the Nucleus, chunks that were out of reach may be reachable
				failures = 0;
				enterQuadrant(game, map);
				stillSince = now;
				pauseUntil = now + 800 + random.nextInt(1700);   // a moment to look around after landing, like a person would
			} else if (now - warpAt > WARP_WAIT_MS) {
				warping = false;
				skipped.clear();
				failures = 0;
				game.say("the warp did not move you, walking on from here.");
			}
			return;
		}
		if (now < pauseUntil) {
			letGo(game);
			stillSince = now;
			return;
		}

		int px = (int) Math.floor(game.x()), py = HollowsPath.feetBlock(game.y()), pz = (int) Math.floor(game.z());
		alongExit(game);
		lastReach = map.reach();
		if (target != null && map.covered(cx(target), cz(target))) {
			target = null;                                // scanned: on to the next one
			search = null;
			failures = 0;
		}
		if (target == null && !chooseNext(game, map, now, px, pz)) {
			return;
		}

		plan(game, map, now, px, py, pz);
		follow(game);
		checkProgress(game, map, now);
		report(game, now);
	}

	/** Picks the next chunk to walk to, or warps or stops instead. False when there is nothing to walk to this tick. */
	private boolean chooseNext(Game game, Coverage map, long now, int px, int pz) {
		int pcx = Math.floorDiv(px, 16), pcz = Math.floorDiv(pz, 16);
		if (warpWanted) {
			letGo(game);
			if (nearNucleus(game)) {
				warpWanted = false;                       // already there
				enterQuadrant(game, map);
			} else if (canWarp(game, now)) {
				warpWanted = false;
				warp(game, now, "off to the " + QUADRANT_NAMES[quadrant] + " quadrant");
			}
			return false;
		}
		if (quadrant < 0) {
			quadrant = quadrantOf(pcx, pcz);
		}
		Long next = percentScanned(map, quadrant) >= DONE_PERCENT ? null : bestTarget(map, pcx, pcz);
		if (next == null) {
			return quadrantDone(game, map);
		}
		if (failures >= FAILURES_BEFORE_WARP && canWarp(game, now)) {
			warp(game, now, "stuck " + failures + " times in a row");
			return false;                                 // and back into the same quadrant
		}
		double hx = cx(next) - pcx, hz = cz(next) - pcz, length = Math.hypot(hx, hz);
		if (length > 0) {
			headingX = hx / length;
			headingZ = hz / length;
		}
		target = next;
		atGoalSince = 0;
		search = null;                                    // the path walked so far is kept, so the walk does not stop between chunks
		replanNow = true;
		bestDistance = Double.MAX_VALUE;
		bestAt = now;
		return true;
	}

	/** Only scraps are left in this quadrant: on to the one with the most left, by way of the Nucleus. Stops when all are done. */
	private boolean quadrantDone(Game game, Coverage map) {
		letGo(game);
		doneQuadrants.add(quadrant);
		int done = quadrant;
		int next = nextQuadrant(map);
		if (next < 0) {
			stop(game, anyLeft(map) ? "every quadrant is mostly scanned" : "the whole Crystal Hollows is scanned");
			return false;
		}
		game.say("the " + QUADRANT_NAMES[done] + " quadrant is done (" + percentScanned(map, done) + "% scanned), next the "
				+ QUADRANT_NAMES[next] + ".");
		quadrant = next;
		warpWanted = true;
		return false;
	}

	/** Starts on the chosen quadrant: says so, and when in the Nucleus, takes the way out into it walked before. */
	private void enterQuadrant(Game game, Coverage map) {
		if (quadrant < 0) {
			return;
		}
		exitTrail = nearNucleus(game) ? game.exits().toward(QUADRANT_EXIT[quadrant]) : null;
		exitIndex = 0;
		bestExitIndex = 0;
		game.say("scanning the " + QUADRANT_NAMES[quadrant] + " quadrant (" + percentScanned(map, quadrant) + "% scanned)"
				+ (!nearNucleus(game) ? "." : exitTrail == null ? ", no way out of the Nucleus learned yet." : ", by a way out walked before."));
	}

	/** Moves along the way out of the Nucleus being followed, and lets it go at its end. */
	private void alongExit(Game game) {
		if (exitTrail == null) {
			return;
		}
		for (int k = exitIndex; k < Math.min(exitTrail.size(), exitIndex + 30); k++) {
			long p = exitTrail.get(k);
			double d = Math.sqrt(sq(game.x() - HollowsExits.px(p)) + sq(game.y() - HollowsExits.py(p)) + sq(game.z() - HollowsExits.pz(p)));
			if (d < 2.5) {
				exitIndex = k;
			}
		}
		if (exitIndex >= exitTrail.size() - 2) {
			game.log("out of the Nucleus at " + where(game.x(), game.y(), game.z()));
			exitTrail = null;
			path = null;                                  // that was the way out: plan from here
			replanNow = true;
		}
	}

	/**
	 * Starts a new plan when one is due, and moves the one in progress along. While a path is being walked, the new plan starts a few
	 * positions ahead on it, and those positions are kept, so you keep walking while it is worked out and never turn back.
	 */
	private void plan(Game game, Coverage map, long now, int px, int py, int pz) {
		if (exitTrail != null) {
			if (path == null || index >= path.size()) {
				path = trailPath();                       // the way out as it was walked, from the point of it nearest you
				index = 0;
				pathComplete = true;
				search = null;
				keep = null;
			}
			return;
		}
		boolean walking = path != null && index < path.size();
		boolean due = !walking || replanNow
				|| (path.size() - index < MIN_AHEAD && now - plannedAt >= MIN_REPLAN_MS)
				|| (!pathComplete && now - plannedAt >= REPLAN_MS);
		if (search == null && due) {
			HollowsPath.Blocks blocks = known(game);
			long from;
			keep = null;
			if (walking) {
				int anchor = Math.min(index + 3, path.size() - 1);
				from = path.get(anchor);
				keep = new ArrayList<>(path.subList(index, anchor + 1));
			} else {
				from = startBlock(blocks, game.x(), game.y(), game.z());
				if (from == Long.MIN_VALUE) {
					if (now - noGroundAt > 1000) {
						noGroundAt = now;
						game.log("no ground under you at " + where(game.x(), game.y(), game.z()) + (game.onGround() ? "" : " (in the air)"));
					}
					return;                               // falling, or on something the plan cannot stand on: try again next tick
				}
			}
			int fx = HollowsPath.x(from), fy = HollowsPath.y(from), fz = HollowsPath.z(from);
			search = new HollowsPath(blocks, fx, fy, fz, goal(target, map), true);
			plannedAt = now;
			replanNow = false;
		}
		if (search != null && search.step(PLAN_NODES_PER_TICK)) {
			HollowsPath done = search;
			search = null;
			List<Long> found = done.path();
			pathComplete = done.reachesGoal();
			if (keep != null) {
				keep.addAll(found);
				path = keep;
				index = 0;
			} else if (!found.isEmpty()) {
				path = found;
				index = 0;
			} else if (goal(target, map).distance(px, py, pz) > 0) {
				giveUp();                                 // not one step closer from here, even digging
			} else {
				path = null;                              // there already, the chunk just has not come in yet
				if (atGoalSince == 0) {
					atGoalSince = now;
				} else if (now - atGoalSince > AT_GOAL_MS) {
					game.log("close enough to chunk " + cx(target) + "," + cz(target) + " but it did not load");
					giveUp();
				}
			}
			keep = null;
		}
	}

	private long noGroundAt;

	/**
	 * The block a path starts from: the one under the middle of the player, or, when the middle hangs over an edge, one under
	 * another corner of the player. {@link Long#MIN_VALUE} when there is none.
	 */
	static long startBlock(HollowsPath.Blocks blocks, double x, double y, double z) {
		int feetY = HollowsPath.feetBlock(y);
		double[][] corners = {{0, 0}, {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}, {0.3, 0.3}};
		for (double[] c : corners) {
			int bx = (int) Math.floor(x + c[0]), bz = (int) Math.floor(z + c[1]);
			int standY = HollowsPath.standingY(blocks, bx, feetY, bz);
			if (standY != Integer.MIN_VALUE) {
				return HollowsPath.pack(bx, standY, bz);
			}
		}
		return Long.MIN_VALUE;
	}

	/**
	 * The blocks as they are where they are loaded, and as remembered where they are not, so a path can go through tunnels out of
	 * view. A block that would not break counts as unbreakable.
	 */
	private HollowsPath.Blocks known(Game game) {
		HollowsTunnels tunnels = game.tunnels();
		HollowsPath.Blocks live = game.blocks();
		return (x, y, z) -> {
			if (!unbreakable.isEmpty() && unbreakable.contains(HollowsPath.pack(x, y, z))) {
				return HollowsPath.Cell.SOLID;
			}
			HollowsPath.Cell cell = live.at(x, y, z);
			return cell == HollowsPath.Cell.UNLOADED ? tunnels.at(x, y, z) : cell;
		};
	}

	/** The way out of the Nucleus being followed, from the point of it nearest you on, as positions of a path. */
	private List<Long> trailPath() {
		List<Long> out = new ArrayList<>();
		for (int i = exitIndex; i < exitTrail.size(); i++) {
			long p = exitTrail.get(i);
			long node = HollowsPath.pack((int) Math.floor(HollowsExits.px(p)), HollowsPath.feetBlock(HollowsExits.py(p)),
					(int) Math.floor(HollowsExits.pz(p)));
			if (out.isEmpty() || out.get(out.size() - 1) != node) {
				out.add(node);
			}
		}
		return out;
	}

	/** Holds the keys and turns the view to walk along the path. */
	private void follow(Game game) {
		if (path == null) {
			letGo(game);
			return;
		}
		double x = game.x(), y = game.y(), z = game.z();
		int feetY = HollowsPath.feetBlock(y);
		track(x, y, z, feetY);
		if (index >= path.size()) {
			letGo(game);                                  // end of this path: the next plan takes over
			return;
		}
		long next = path.get(index);
		double below = y - HollowsPath.y(next);           // how far the next position is under you: a planned drop is fine
		if (across(next, x, z) > 4 || (game.onGround() && (below < -OFF_LEVEL_Y || below > HollowsPath.MAX_DROP + 1))) {
			game.log("off the path at " + where(x, y, z) + ", next " + where(next));
			path = null;                                  // knocked or fallen off the path: plan again from here
			search = null;                                // and a plan made from the old one is no good either
			keep = null;
			letGo(game);
			return;
		}

		long[] inTheWay = inTheWay(game.blocks(), x, z, feetY, next);
		if (inTheWay.length > 0 && game.onGround() && across(next, x, z) < 1.8) {
			mine(game, inTheWay);
			return;
		}
		if (mining != Long.MIN_VALUE) {
			mining = Long.MIN_VALUE;                      // the way is open: walk on, the button still held like a player
		}                                                 // digging a tunnel would, until the tunnel is done
		if (attackHeld && game.millis() - minedAt > KEEP_HOLDING_MS) {
			attackHeld = false;
			game.attack(false);
		}

		HollowsPath.Blocks blocks = game.blocks();
		if (hop(game, blocks, x, y, z)) {
			return;
		}
		double[] aim = aim(blocks, x, y, z);
		float want = (float) Math.toDegrees(Math.atan2(aim[1] - z, aim[0] - x)) - 90;
		float yaw = turn(game.yaw(), want);
		float diff = Math.abs(wrap(want - yaw));
		boolean up = height(blocks, next) - y > HollowsPath.STEP_HEIGHT;   // a real step up, not a slab or stairs
		float pitch = look(game.pitch(), up && across(next, x, z) < 2.5);
		boolean onAim = Math.abs(wrap(want - yaw)) < 25;    // the aim line was checked for ground
		boolean safe = !game.onGround() || onAim || safeAhead(game.blocks(), x, z, yaw, feetY);
		boolean forward = diff < WALK_ANGLE && safe;
		if (game.climbing() && !up) {
			forward = false;                              // walking into the wall from a ladder or vines would climb it
		}
		blockedTicks = game.blocked() && game.onGround() && forward ? blockedTicks + 1 : 0;
		boolean jump = game.inWater()
				? HollowsPath.y(next) >= feetY
				: game.onGround() && forward && ((up && across(next, x, z) < 1.5) || blockedTicks >= BLOCKED_TICKS);
		boolean edge = edgeNear(blocks, y);
		sprinting = !edge && forward && diff < SPRINT_STOP && level(blocks, y, 2)
				&& (sprinting || (diff < SPRINT_START && level(blocks, y, 5)));
		game.steer(yaw, pitch, forward, jump, sprinting);
		lastAim = aim;
		lastWant = want;
		lastSafe = safe;
	}

	private int blockedTicks;
	private double[] lastAim;
	private float lastWant;
	private boolean lastSafe = true;

	/**
	 * Finds where you are on the path: the position nearest to you among the next few, and the one after it once you have gone by.
	 * It never goes back, and never past a jump you have not made yet. So the aim is always ahead of you, even when a new plan
	 * starts a little behind where you are.
	 */
	private void track(double x, double y, double z, int feetY) {
		if (index >= path.size()) {
			return;                                       // already at the end: the next plan takes over
		}
		int best = index;
		double bestDistance = Double.MAX_VALUE;
		for (int k = index; k < Math.min(path.size(), index + TRACK_AHEAD); k++) {
			long node = path.get(k);
			double distance = across(node, x, z) + 0.5 * Math.abs(HollowsPath.y(node) - y);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = k;
			}
			if (HollowsPath.y(node) > feetY || HollowsPath.y(node) < feetY - 1) {
				break;                                    // a jump up or a drop: nothing beyond it counts until it is made
			}
		}
		long at = path.get(best);
		if (HollowsPath.y(at) <= feetY && HollowsPath.y(at) >= feetY - 1) {   // not past a drop still to be taken
			if (best + 1 < path.size()) {
				long after = path.get(best + 1);
				double ax = HollowsPath.x(at) + 0.5, az = HollowsPath.z(at) + 0.5;
				double dot = (x - ax) * (HollowsPath.x(after) + 0.5 - ax) + (z - az) * (HollowsPath.z(after) + 0.5 - az);
				if (dot > 0) {
					best++;                               // already past it, heading for the next
				}
			} else if (across(at, x, z) < LAST_REACHED) {
				best = path.size();                       // at the end
			}
		}
		index = best;
	}

	/**
	 * True when stepping forward along this yaw keeps you on ground: the block {@link #EDGE_LOOK} ahead can be stood on (or stepped
	 * up onto), or the path itself drops there. Stops a turn that is not finished yet from walking you off a ledge.
	 */
	private boolean safeAhead(HollowsPath.Blocks blocks, double x, double z, float yaw, int feetY) {
		double rad = Math.toRadians(yaw);
		int ax = (int) Math.floor(x - Math.sin(rad) * EDGE_LOOK), az = (int) Math.floor(z + Math.cos(rad) * EDGE_LOOK);
		if (ax == (int) Math.floor(x) && az == (int) Math.floor(z)) {
			return true;
		}
		if (HollowsPath.standable(blocks, ax, feetY, az) || HollowsPath.standable(blocks, ax, feetY + 1, az)
				|| HollowsPath.standable(blocks, ax, feetY - 1, az) || !HollowsPath.clear(blocks, ax, feetY, az)) {
			return true;                                  // ground, a step up or down, or a wall (walking into a wall drops nobody)
		}
		for (int k = Math.max(0, index - 1); k < Math.min(path.size(), index + 6); k++) {
			long node = path.get(k);
			if (HollowsPath.x(node) == ax && HollowsPath.z(node) == az) {
				return true;                              // the path goes there, a drop on purpose
			}
		}
		return false;
	}

	private static String where(double x, double y, double z) {
		return String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", x, y, z);
	}

	private static String where(long pos) {
		return HollowsPath.x(pos) + " " + HollowsPath.y(pos) + " " + HollowsPath.z(pos);
	}

	/** Notes a move of more than a few blocks in one tick: the server put you somewhere (a setback, or a warp). */
	private void watchJumps(Game game) {
		double moved = Math.sqrt(sq(game.x() - tickX) + sq(game.y() - tickY) + sq(game.z() - tickZ));
		if (tickX != 0 && moved > 3) {
			game.log("moved " + Math.round(moved) + " blocks in one tick, from " + where(tickX, tickY, tickZ) + " to "
					+ where(game.x(), game.y(), game.z()));
		}
		tickX = game.x();
		tickY = game.y();
		tickZ = game.z();
	}

	private double tickX, tickY, tickZ;

	private static double sq(double v) {
		return v * v;
	}

	/** One line a second for the game log while walking: where you are, where it is going, and what it does about it. */
	private void report(Game game, long now) {
		if (now - reportedAt < 1000) {
			return;
		}
		reportedAt = now;
		int ux = (int) Math.floor(game.x()), uy = (int) Math.floor(game.y() + 0.01) - 1, uz = (int) Math.floor(game.z());
		StringBuilder line = new StringBuilder("at ").append(where(game.x(), game.y(), game.z()))
				.append(game.onGround() ? " ground" : " air").append(game.climbing() ? " CLIMBING" : "")
				.append(" under ").append(game.blocks().at(ux, uy, uz)).append('/').append(game.tunnels().at(ux, uy, uz))
				.append(" yaw ").append(Math.round(game.yaw()))
				.append(" | target ").append(target == null ? "-" : cx(target) + "," + cz(target))
				.append(" reach ").append(lastReach)
				.append(" | path ").append(path == null ? "-" : index + "/" + path.size())
				.append(search != null ? " (planning)" : "")
				.append(exitTrail != null ? " way out " + exitIndex + "/" + exitTrail.size() : "")
				.append(mining != Long.MIN_VALUE ? " mining " + where(mining) : "");
		if (path != null && index < path.size()) {
			line.append(" next ").append(where(path.get(index)));
		}
		if (lastAim != null) {
			line.append(" | aim ").append(where(lastAim[0], game.y(), lastAim[1])).append(" want ").append(Math.round(lastWant))
					.append(lastSafe ? "" : " EDGE");
		}
		game.log(line.toString());
	}

	private long reportedAt;
	private int lastReach;

	/**
	 * The blocks to break before stepping to the next position of the path, highest first; none when the way is open. For a step up
	 * that includes the block above your head, so there is room to jump.
	 */
	private long[] inTheWay(HollowsPath.Blocks blocks, double x, double z, int feetY, long next) {
		int nx = HollowsPath.x(next), ny = HollowsPath.y(next), nz = HollowsPath.z(next);
		int px = (int) Math.floor(x), pz = (int) Math.floor(z);
		long[] cells = ny > feetY
				? new long[] {HollowsPath.pack(px, feetY + 2, pz), HollowsPath.pack(nx, ny + 1, nz), HollowsPath.pack(nx, ny, nz)}
				: ny == feetY
						? new long[] {HollowsPath.pack(nx, ny + 1, nz), HollowsPath.pack(nx, ny, nz)}
						: new long[] {HollowsPath.pack(nx, feetY + 1, nz), HollowsPath.pack(nx, feetY, nz)};
		int n = 0;
		for (long cell : cells) {
			HollowsPath.Cell kind = blocks.at(HollowsPath.x(cell), HollowsPath.y(cell), HollowsPath.z(cell));
			if (kind == HollowsPath.Cell.STONE || kind == HollowsPath.Cell.GEM) {
				cells[n++] = cell;
			}
		}
		return java.util.Arrays.copyOf(cells, n);
	}

	/**
	 * Mines the blocks in the way of the next step: the drill in hand, the view eased onto the first of them without any drift, and
	 * the attack button held while the crosshair is on any of them (from where you stand one can hide another, and that one is
	 * mined first). Like a player it holds the button down through the whole tunnel: moving the crosshair between blocks, or
	 * stepping into the gap just mined, does not let go. When none
	 * breaks within {@link #MINE_TIMEOUT_MS} the first is remembered as unbreakable, and the path planned again round it.
	 */
	private void mine(Game game, long[] blocks) {
		long now = game.millis();
		long block = blocks[0];
		if (block != mining) {
			if (mining == Long.MIN_VALUE) {
				game.selectSlot(DRILL_SLOT);
			}
			mining = block;
			miningSince = now;
		}
		if (now - miningSince > MINE_TIMEOUT_MS) {
			game.log("could not break the block at " + where(block) + ", going round it");
			unbreakable.add(block);
			mining = Long.MIN_VALUE;
			game.attack(false);
			path = null;
			search = null;
			return;
		}
		double ex = game.x(), ey = game.y() + EYE, ez = game.z();
		double bx = HollowsPath.x(block) + 0.5, by = HollowsPath.y(block) + 0.5, bz = HollowsPath.z(block) + 0.5;
		float want = (float) Math.toDegrees(Math.atan2(bz - ez, bx - ex)) - 90;
		float wantPitch = (float) -Math.toDegrees(Math.atan2(by - ey, Math.hypot(bx - ex, bz - ez)));
		float yaw = turnSteady(game.yaw(), want);
		float pitch = game.pitch() + Math.max(-12, Math.min(12, (wantPitch - game.pitch()) * 0.35f));
		sprinting = false;
		game.steer(yaw, pitch, false, false, false);
		long looking = game.lookingAt();
		boolean onOne = false;
		for (long b : blocks) {
			onOne |= looking == b;
		}
		offBlockTicks = onOne ? 0 : offBlockTicks + 1;
		attackHeld = onOne || (attackHeld && offBlockTicks <= HOLD_TICKS);
		game.attack(attackHeld);
		minedAt = now;
	}

	static final int HOLD_TICKS = 10;                // the button stays down this long with the crosshair between blocks
	static final long KEEP_HOLDING_MS = 1_000;       // and this long after the last block, walking on down the tunnel
	private long minedAt = Long.MIN_VALUE / 2;

	private int offBlockTicks;
	private boolean attackHeld;

	/**
	 * Teleports along the path with the Aspect of the Void when a spot far enough ahead can be reached: turns the view onto it
	 * steadily, the way a player lines up a teleport, then right-clicks once and waits to land. True while it is doing that, so
	 * walking waits.
	 */
	private boolean hop(Game game, HollowsPath.Blocks blocks, double x, double y, double z) {
		long now = game.millis();
		if (hopping) {
			double moved = Math.sqrt(sq(x - hopFromX) + sq(y - hopFromY) + sq(z - hopFromZ));
			if (moved > 3) {
				hopping = false;
				hopFailures = 0;
				nextHopAt = now + 150 + random.nextInt(450);   // a moment between teleports, like a player clicking
				if (moved > HOP_BLOCKS - 0.5 && moved < 12.5) {
					hopBlocks = Math.max(hopBlocks, Math.floor(moved));   // tuners: it goes further than 8
				}
				return false;
			}
			if (now - hopAt > HOP_WAIT_MS) {
				hopping = false;
				if (++hopFailures >= 3) {
					hopFailures = 0;
					hopsOffUntil = now + HOPS_OFF_MS;
					game.log("the Aspect of the Void did not teleport 3 times (no mana?), walking for a while");
				}
				return false;
			}
			game.steer(game.yaw(), game.pitch(), false, false, false);
			return true;
		}
		if (!game.onGround() || now < nextHopAt || now < hopsOffUntil || exitTrail != null) {
			return false;
		}
		float[] look = hopLook(blocks, x, y, z);
		if (look == null) {
			return false;
		}
		game.selectSlot(AOTV_SLOT);
		float yaw = turnSteady(game.yaw(), look[0]);
		float pitch = game.pitch() + Math.max(-10, Math.min(10, (look[1] - game.pitch()) * 0.35f));
		sprinting = false;
		game.steer(yaw, pitch, false, false, false);
		if (Math.abs(wrap(look[0] - yaw)) < 2 && Math.abs(look[1] - pitch) < 2) {
			game.use();
			hopping = true;
			hopAt = now;
			hopFromX = x;
			hopFromY = y;
			hopFromZ = z;
		}
		return true;
	}

	/**
	 * The yaw and pitch to teleport to the furthest spot of the path that is between {@link #HOP_MIN} and a teleport's length
	 * away: the flight there (and on, to where a teleport stops) open for the whole body, ground close under where it lands.
	 * Null when there is none.
	 */
	private float[] hopLook(HollowsPath.Blocks blocks, double x, double y, double z) {
		for (int k = Math.min(path.size() - 1, index + TRACK_AHEAD); k > index; k--) {
			long node = path.get(k);
			double tx = HollowsPath.x(node) + 0.5, ty = height(blocks, node), tz = HollowsPath.z(node) + 0.5;
			double d = Math.sqrt(sq(tx - x) + sq(ty - y) + sq(tz - z));
			if (d < HOP_MIN || d > hopBlocks) {
				continue;
			}
			double dx = (tx - x) / d, dy = (ty - y) / d, dz = (tz - z) / d;
			double lx = x + dx * hopBlocks, ly = y + dy * hopBlocks, lz = z + dz * hopBlocks;
			if (!openFlight(blocks, x, y, z, lx, ly, lz) || !landing(blocks, lx, ly, lz)) {
				continue;
			}
			float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
			float pitch = (float) -Math.toDegrees(Math.asin(dy));
			return new float[] {yaw, pitch};
		}
		return null;
	}

	/** True when a body (feet and head, every corner) passes nothing solid on the straight line between two points. */
	private static boolean openFlight(HollowsPath.Blocks blocks, double x0, double y0, double z0, double x1, double y1, double z1) {
		int steps = (int) Math.ceil(Math.sqrt(sq(x1 - x0) + sq(y1 - y0) + sq(z1 - z0)) / 0.25);
		for (int i = 1; i <= steps; i++) {
			double t = (double) i / steps;
			double sx = x0 + (x1 - x0) * t, sy = y0 + (y1 - y0) * t, sz = z0 + (z1 - z0) * t;
			for (double[] corner : BODY) {
				int bx = (int) Math.floor(sx + corner[0]), bz = (int) Math.floor(sz + corner[1]);
				for (double h = 0.05; h < 1.85; h += 0.9) {
					if (!HollowsPath.passable(blocks, bx, (int) Math.floor(sy + h), bz)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	/** True when there is ground at most {@link #HOP_FALL} under this point, with nothing dangerous on it. */
	private static boolean landing(HollowsPath.Blocks blocks, double x, double y, double z) {
		int bx = (int) Math.floor(x), bz = (int) Math.floor(z), feet = (int) Math.floor(y + 0.01);
		for (int fy = feet; fy >= feet - HOP_FALL; fy--) {
			if (HollowsPath.standable(blocks, bx, fy, bz)) {
				return true;
			}
			if (!HollowsPath.passable(blocks, bx, fy - 1, bz) && !HollowsPath.ground(blocks.at(bx, fy - 1, bz))) {
				return false;                             // lava, a fence: not a place to come down on
			}
		}
		return false;
	}

	/**
	 * Where to look: the point {@link #LOOKAHEAD} blocks further along the path, measured from you. As you walk it glides along the
	 * path, so the view turns smoothly into bends and over steps instead of snapping from block to block. It only goes as far as a
	 * straight walk to it keeps ground under the whole of you (never across a hole or the edge of a ledge, never into a wall);
	 * otherwise it is the next position of the path.
	 */
	private double[] aim(HollowsPath.Blocks blocks, double x, double y, double z) {
		long first = path.get(index);
		double[] best = {HollowsPath.x(first) + 0.5, HollowsPath.z(first) + 0.5};
		double px = x, pz = z, left = LOOKAHEAD, height = y;
		for (int k = index; k < path.size(); k++) {
			long node = path.get(k);
			double nx = HollowsPath.x(node) + 0.5, nz = HollowsPath.z(node) + 0.5, nodeHeight = height(blocks, node);
			double step = Math.hypot(nx - px, nz - pz);
			double t = step >= left ? left / step : 1;
			double ax = px + (nx - px) * t, az = pz + (nz - pz) * t, ah = height + (nodeHeight - height) * t;
			if (k > index && !groundAlong(blocks, x, z, y, ax, az, ah)) {
				break;
			}
			best = new double[] {ax, az};
			if (step >= left) {
				break;
			}
			left -= step;
			px = nx;
			pz = nz;
			height = nodeHeight;
		}
		return best;
	}

	private static final double[][] BODY = {{0, 0}, {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}, {0.3, 0.3}};

	/**
	 * True when walking straight from one point to another keeps ground under the middle and every corner of the player, at about
	 * the height the path has there (a step up or down is fine, a hole, a ledge or a wall is not).
	 */
	static boolean groundAlong(HollowsPath.Blocks blocks, double x0, double z0, double y0, double x1, double z1, double y1) {
		int steps = Math.max(1, (int) Math.ceil(Math.hypot(x1 - x0, z1 - z0) / 0.3));
		for (int i = 1; i <= steps; i++) {
			double t = (double) i / steps;
			double sx = x0 + (x1 - x0) * t, sz = z0 + (z1 - z0) * t, sy = y0 + (y1 - y0) * t;
			for (double[] corner : BODY) {
				if (!groundNear(blocks, (int) Math.floor(sx + corner[0]), sy, (int) Math.floor(sz + corner[1]))) {
					return false;
				}
			}
		}
		return true;
	}

	/** A place to stand in this column within a block of this height. */
	private static boolean groundNear(HollowsPath.Blocks blocks, int x, double height, int z) {
		int base = HollowsPath.feetBlock(height);
		for (int y = base - 1; y <= base + 1; y++) {
			if (HollowsPath.standable(blocks, x, y, z)) {
				return true;
			}
		}
		return false;
	}

	/** How high a player at this position of the path really stands (half a block lower on a slab). */
	private static double height(HollowsPath.Blocks blocks, long node) {
		return HollowsPath.standHeight(blocks, HollowsPath.x(node), HollowsPath.y(node), HollowsPath.z(node));
	}

	/** The next yaw: a share of the angle left, with a turning speed that builds up and dies down, and a slow drift. */
	private float turn(float yaw, float want) {
		if (--wobbleTicks <= 0) {
			wobbleTarget = (random.nextFloat() * 2 - 1) * WOBBLE;
			wobbleTicks = 15 + random.nextInt(40);
		}
		wobble += (wobbleTarget - wobble) * 0.05f;
		float diff = wrap(want + wobble - yaw);
		float wanted = Math.max(-MAX_TURN, Math.min(MAX_TURN, diff * turnShare));
		yawSpeed += (wanted - yawSpeed) * TURN_EASE;
		return yaw + yawSpeed;
	}

	/** The next yaw with no drift: for holding the crosshair on a block. */
	private float turnSteady(float yaw, float want) {
		float diff = wrap(want - yaw);
		float wanted = Math.max(-MAX_TURN, Math.min(MAX_TURN, diff * 0.4f));
		yawSpeed += (wanted - yawSpeed) * TURN_EASE;
		return yaw + yawSpeed;
	}

	/** The next pitch: a little below the horizon, wandering now and then, and up before a jump. Eased like the yaw. */
	private float look(float pitch, boolean jumpingUp) {
		if (--pitchTicks <= 0) {
			pitchTarget = 10 + random.nextFloat() * 8;
			pitchTicks = 60 + random.nextInt(120);
		}
		float target = jumpingUp ? pitchTarget - JUMP_LOOK_UP : pitchTarget;
		return pitch + Math.max(-2, Math.min(2, (target - pitch) * 0.06f));
	}

	/**
	 * True when there is a drop beside the next few positions of the path: a block next to one of them with nothing to stand on
	 * within a block of its height. A narrow walkway, a ledge, the edge of a pit.
	 */
	private boolean edgeNear(HollowsPath.Blocks blocks, double y) {
		for (int i = Math.max(0, index - 1); i < Math.min(path.size(), index + 4); i++) {
			long node = path.get(i);
			int nx = HollowsPath.x(node), ny = HollowsPath.y(node), nz = HollowsPath.z(node);
			for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
				int cx = nx + d[0], cz = nz + d[1];
				boolean ground = false;
				for (int cy = ny - 1; cy <= ny + 1 && !ground; cy++) {
					ground = HollowsPath.standable(blocks, cx, cy, cz) || !HollowsPath.clear(blocks, cx, cy, cz);
				}
				if (!ground) {
					return true;
				}
			}
		}
		return false;
	}

	/** True when the next few positions of the path have no jump or drop, so sprinting cannot overshoot one. */
	private boolean level(HollowsPath.Blocks blocks, double y, int count) {
		double height = y;
		for (int i = index; i < Math.min(path.size(), index + count); i++) {
			double next = height(blocks, path.get(i));
			if (Math.abs(next - height) > HollowsPath.STEP_HEIGHT) {
				return false;
			}
			height = next;
		}
		return true;
	}

	private void letGo(Game game) {
		hopping = false;
		mining = Long.MIN_VALUE;
		attackHeld = false;
		yawSpeed = 0;
		sprinting = false;
		game.release();
	}

	/** Gives up on a chunk you do not get closer to, and plans again when you do not move at all. */
	private void checkProgress(Game game, Coverage map, long now) {
		if (target == null) {
			return;
		}
		double distance = goal(target, map).distance((int) Math.floor(game.x()), 0, (int) Math.floor(game.z()));
		if (distance < bestDistance - PROGRESS_BLOCKS) {
			bestDistance = distance;
			bestAt = now;
		} else if (exitTrail != null && exitIndex > bestExitIndex) {
			bestExitIndex = exitIndex;
			bestAt = now;
		} else if (now - bestAt > STUCK_MS) {
			game.log("no headway to chunk " + cx(target) + "," + cz(target) + " in " + STUCK_MS / 1000 + " s");
			giveUp();
			return;
		}
		if (Math.hypot(game.x() - lastX, game.z() - lastZ) > 0.3 || mining != Long.MIN_VALUE) {
			lastX = game.x();
			lastZ = game.z();
			stillSince = now;
		} else if (now - stillSince > STILL_MS) {
			stillSince = now;
			game.log("not moving at " + where(game.x(), game.y(), game.z()) + ", planning again");
			path = null;                                  // stuck on something the plan did not know about
			search = null;
		}
	}

	private Game lastGame;

	private void log(String text) {
		if (lastGame != null) {
			lastGame.log(text);
		}
	}

	private void giveUp() {
		if (target != null) {
			skipped.add(target);
			log("gave up on chunk " + cx(target) + "," + cz(target));
		}
		failures++;
		target = null;
		path = null;
		search = null;
	}

	private boolean canWarp(Game game, long now) {
		return now - lastWarpAt >= WARP_COOLDOWN_MS && !nearNucleus(game);
	}

	private static boolean nearNucleus(Game game) {
		return Math.hypot(game.x() - NUCLEUS_X, game.z() - NUCLEUS_Z) < NUCLEUS_RADIUS;
	}

	private void warp(Game game, long now, String why) {
		letGo(game);
		game.say(why + ", warping to the Crystal Nucleus.");
		game.command(WARP_COMMAND);
		warping = true;
		exitTrail = null;
		headingX = 0;
		headingZ = 0;
		warpAt = now;
		lastWarpAt = now;
		warpFromX = game.x();
		warpFromZ = game.z();
		target = null;
		path = null;
		search = null;
	}

	/** The quadrant not done yet with the most chunks not scanned, or -1 when there is none. */
	private int nextQuadrant(Coverage map) {
		int[] left = new int[4];
		for (int cz = MansionFinder.LOW_CHUNK; cz <= MansionFinder.HIGH_CHUNK; cz++) {
			for (int cx = MansionFinder.LOW_CHUNK; cx <= MansionFinder.HIGH_CHUNK; cx++) {
				if (!map.covered(cx, cz)) {
					left[quadrantOf(cx, cz)]++;
				}
			}
		}
		int pick = -1;
		for (int q = 0; q < 4; q++) {
			if (left[q] > 0 && !doneQuadrants.contains(q) && (pick < 0 || left[q] > left[pick])) {
				pick = q;
			}
		}
		return pick;
	}

	/** Which quadrant round the Nucleus a chunk is in: 0 north-west, 1 north-east, 2 south-east, 3 south-west. */
	static int quadrantOf(int chunkX, int chunkZ) {
		boolean east = chunkX * 16 + 8 >= NUCLEUS_X, south = chunkZ * 16 + 8 >= NUCLEUS_Z;
		return south ? (east ? 2 : 3) : (east ? 1 : 0);
	}

	private static int percentScanned(Coverage map, int quadrant) {
		int all = 0, covered = 0;
		for (int cz = MansionFinder.LOW_CHUNK; cz <= MansionFinder.HIGH_CHUNK; cz++) {
			for (int cx = MansionFinder.LOW_CHUNK; cx <= MansionFinder.HIGH_CHUNK; cx++) {
				if (quadrantOf(cx, cz) == quadrant) {
					all++;
					if (map.covered(cx, cz)) {
						covered++;
					}
				}
			}
		}
		return all == 0 ? 100 : covered * 100 / all;
	}

	/** Which way from the middle of the Nucleus a chunk is: 0 west, 2 north, 4 east, 6 south. */
	static int sectorOf(int chunkX, int chunkZ) {
		double angle = Math.atan2(chunkZ * 16 + 8 - NUCLEUS_Z, chunkX * 16 + 8 - NUCLEUS_X);
		return (int) Math.floor((angle + Math.PI + Math.PI / SECTORS) / (2 * Math.PI / SECTORS)) % SECTORS;
	}

	/**
	 * The best chunk to head for in the quadrant: the one that brings the most chunks not scanned into view (the gain) for the
	 * distance to go (more for turning). One ahead comes first, so it keeps going one way and only turns when nothing worth it is
	 * left that way. Null when even the best one only brings scraps ({@link #MIN_GAIN}) into view.
	 */
	private Long bestTarget(Coverage map, int pcx, int pcz) {
		Long ahead = bestTarget(map, pcx, pcz, true);
		return ahead != null ? ahead : bestTarget(map, pcx, pcz, false);
	}

	/** The best chunk to head for; only ahead (within 60 degrees of the heading) when asked, so it keeps one direction. */
	private Long bestTarget(Coverage map, int pcx, int pcz, boolean onlyAhead) {
		boolean heading = headingX != 0 || headingZ != 0;
		if (onlyAhead && !heading) {
			return null;
		}
		int around = Math.max(1, around(map));
		Long best = null;
		double bestValue = 0;
		for (int cz = MansionFinder.LOW_CHUNK; cz <= MansionFinder.HIGH_CHUNK; cz++) {
			for (int cx = MansionFinder.LOW_CHUNK; cx <= MansionFinder.HIGH_CHUNK; cx++) {
				if (!open(map, cx, cz)) {
					continue;
				}
				int gain = 0;
				for (int dz = -around; dz <= around; dz++) {
					for (int dx = -around; dx <= around; dx++) {
						if (open(map, cx + dx, cz + dz)) {
							gain++;
						}
					}
				}
				if (gain < MIN_GAIN) {
					continue;
				}
				double dx = cx - pcx, dz = cz - pcz, length = Math.hypot(dx, dz);
				if (onlyAhead && length > 0 && (dx * headingX + dz * headingZ) / length < AHEAD_COS) {
					continue;
				}
				double cost = 16 * Math.max(0, length - around);   // blocks to go before it comes into view
				if (length > 0 && (headingX != 0 || headingZ != 0)) {
					cost += TURN_COST * (1 - (dx * headingX + dz * headingZ) / length) / 2;  // 0 straight on, all of it straight back
				}
				double value = gain / (cost + GAIN_COST + length * 0.01);
				if (value > bestValue) {
					bestValue = value;
					best = MansionFinder.key(cx, cz);
				}
			}
		}
		return best;
	}

	/** A chunk of the quadrant being scanned that is not scanned yet and not left out. */
	private boolean open(Coverage map, int cx, int cz) {
		return MansionFinder.inHollows(cx, cz) && quadrantOf(cx, cz) == quadrant && !map.covered(cx, cz)
				&& !skipped.contains(MansionFinder.key(cx, cz));
	}

	private static boolean anyLeft(Coverage map) {
		for (int cz = MansionFinder.LOW_CHUNK; cz <= MansionFinder.HIGH_CHUNK; cz++) {
			for (int cx = MansionFinder.LOW_CHUNK; cx <= MansionFinder.HIGH_CHUNK; cx++) {
				if (!map.covered(cx, cz)) {
					return true;
				}
			}
		}
		return false;
	}

	private static int reach(Coverage map) {
		return map.reach() > 0 ? map.reach() : DEFAULT_REACH;
	}

	/** How many chunks from a chunk you can be and still have it sent: the view distance, less one to be sure. */
	private static int around(Coverage map) {
		return Math.max(0, reach(map) - 1);
	}

	/** Close enough to a chunk that the server sends it: within the view distance, less one chunk to be sure. Height does not matter. */
	static HollowsPath.Goal goal(long chunk, Coverage map) {
		int around = around(map);
		int minX = (cx(chunk) - around) * 16, maxX = (cx(chunk) + around) * 16 + 15;
		int minZ = (cz(chunk) - around) * 16, maxZ = (cz(chunk) + around) * 16 + 15;
		return (x, y, z) -> {
			int dx = Math.max(0, Math.max(minX - x, x - maxX)), dz = Math.max(0, Math.max(minZ - z, z - maxZ));
			return Math.sqrt((double) dx * dx + (double) dz * dz);
		};
	}

	/** How far across (ignoring height) a path position's middle is from a point. */
	private static double across(long node, double x, double z) {
		return Math.hypot(HollowsPath.x(node) + 0.5 - x, HollowsPath.z(node) + 0.5 - z);
	}

	private static int cx(long key) {
		return (int) (key >> 32);
	}

	private static int cz(long key) {
		return (int) key;
	}

	private static float wrap(float degrees) {
		float d = degrees % 360;
		if (d >= 180) {
			d -= 360;
		} else if (d < -180) {
			d += 360;
		}
		return d;
	}
}
