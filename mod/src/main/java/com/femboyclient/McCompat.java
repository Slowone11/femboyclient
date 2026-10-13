package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.chat.ChatListener;

import java.lang.reflect.Method;

/**
 * The few places where Minecraft 26.1.2 and 26.2 differ, so one jar runs on both.
 *
 * <ul>
 * <li>The open screen is {@code minecraft.screen} in 26.1.2 and {@code minecraft.gui.screen()} in 26.2.</li>
 * <li>The chat listener is {@code minecraft.getChatListener()} in 26.1.2 and {@code minecraft.gui.chatListener()} in 26.2.</li>
 * <li>The chat window is {@code minecraft.gui.getChat()} in 26.1.2 and {@code minecraft.gui.hud.getChat()} in 26.2.</li>
 * <li>The game's main render target (what the screen is drawn on) is {@code minecraft.getMainRenderTarget()} in 26.1.2 and
 * {@code minecraft.gameRenderer.mainRenderTarget()} in 26.2.</li>
 * </ul>
 *
 * Both are looked up once by name (the names are not obfuscated in 26.x) and the one that exists is used.
 *
 * Public so the gametest source set (a different package, and the one place outside this file that still has to ask
 * "what screen is open" the same way across both versions) can use it too.
 */
public final class McCompat {

	private static final Method GUI_SCREEN = method(net.minecraft.client.gui.Gui.class, "screen");
	private static final Method GUI_CHAT_LISTENER = method(net.minecraft.client.gui.Gui.class, "chatListener");
	private static final Method MC_CHAT_LISTENER = method(Minecraft.class, "getChatListener");
	private static final java.lang.reflect.Field MC_SCREEN = field(Minecraft.class, "screen");
	private static final Method MC_MAIN_TARGET = method(Minecraft.class, "getMainRenderTarget");
	private static final java.lang.reflect.Field MC_GAME_RENDERER = field(Minecraft.class, "gameRenderer");
	private static final Method RENDERER_MAIN_TARGET = method(net.minecraft.client.renderer.GameRenderer.class, "mainRenderTarget");
	private static final Method GUI_GET_CHAT = method(net.minecraft.client.gui.Gui.class, "getChat");
	private static final java.lang.reflect.Field GUI_HUD = field(net.minecraft.client.gui.Gui.class, "hud");

	private McCompat() {
	}

	private static Method method(Class<?> owner, String name) {
		try {
			return owner.getMethod(name);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static java.lang.reflect.Field field(Class<?> owner, String name) {
		try {
			return owner.getField(name);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** The screen that is open right now, or null when none is. */
	public static Screen screen(Minecraft mc) {
		try {
			if (GUI_SCREEN != null && mc.gui != null) {
				return (Screen) GUI_SCREEN.invoke(mc.gui);
			}
			if (MC_SCREEN != null) {
				return (Screen) MC_SCREEN.get(mc);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// fall through: no screen known
		}
		return null;
	}

	/** What the game draws the screen on, or null when it cannot be found. */
	static com.mojang.blaze3d.pipeline.RenderTarget mainRenderTarget(Minecraft mc) {
		try {
			if (MC_MAIN_TARGET != null) {
				return (com.mojang.blaze3d.pipeline.RenderTarget) MC_MAIN_TARGET.invoke(mc);
			}
			if (MC_GAME_RENDERER != null && RENDERER_MAIN_TARGET != null) {
				Object renderer = MC_GAME_RENDERER.get(mc);
				return renderer == null ? null : (com.mojang.blaze3d.pipeline.RenderTarget) RENDERER_MAIN_TARGET.invoke(renderer);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// fall through: no target known
		}
		return null;
	}

	/** The game's chat listener, which shows system messages, or null when the game has none yet. */
	static ChatListener chatListener(Minecraft mc) {
		try {
			if (GUI_CHAT_LISTENER != null && mc.gui != null) {
				return (ChatListener) GUI_CHAT_LISTENER.invoke(mc.gui);
			}
			if (MC_CHAT_LISTENER != null) {
				return (ChatListener) MC_CHAT_LISTENER.invoke(mc);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// fall through: no listener known
		}
		return null;
	}

	/** Shows a big title in the middle of the screen: {@code minecraft.gui.setTitle} in 26.1.2, {@code minecraft.gui.hud.setTitle} in 26.2. */
	static void setTitle(Minecraft mc, net.minecraft.network.chat.Component title) {
		try {
			if (mc.gui == null) {
				return;
			}
			Object owner = GUI_HUD != null ? GUI_HUD.get(mc.gui) : mc.gui;
			if (owner != null) {
				owner.getClass().getMethod("setTitle", net.minecraft.network.chat.Component.class).invoke(owner, title);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// no title: the party message still goes out
		}
	}

	/** The chat window (the messages above the chat box), or null when it cannot be found. */
	static net.minecraft.client.gui.components.ChatComponent chat(Minecraft mc) {
		try {
			if (mc.gui == null) {
				return null;
			}
			if (GUI_GET_CHAT != null) {
				return (net.minecraft.client.gui.components.ChatComponent) GUI_GET_CHAT.invoke(mc.gui);
			}
			if (GUI_HUD != null) {
				Object hud = GUI_HUD.get(mc.gui);
				return hud == null ? null : (net.minecraft.client.gui.components.ChatComponent) hud.getClass().getMethod("getChat").invoke(hud);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// fall through: no chat known
		}
		return null;
	}
}
