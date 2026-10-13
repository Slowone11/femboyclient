package com.femboyclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix3x2f;
import org.joml.Vector2f;

import java.util.Optional;

/**
 * Copy Chat (a copy of OdinClient's): right click a message in the open chat to copy the whole message to the clipboard,
 * even when it wraps over several lines. Holding the Copy Chat key while clicking keeps the colour codes (§a, §l...).
 * With Send Message on, a line in chat says it was copied. Nothing is sent to the server.
 */
public final class CopyChat {

	private static final int LINE_HEIGHT = 9;

	private CopyChat() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof ChatScreen) {
				ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
						event.button() != 1 || !copyAt(client, s.height, event.x(), event.y()));
			}
		});
	}

	/** Copies the message under the mouse. True if there was one, so the click goes no further. */
	private static boolean copyAt(Minecraft mc, int screenHeight, double mouseX, double mouseY) {
		FemboyClientConfig config = FemboyClientConfig.get();
		if (!config.enabled || !config.copyChat) {
			return false;
		}
		ChatComponent chat = McCompat.chat(mc);
		if (chat == null) {
			return false;
		}
		LineFinder finder = new LineFinder(mouseX, mouseY);
		chat.captureClickableText(finder, screenHeight, mc.gui == null ? 0 : guiTicks(mc), ChatComponent.DisplayMode.FOREGROUND);
		if (finder.found == null) {
			return false;
		}
		Component message = null;
		for (GuiMessage.Line line : lines(chat)) {
			if (line.content() == finder.found) {
				message = line.parent().content();
				break;
			}
		}
		if (message == null) {
			return false;
		}
		boolean withCodes = config.copyChatKey >= 0 && InputConstants.isKeyDown(mc.getWindow(), config.copyChatKey);
		mc.keyboardHandler.setClipboard(withCodes ? legacy(message) : message.getString());
		if (config.copyChatSendMessage) {
			Chat.showLater(Component.literal("§aCopied chat message to clipboard!"));
		}
		return true;
	}

	/** The chat's wrapped lines, each knowing its whole message. Read by name, so a renamed field only stops Copy Chat. */
	@SuppressWarnings("unchecked")
	private static java.util.List<GuiMessage.Line> lines(ChatComponent chat) {
		try {
			java.lang.reflect.Field field = ChatComponent.class.getDeclaredField("trimmedMessages");
			field.setAccessible(true);
			return (java.util.List<GuiMessage.Line>) field.get(chat);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return java.util.List.of();
		}
	}

	private static int guiTicks(Minecraft mc) {
		try {
			Object hud = mc.gui.getClass().getField("hud").get(mc.gui);
			return (int) hud.getClass().getMethod("getGuiTicks").invoke(hud);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return 0;      // only used to fade old lines; the focused chat shows every line anyway
		}
	}

	/** The message as text with § colour and style codes, the way 1.8 chat wrote it. */
	static String legacy(Component message) {
		StringBuilder out = new StringBuilder();
		message.visit((style, text) -> {
			if (!text.isEmpty()) {
				out.append(codes(style)).append(text);
			}
			return Optional.empty();
		}, Style.EMPTY);
		return out.toString();
	}

	private static String codes(Style style) {
		StringBuilder codes = new StringBuilder("§r");
		TextColor color = style.getColor();
		if (color != null) {
			// A named colour serializes as its name ("green"); a hex colour (#12ab34) has no § code and is left out.
			try {
				codes.append(ChatFormatting.valueOf(color.serialize().toUpperCase(java.util.Locale.ROOT)));
			} catch (IllegalArgumentException e) {
				// hex colour
			}
		}
		if (style.isBold()) codes.append("§l");
		if (style.isItalic()) codes.append("§o");
		if (style.isUnderlined()) codes.append("§n");
		if (style.isStrikethrough()) codes.append("§m");
		if (style.isObfuscated()) codes.append("§k");
		return codes.toString();
	}

	/** Gets every chat line with where it is drawn, and keeps the one under the mouse. */
	private static final class LineFinder implements ActiveTextCollector {
		private final double mouseX, mouseY;
		private Parameters parameters = new Parameters(new Matrix3x2f());
		private FormattedCharSequence found;

		LineFinder(double mouseX, double mouseY) {
			this.mouseX = mouseX;
			this.mouseY = mouseY;
		}

		@Override
		public Parameters defaultParameters() {
			return parameters;
		}

		@Override
		public void defaultParameters(Parameters parameters) {
			this.parameters = parameters;
		}

		@Override
		public void accept(TextAlignment alignment, int x, int y, Parameters params, FormattedCharSequence text) {
			Vector2f top = params.pose().transformPosition(x, y, new Vector2f());
			Vector2f bottom = params.pose().transformPosition(x, y + LINE_HEIGHT, new Vector2f());
			if (mouseY >= top.y && mouseY < bottom.y && mouseX >= 0) {
				found = text;
			}
		}

		@Override
		public void acceptScrolling(Component text, int centerX, int left, int right, int top, int bottom, Parameters params) {
		}
	}
}
