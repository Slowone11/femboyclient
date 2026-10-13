package com.femboyclient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/**
 * Pet Swapper: in the Crystal Hollows, puts Bal on when the sidebar's heat goes up, and puts back the pet you had before Bal
 * when it goes down. The decisions are in {@link PetSwap}; this class does the swapping.
 * <p>
 * A swap sends {@code /pets} and opens the menu the server answers with in the background: {@link #onOpenScreen} builds the
 * menu without a screen, so nothing shows, the mouse stays grabbed and you keep moving and mining. The moment the menu's items
 * arrive ({@link #onContent}, straight from the packet, not the next tick) the pet is clicked and the menu is closed again.
 * The "Next Page" arrow is followed when the pet is on a later page.
 * <p>
 * The pet to go back to is the last pet other than Bal that was out: read from the menu (its lore offers to despawn it)
 * when Bal is put on, and from "You summoned your ..." and Autopet chat lines. It is kept in the config, so it survives a
 * restart. Everything runs on the game's thread.
 */
public final class PetSwapper {

	/** Longest wait for the /pets menu to open, or for a page of it to answer. */
	private static final long TIMEOUT_NANOS = 3_000_000_000L;

	/** How long a page must stay unchanged before a pet missing from it counts as not there. */
	private static final long SETTLE_NANOS = 300_000_000L;

	/** Least time between two swaps, so heat going up and down fast cannot spam /pets. */
	private static final long GAP_NANOS = 500_000_000L;

	/** How long after a hidden menu is closed a close from the server for it is still ignored (it must not close your chat). */
	private static final long LATE_CLOSE_NANOS = 2_000_000_000L;

	private enum Step {
		IDLE, OPENING, LOOKING
	}

	private static final PetSwap HEAT = new PetSwap();

	private static Step step = Step.IDLE;
	/** The pet the heat asked for and is not out yet, or null. */
	private static PetSwap.Want wanted;
	/** The pet out now, as last seen in chat or the menu; null when not known or none. */
	private static String active;
	/** The hidden /pets menu while a swap looks through it, else null. */
	private static AbstractContainerMenu menu;
	private static boolean filled, waitingPage, toldNoBack, warned;
	private static long deadline, nextStartAt, changedAt;
	private static int closedId = -1;
	private static long closedAt;
	private static int swaps;
	private static String loggedHeatLine;

	private PetSwapper() {
	}

	// ---------------------------------------------------------------- every tick

	/** Called every client tick: reads the heat and starts, follows or ends a swap. Never throws. */
	static void tick(Minecraft mc) {
		try {
			FemboyClientConfig config = FemboyClientConfig.get();
			if (!config.enabled || !config.petSwapper || mc.player == null) {
				if (step != Step.IDLE) {
					close(mc);
				}
				wanted = null;
				HEAT.reset();
				return;
			}
			ScoreboardReader.Sidebar sidebar = ScoreboardReader.read(mc);
			List<String> lines = sidebar == null ? List.of() : sidebar.lines();
			Integer heat = PetSwap.heat(lines);
			if (heat == null) {
				wanted = null;                // left the Crystal Hollows: nothing to swap for
			} else if (heat == PetSwap.NO_NUMBER) {
				logHeatLine(lines);
			}
			PetSwap.Want want = HEAT.onHeat(heat);
			if (want != null) {
				FemboyClientMod.LOGGER.info("[Pet Swapper] heat went {} to {}", want == PetSwap.Want.BAL ? "up" : "down", heat);
				wanted = want;
			}

			long now = System.nanoTime();
			switch (step) {
				case IDLE -> {
					if (wanted != null && now >= nextStartAt) {
						start(mc, config, now);
					}
				}
				case OPENING -> {
					if (now > deadline) {
						giveUp(mc, "/pets did not open.");
					}
				}
				case LOOKING -> {
					if (now > deadline) {
						giveUp(mc, "the /pets menu did not answer.");
					} else {
						look(mc, now);
					}
				}
			}
		} catch (RuntimeException e) {
			if (!warned) {
				warned = true;
				FemboyClientMod.LOGGER.warn("[Pet Swapper] failed, it will keep trying", e);
			}
			close(mc);
		}
	}

