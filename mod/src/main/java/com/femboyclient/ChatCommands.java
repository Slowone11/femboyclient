package com.femboyclient;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat Commands (a copy of OdinClient 1.2.5.beta7's): when someone types a command starting with ! in party, guild or private
 * chat, the answer is sent back to that channel. "!help" lists the commands switched on for that channel. Party only: !warp,
 * !warptransfer, !allinvite, !pt, !dt (a reminder at the end of the run), !f/!m (join a floor) and !t (join a Kuudra tier).
 * Private only: !inv / !invite (a message you click to invite them). Each command has its own switch in the G menu, and
 * /blacklist add|remove|clear|list keeps players whose commands are ignored. Answers wait 6 ticks, like Odin's.
 */
public final class ChatCommands {

	/** Every command's switch, by the name the G menu shows, in order. The value is the command word(s). */
	public static final Map<String, String> COMMANDS = new LinkedHashMap<>();

	static {
		COMMANDS.put("Warp", "warp");
		COMMANDS.put("Warp & pt (warptransfer)", "warptransfer");
		COMMANDS.put("Coords (coords)", "coords");
		COMMANDS.put("Allinvite", "allinvite");
		COMMANDS.put("Odin", "odin");
		COMMANDS.put("Boop", "boop");
		COMMANDS.put("Coinflip (cf)", "cf");
		COMMANDS.put("Eightball", "8ball");
		COMMANDS.put("Dice", "dice");
		COMMANDS.put("Cat", "cat");
		COMMANDS.put("Party transfer (pt)", "pt");
		COMMANDS.put("Ping", "ping");
		COMMANDS.put("tps", "tps");
		COMMANDS.put("Dt", "dt");
		COMMANDS.put("inv", "inv");
		COMMANDS.put("invite", "invite");
		COMMANDS.put("Racism", "racism");
		COMMANDS.put("Queue dungeons cmds", "queDungeons");
		COMMANDS.put("Queue kuudra cmds", "queKuudra");
	}

	private static final Pattern PARTY = Pattern.compile("Party > (\\[.+])? ?(.+): (.+)");
	private static final Pattern GUILD = Pattern.compile("Guild > (\\[.+])? ?(.+) ?(\\[.+])?: ?(.+)");
	private static final Pattern FROM = Pattern.compile("From (\\[.+])? ?(.+): (.+)");
	private static final String[] EIGHT_BALL = {"It is certain", "It is decidedly so", "Without a doubt", "Yes definitely",
			"You may rely on it", "As I see it, yes", "Most likely", "Outlook good", "Yes", "Signs point to yes",
			"Reply hazy try again", "Ask again later", "Better not tell you now", "Cannot predict now", "Concentrate and ask again",
			"Don't count on it", "My reply is no", "My sources say no", "Outlook not so good", "Very doubtful"};
	private static final String[] FLOORS = {"ENTRANCE", "FLOOR_ONE", "FLOOR_TWO", "FLOOR_THREE", "FLOOR_FOUR", "FLOOR_FIVE",
			"FLOOR_SIX", "FLOOR_SEVEN"};
	private static final String[] KUUDRA = {"NORMAL", "HOT", "BURNING", "FIERY", "INFERNAL"};
	private static final Random RANDOM = new Random();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private static final java.util.concurrent.ExecutorService CAT = Daemons.worker("chat-commands-cat");

	private record Later(int ticks, Runnable action) {
	}

	private static final List<Later> LATER = new ArrayList<>();
	private static final List<String[]> DT_REASONS = new ArrayList<>();
	private static String dtPlayer;

	private ChatCommands() {
	}

	private static boolean on(FemboyClientConfig config, String command) {
		return !config.chatCommandsOff.contains(command);
	}

	/** Registers /blacklist. */
	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
				ClientCommands.literal("blacklist")
						.executes(c -> {
							message("Usage:\n /blacklist <add/remove> <name>\n /blacklist <clear/list>");
							return 1;
						})
						.then(ClientCommands.literal("add").then(ClientCommands.argument("name", StringArgumentType.word())
								.executes(c -> blacklist("add", StringArgumentType.getString(c, "name")))))
						.then(ClientCommands.literal("remove").then(ClientCommands.argument("name", StringArgumentType.word())
								.executes(c -> blacklist("remove", StringArgumentType.getString(c, "name")))))
						.then(ClientCommands.literal("clear").executes(c -> blacklist("clear", "")))
						.then(ClientCommands.literal("list").executes(c -> blacklist("list", "")))));
	}

	private static int blacklist(String action, String name) {
		FemboyClientConfig config = FemboyClientConfig.get();
		String lower = name.toLowerCase(Locale.ROOT);
		switch (action) {
			case "add" -> {
				if (config.chatCommandsBlacklist.contains(lower)) {
					message(name + " is already in the Blacklist.");
				} else {
					config.chatCommandsBlacklist.add(lower);
					config.save();
					message("Added " + name + " to Blacklist.");
				}
			}
			case "remove" -> {
				if (!config.chatCommandsBlacklist.remove(lower)) {
					message(name + " isn't in the Blacklist.");
				} else {
					config.save();
					message("Removed " + name + " from Blacklist.");
				}
			}
			case "clear" -> {
				config.chatCommandsBlacklist.clear();
				config.save();
				message("Blacklist cleared.");
			}
			default -> message(config.chatCommandsBlacklist.isEmpty() ? "Blacklist is empty"
					: "Blacklist:\n" + String.join("\n", config.chatCommandsBlacklist));
		}
		return 1;
	}

	/** One chat line (colour codes already removed). */
	public static void onChatLine(String line) {
		FemboyClientConfig config = FemboyClientConfig.get();
		if (dtPlayer != null && (line.contains("EXTRA STATS") || line.contains("KUUDRA DOWN!"))) {
			later(30, ChatCommands::dtReminder);
		}
		if (!config.enabled || !config.chatCommands) {
			return;
		}
		String channel, name, text;
		Matcher m;
		if ((m = PARTY.matcher(line)).matches()) {
			channel = "party";
			name = m.group(2);
			text = m.group(3);
		} else if ((m = GUILD.matcher(line)).matches()) {
			channel = "guild";
			name = m.group(2);
			text = m.group(4);
		} else if ((m = FROM.matcher(line)).matches()) {
			channel = "private";
			name = m.group(2);
			text = m.group(3);
		} else {
			return;
		}
		if (config.chatCommandsBlacklist.contains(name.toLowerCase(Locale.ROOT))) {
			return;
		}
		boolean channelOn = switch (channel) {
			case "party" -> config.chatCommandsParty;
			case "guild" -> config.chatCommandsGuild;
			default -> config.chatCommandsPrivate;
		};
		if (channelOn) {
			String message = text.toLowerCase(Locale.ROOT);
			later(6, () -> handle(message, name, channel));
		}
	}

	private static void later(int ticks, Runnable action) {
		LATER.add(new Later(ticks, action));
	}

	/** Called every client tick: runs what is due. */
	public static void tick(Minecraft mc) {
		if (LATER.isEmpty()) {
			return;
		}
		List<Runnable> due = new ArrayList<>();
		for (int i = 0; i < LATER.size(); i++) {
			Later one = LATER.get(i);
			if (one.ticks() <= 1) {
				due.add(one.action());
			} else {
				LATER.set(i, new Later(one.ticks() - 1, one.action()));
			}
		}
		for (Iterator<Later> it = LATER.iterator(); it.hasNext(); ) {
			if (it.next().ticks() <= 1) {
				it.remove();
			}
		}
		if (mc.player != null) {
			due.forEach(Runnable::run);
		}
	}

	private static String help(FemboyClientConfig config, String channel) {
		List<String[]> list = new ArrayList<>(List.of(new String[] {"coords", "coords"}, new String[] {"odin", "odin"},
				new String[] {"boop", "boop"}, new String[] {"cf", "cf"}, new String[] {"8ball", "8ball"}, new String[] {"dice", "dice"},
				new String[] {"cat", "cat"}, new String[] {"racism", "racism"}));
		switch (channel) {
			case "party" -> list.addAll(List.of(new String[] {"tps", "tps"}, new String[] {"warp", "warp"},
					new String[] {"warptransfer", "warptransfer"}, new String[] {"allinvite", "allinvite"}, new String[] {"pt", "pt"},
					new String[] {"dt", "dt"}, new String[] {"m", "queDungeons"}, new String[] {"f", "queDungeons"}));
			case "guild" -> list.addAll(List.of(new String[] {"ping", "ping"}, new String[] {"tps", "tps"}));
			default -> list.addAll(List.of(new String[] {"ping", "ping"}, new String[] {"tps", "tps"}, new String[] {"inv", "inv"},
					new String[] {"invite", "invite"}));
		}
		List<String> shown = new ArrayList<>();
		for (String[] one : list) {
			if (on(config, one[1])) {
				shown.add(one[0]);
			}
		}
		return "Commands: " + String.join(", ", shown);
	}

	private static void handle(String message, String name, String channel) {
		if (!message.startsWith("!")) {
			return;
		}
		FemboyClientConfig config = FemboyClientConfig.get();
		Minecraft mc = Minecraft.getInstance();
		boolean party = channel.equals("party");
		String word = message.split(" ")[0].substring(1);
		switch (word) {
			case "help" -> reply(help(config, channel), name, channel);
			case "coords" -> {
				if (on(config, "coords")) {
					reply("x: " + (int) mc.player.getX() + ", y: " + (int) mc.player.getY() + ", z: " + (int) mc.player.getZ(), name, channel);
				}
			}
			case "odin" -> {
				if (on(config, "odin")) {
					reply("Odin! https://discord.gg/2nCbC9hkxT", name, channel);
				}
			}
			case "boop" -> {
				if (on(config, "boop")) {
					command("boop " + after(message, "boop "));
				}
			}
			case "cf" -> {
				if (on(config, "cf")) {
					reply(RANDOM.nextBoolean() ? "heads" : "tails", name, channel);
				}
			}
			case "8ball" -> {
				if (on(config, "8ball")) {
					reply(EIGHT_BALL[RANDOM.nextInt(EIGHT_BALL.length)], name, channel);
				}
			}
			case "dice" -> {
				if (on(config, "dice")) {
					reply(Integer.toString(1 + RANDOM.nextInt(6)), name, channel);
				}
			}
			case "cat" -> {
				if (on(config, "cat")) {
					message("§aFetching cat picture...");
					catPicture(name, channel);
				}
			}
			case "racism" -> {
				if (on(config, "racism")) {
					reply(name + " is " + (1 + RANDOM.nextInt(100)) + "% racist. Racism is not allowed!", name, channel);
				}
			}
			case "ping" -> {
				if (on(config, "ping")) {
					reply("Current Ping: " + (int) Math.floor(ServerStats.ping()) + "ms", name, channel);
				}
			}
			case "tps" -> {
				if (on(config, "tps")) {
					reply("Current TPS: " + Math.floor(ServerStats.tps()), name, channel);
				}
			}
			case "warp" -> {
				if (on(config, "warp") && party) {
					command("p warp");
				}
			}
			case "warptransfer" -> {
				if (on(config, "warptransfer")) {
					command("p warp");
					later(12, () -> command("p transfer " + name));
				}
			}
			case "allinvite" -> {
				if (on(config, "allinvite") && party) {
					command("p settings allinvite");
				}
			}
			case "pt" -> {
				if (on(config, "pt") && party) {
					command("p transfer " + name);
				}
			}
			case "dt" -> {
				if (on(config, "dt") && party) {
					String rest = after(message, "dt ");
					String reason = !rest.equals(message) && !rest.contains("!dt") ? rest : "No reason given";
					if (DT_REASONS.stream().anyMatch(r -> r[0].equals(name))) {
						message("§cThat player already has a reminder!");
					} else {
						DT_REASONS.add(new String[] {name, reason});
						message("§aReminder set for the end of the run!");
						dtPlayer = name;
					}
				}
			}
			case "m", "f" -> {
				if (on(config, "queDungeons")) {
					queue(message, word, word.equals("m") ? "master mode floor" : "floor");
				}
			}
			case "t" -> {
				if (on(config, "queKuudra")) {
					queue(message, "t", "kuudra run");
				}
			}
			case "inv", "invite" -> {
				if (on(config, word) && channel.equals("private")) {
					invite(mc, name);
				}
			}
			default -> {
			}
		}
	}

	/** The text after {@code prefix}, or the whole message when it is not there (like Kotlin's substringAfter). */
	private static String after(String message, String prefix) {
		int at = message.indexOf(prefix);
		return at < 0 ? message : message.substring(at + prefix.length());
	}

	/** !f 7, !m 5, !t 3: joins that instance, the way Odin's /od f7 does. */
	private static void queue(String message, String word, String what) {
		String rest = after(message, word + " ");
		if (rest.equals(message)) {
			message(word.equals("t") ? "§cPlease specify a tier." : "§cPlease specify a floor.");
			return;
		}
		int number;
		try {
			number = Integer.parseInt(rest.trim());
		} catch (NumberFormatException e) {
			message(word.equals("t") ? "§cPlease specify a valid tier." : "§cPlease specify a valid floor.");
			return;
		}
		String instance;
		if (word.equals("t")) {
			if (number < 1 || number > KUUDRA.length) {
				message("§cPlease specify a valid tier.");
				return;
			}
			instance = "KUUDRA_" + KUUDRA[number - 1];
		} else {
			if (number < (word.equals("m") ? 1 : 0) || number > 7) {
				message("§cPlease specify a valid floor.");
				return;
			}
			instance = (word.equals("m") ? "MASTER_CATACOMBS_" : "CATACOMBS_") + FLOORS[number];
		}
		message("§aEntering " + what + ": " + number);
		command("joininstance " + instance);
	}

	private static void invite(Minecraft mc, String name) {
		mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1F, 1F);
		Component text = Component.literal("§3Femboy Client §8»§r Click on this message to invite " + name + " to your party!")
				.withStyle(Style.EMPTY.withClickEvent(new ClickEvent.RunCommand("/party invite " + name))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("§e/party invite " + name))));
		Chat.showLater(text);
	}

	private static void dtReminder() {
		if (dtPlayer == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		McCompat.setTitle(mc, Component.literal("§cPlayers need DT"));
		if (mc.player != null) {
			mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1F, 1F);
		}
		List<String> parts = new ArrayList<>();
		for (String[] one : DT_REASONS) {
			parts.add(one[0] + ": " + one[1]);
		}
		command("pc Players need DT: " + String.join(", ", parts));
		dtPlayer = null;
		DT_REASONS.clear();
	}

	/** Asks thecatapi for a random cat and sends its link (Odin re-uploaded it to imgur first; that needs Odin's own key). */
	private static void catPicture(String name, String channel) {
		CAT.execute(() -> {
			String reply;
			try {
				HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.thecatapi.com/v1/images/search"))
						.timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
				Matcher m = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
				reply = m.find() ? m.group(1) : "Failed to get a cat";
			} catch (Exception e) {
				reply = "Failed to get a cat " + e.getMessage();
			}
			String text = reply;
			Minecraft.getInstance().execute(() -> reply(text, name, channel));
		});
	}

	private static void reply(String text, String name, String channel) {
		switch (channel) {
			case "guild" -> command("gc " + text);
			case "party" -> command("pc " + text);
			default -> command("w " + name + " " + text);
		}
	}

	private static void command(String command) {
		var connection = Minecraft.getInstance().getConnection();
		if (connection != null) {
			connection.sendCommand(command);
		}
	}

	private static void message(String text) {
		Chat.showLater(Component.literal("§3Femboy Client §8»§r " + text));
	}
}
