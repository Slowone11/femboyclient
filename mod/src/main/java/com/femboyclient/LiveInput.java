package com.femboyclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Input sent from the website's Live tab: a click, a hotbar slot, the mouse wheel, a key or some typed text. Only run when the
 * player has switched it on (liveInput in the config file). The website only takes it from its own PC with the admin token.
 *
 * <ul>
 * <li>With a menu open (inventory, chest, auction house...) a click lands at the spot that was clicked on the picture.</li>
 * <li>With no menu open a click is the mouse button as it is now: left attacks or mines, right uses the held item, at whatever the
 * crosshair points to. The spot is ignored.</li>
 * <li>A hotbar slot selects that slot. The wheel scrolls a menu, or moves along the hotbar when there is none.</li>
 * <li>Esc, Enter and Backspace, and typed text, go to the open menu (a search box, a sign, an anvil name).</li>
 * </ul>
 *
 * Parsing is kept apart from running so it can be tested. Running touches the game and so only happens on the game thread.
 */
public final class LiveInput {

	private static final int MAX_EVENTS = 40;
	private static final int MAX_TEXT = 64;
	private static final int SHIFT = 1;                       // GLFW_MOD_SHIFT
	private static final int ESCAPE = 256, ENTER = 257, BACKSPACE = 259;      // GLFW key codes

	sealed interface Event permits Click, Slot, Scroll, Key, Text {
	}

	/** x and y are 0 to 1 across and down the picture. button is 0 left, 1 right, 2 middle. */
	record Click(double x, double y, int button, boolean shift) implements Event {
	}

	/** n is 1 to 9. */
	record Slot(int n) implements Event {
	}

	record Scroll(double x, double y, double dy) implements Event {
	}

	/** name is escape, enter or backspace. */
	record Key(String name) implements Event {
	}

	record Text(String text) implements Event {
	}

	private LiveInput() {
	}

	// ------------------------------------------------------------------ reading

	/** Whether the website's page is watching, as the answer says, or null when it does not say. */
	static Boolean watching(String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			return o.has("viewers") ? o.get("viewers").getAsInt() > 0 : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The input in the website's answer ({"events":[...]}). Anything malformed is skipped, and nothing here can throw. */
	static List<Event> parse(String json) {
		List<Event> out = new ArrayList<>();
		try {
			JsonArray array = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("events");
			for (JsonElement element : array) {
				if (out.size() >= MAX_EVENTS) {
					break;
				}
				try {
					Event event = one(element.getAsJsonObject());
					if (event != null) {
						out.add(event);
					}
				} catch (RuntimeException e) {
					// one bad input must not lose the others
				}
			}
		} catch (RuntimeException e) {
			// a bad answer is no input
		}
		return out;
	}

	private static Event one(JsonObject o) {
		String type = o.has("type") ? o.get("type").getAsString() : "";
		switch (type) {
			case "click": {
				double x = o.get("x").getAsDouble(), y = o.get("y").getAsDouble();
				int button = o.has("button") ? o.get("button").getAsInt() : 0;
				if (!inside(x) || !inside(y) || button < 0 || button > 2) {
					return null;
				}
				return new Click(x, y, button, o.has("shift") && o.get("shift").getAsBoolean());
			}
			case "scroll": {
				double x = o.get("x").getAsDouble(), y = o.get("y").getAsDouble(), dy = o.get("dy").getAsDouble();
				return inside(x) && inside(y) && Math.abs(dy) <= 10 ? new Scroll(x, y, dy) : null;
			}
			case "slot": {
				int n = o.get("n").getAsInt();
				return n >= 1 && n <= 9 ? new Slot(n) : null;
			}
			case "key": {
				String name = o.get("key").getAsString().toLowerCase(Locale.ROOT);
				return name.equals("escape") || name.equals("enter") || name.equals("backspace") ? new Key(name) : null;
			}
			case "text": {
				String text = o.get("text").getAsString();
				boolean printable = text.codePoints().noneMatch(c -> c < 32 || c == 127);
				return !text.isEmpty() && text.length() <= MAX_TEXT && printable ? new Text(text) : null;
			}
			default:
				return null;
		}
	}

	private static boolean inside(double fraction) {
		return fraction >= 0 && fraction <= 1;
	}

	/** Where a spot on the picture (0 to 1 each way) is in the game's scaled screen units. */
	static double[] guiPoint(double x, double y, int guiWidth, int guiHeight) {
		return new double[] {Math.min(1, Math.max(0, x)) * guiWidth, Math.min(1, Math.max(0, y)) * guiHeight};
	}

	// ------------------------------------------------------------------ running

	/** Reads the website's JSON and runs every input in it. Game thread only. Public for the in-game test. */
	public static void applyJson(Minecraft mc, String json) {
		parse(json).forEach(event -> apply(mc, event));
	}

	/** Runs one input. Game thread only. Never throws: a bad input must not be able to disturb the game. */
	static void apply(Minecraft mc, Event event) {
		try {
			run(mc, event);
		} catch (RuntimeException | LinkageError e) {
			FemboyClientMod.LOGGER.debug("live input failed", e);
		}
	}

	private static void run(Minecraft mc, Event event) {
		if (mc.player == null) {
			return;
		}
		Screen screen = McCompat.screen(mc);
		switch (event) {
			case Slot slot -> mc.player.getInventory().setSelectedSlot(slot.n() - 1);
			case Click click -> click(mc, screen, click);
			case Scroll scroll -> {
				if (screen != null) {
					double[] at = guiPoint(scroll.x(), scroll.y(), mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
					screen.mouseScrolled(at[0], at[1], 0, scroll.dy());
				} else {
					int now = mc.player.getInventory().getSelectedSlot();
					mc.player.getInventory().setSelectedSlot(Math.floorMod(now - (int) Math.signum(scroll.dy()), 9));
				}
			}
			case Key key -> {
				if (screen != null) {
					int code = switch (key.name()) {
						case "escape" -> ESCAPE;
						case "enter" -> ENTER;
						default -> BACKSPACE;
					};
					screen.keyPressed(new KeyEvent(code, 0, 0));
				}
			}
			case Text text -> {
				if (screen != null) {
					text.text().codePoints().forEach(c -> screen.charTyped(new CharacterEvent(c)));
				}
			}
		}
	}

	private static void click(Minecraft mc, Screen screen, Click click) {
		if (screen != null) {
			double[] at = guiPoint(click.x(), click.y(), mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
			MouseButtonEvent event = new MouseButtonEvent(at[0], at[1], new MouseButtonInfo(click.button(), click.shift() ? SHIFT : 0));
			screen.mouseClicked(event, false);
			screen.mouseReleased(event);
			return;
		}
		// No menu: the game's own click, for whatever key the player has on that button.
		KeyMapping mapping = switch (click.button()) {
			case 0 -> mc.options.keyAttack;
			case 1 -> mc.options.keyUse;
			default -> mc.options.keyPickItem;
		};
		KeyMapping.click(InputConstants.getKey(mapping.saveString()));
	}
}