	/** Sends /pets when the wanted pet is not out already and nothing else is open. */
	private static void start(Minecraft mc, FemboyClientConfig config, long now) {
		String target = target(config);
		if (target == null) {
			wanted = null;
			if (!toldNoBack) {
				toldNoBack = true;
				say("[Pet Swapper] Heat went down, but no pet to swap back to is known yet. Summon it once; it is remembered.");
			}
			return;
		}
		if (target.equalsIgnoreCase(active)) {
			wanted = null;                    // already out
			return;
		}
		ClientPacketListener connection = mc.getConnection();
		if (connection == null || mc.gameMode == null || McCompat.screen(mc) != null
				|| mc.player.containerMenu != mc.player.inventoryMenu) {
			return;                           // a screen or menu is open: wait until it is closed
		}
		step = Step.OPENING;
		deadline = now + TIMEOUT_NANOS;
		connection.sendCommand("pets");
	}

	/** Looks through the hidden menu's page: clicks the pet, turns the page, or gives up when it is not there. */
	private static void look(Minecraft mc, long now) {
		LocalPlayer player = mc.player;
		if (!filled || menu == null || player == null || mc.gameMode == null) {
			return;
		}
		if (player.containerMenu != menu) {
			giveUp(mc, null);                 // something else opened over it
			return;
		}
		List<PetSwap.Item> items = items(menu);
		String out = PetSwap.active(items);
		if (out != null) {
			active = out;
			learnBack(out);
		}
		String target = target(FemboyClientConfig.get());
		if (target == null) {
			close(mc);
			return;
		}
		int slot = PetSwap.find(items, target);
		if (slot >= 0) {
			if (!PetSwap.isActive(items.get(slot))) {
				mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, player);
				swaps++;
				FemboyClientMod.LOGGER.info("[Pet Swapper] summoned {}", target);
			}
			active = target;
			wanted = null;
			close(mc);
			return;
		}
		if (waitingPage) {
			return;
		}
		int next = PetSwap.nextPage(items);
		if (next >= 0) {
			mc.gameMode.handleContainerInput(menu.containerId, next, 0, ContainerInput.PICKUP, player);
			waitingPage = true;
			deadline = now + TIMEOUT_NANOS;
		} else if (now - changedAt >= SETTLE_NANOS) {
			wanted = null;
			say("[Pet Swapper] " + target + " is not in your /pets menu.");
			close(mc);
		}
	}

	// ---------------------------------------------------------------- packets (from ClientPacketListenerMixin)

	/**
	 * A menu is opening. While a swap waits for /pets, the pets menu is built here without a screen and true is returned, so
	 * the game does not show it. Any other menu, or no swap going on: false, and the game opens it as usual.
	 */
	public static boolean onOpenScreen(int containerId, MenuType<?> type, Component title) {
		Minecraft mc = Minecraft.getInstance();
		if (step == Step.IDLE || !mc.isSameThread() || mc.player == null) {
			return false;
		}
		String plain = ScoreboardReader.strip(title.getString());
		if (!PetSwap.isPetsMenu(plain)) {
			FemboyClientMod.LOGGER.info("[Pet Swapper] waited for the pets menu, but \"{}\" opened; left it open", plain);
			wanted = null;
			step = Step.IDLE;
			menu = null;
			return false;
		}
		menu = type.create(containerId, mc.player.getInventory());
		mc.player.containerMenu = menu;
		step = Step.LOOKING;
		filled = false;
		waitingPage = false;
		changedAt = System.nanoTime();
		deadline = changedAt + TIMEOUT_NANOS;
		return true;
	}

	/**
	 * Items arrived in a menu: all of them at once ({@code whole}) or one slot. For the hidden menu the pet is looked for right
	 * away. Only a whole page ends the wait after a page turn: the arrow's own slot update must not count as the new page.
	 */
	public static void onContent(int containerId, boolean whole) {
		if (step != Step.LOOKING || menu == null || containerId != menu.containerId) {
			return;
		}
		filled = true;
		if (whole) {
			waitingPage = false;
		}
		changedAt = System.nanoTime();
		look(Minecraft.getInstance(), changedAt);
	}

	/**
	 * The server closes a menu. For the hidden one (also just after it was closed here) true is returned and the game skips its
	 * own close, which would also close whatever screen you have open, like the chat.
	 */
	public static boolean onServerClose(int containerId) {
		Minecraft mc = Minecraft.getInstance();
		if (!mc.isSameThread() || mc.player == null) {
			return false;
		}
		if (menu != null && containerId == menu.containerId && mc.player.containerMenu == menu) {
			mc.player.containerMenu = mc.player.inventoryMenu;
			closedId = containerId;
			closedAt = System.nanoTime();
			menu = null;
			step = Step.IDLE;
			nextStartAt = closedAt + GAP_NANOS;
			return true;
		}
		return containerId == closedId && System.nanoTime() - closedAt < LATE_CLOSE_NANOS;
	}

	// ---------------------------------------------------------------- chat

	/** One incoming chat line, color codes gone: notes which pet was summoned, or that it was put away. */
	static void onChatLine(String line) {
		String pet = PetSwap.summoned(line);
		if (pet != null) {
			active = pet;
			learnBack(pet);
		} else if (PetSwap.despawned(line)) {
			active = null;
		}
	}

	/** Called when the client leaves a server. */
	static void onDisconnect() {
		step = Step.IDLE;
		menu = null;
		wanted = null;
		active = null;
		closedId = -1;
		HEAT.reset();
	}

	// ---------------------------------------------------------------- for the G menu

	/** The heat on the sidebar now, for the G menu. */
	static String heatText() {
		ScoreboardReader.Sidebar sidebar = ScoreboardReader.read(Minecraft.getInstance());
		Integer heat = sidebar == null ? null : PetSwap.heat(sidebar.lines());
		return heat == null ? "not in the Crystal Hollows" : heat == PetSwap.NO_NUMBER ? "no number" : heat.toString();
	}

	/** The pet out now as far as known, for the G menu. */
	static String activeText() {
		return active == null ? "not known yet" : active;
	}

	/** Pets summoned by swaps since the game started. */
	static int swaps() {
		return swaps;
	}

	// ---------------------------------------------------------------- helpers

	/** The pet {@link #wanted} names: Bal, or the pet to go back to (null when none is known yet, or nothing is wanted). */
	private static String target(FemboyClientConfig config) {
		if (wanted == null) {
			return null;
		}
		if (wanted == PetSwap.Want.BAL) {
			return PetSwap.BAL;
		}
		return config.petSwapBack.isBlank() ? null : config.petSwapBack;
	}

	/** Remembers a pet other than Bal as the one to go back to. */
	private static void learnBack(String pet) {
		FemboyClientConfig config = FemboyClientConfig.get();
		if (!PetSwap.isBal(pet) && !pet.equalsIgnoreCase(config.petSwapBack)) {
			config.petSwapBack = pet;
			config.save();
			toldNoBack = false;
		}
	}

	/** The slots of a menu, without the player's own inventory (its last 36 slots), names and lore with color codes gone. */
	private static List<PetSwap.Item> items(AbstractContainerMenu menu) {
		int size = Math.max(0, menu.slots.size() - 36);
		List<PetSwap.Item> items = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			ItemLore lore = stack.get(DataComponents.LORE);
			List<String> lines = new ArrayList<>();
			if (lore != null) {
				for (Component line : lore.lines()) {
					lines.add(ScoreboardReader.strip(line.getString()));
				}
			}
			items.add(new PetSwap.Item(ScoreboardReader.strip(stack.getHoverName().getString()), lines));
		}
		return items;
	}

	/** Ends a swap that did not work. {@code reason} goes to chat, or nowhere when null. */
	private static void giveUp(Minecraft mc, String reason) {
		FemboyClientMod.LOGGER.info("[Pet Swapper] swap skipped: {}", reason == null ? "another menu opened" : reason);
		if (reason != null) {
			say("[Pet Swapper] Swap skipped: " + reason);
		}
		wanted = null;
		close(mc);
	}

	/** Closes the hidden menu, if it is still open, and ends the swap. */
	private static void close(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player != null && menu != null && player.containerMenu == menu) {
			player.connection.send(new ServerboundContainerClosePacket(menu.containerId));
			player.containerMenu = player.inventoryMenu;
			closedId = menu.containerId;
			closedAt = System.nanoTime();
		}
		menu = null;
		step = Step.IDLE;
		nextStartAt = System.nanoTime() + GAP_NANOS;
	}

	/** Logs a heat line without a number once per text, so what the sidebar says can be read from the log. */
	private static void logHeatLine(List<String> lines) {
		for (String line : lines) {
			if (line.startsWith("Heat:") && !line.equals(loggedHeatLine)) {
				loggedHeatLine = line;
				FemboyClientMod.LOGGER.info("[Pet Swapper] heat line has no number: \"{}\"", line);
			}
		}
	}

	private static void say(String text) {
		Chat.showLater(Component.literal(text).withStyle(ChatFormatting.GRAY));
	}
}
