package com.femboyclient;

import java.util.Locale;

/**
 * A proxy for Minecraft server connections: its protocol, address and optional login. Free of Minecraft classes, so plain
 * JUnit tests cover it. A blank username means no login. SOCKS4 has no password, only a user id (the username).
 */
public record ProxySettings(Type type, String host, int port, String username, String password) {

	/** The proxy protocols the Proxy screen offers. */
	public enum Type {
		SOCKS5("SOCKS5"),
		SOCKS4("SOCKS4"),
		HTTP("HTTP");

		private final String label;

		Type(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		/** The protocol named by the text, ignoring case. Anything unknown is SOCKS5. */
		public static Type parse(String text) {
			if (text != null) {
				String upper = text.trim().toUpperCase(Locale.ROOT);
				for (Type type : values()) {
					if (type.name().equals(upper)) {
						return type;
					}
				}
			}
			return SOCKS5;
		}
	}

	public ProxySettings {
		type = type == null ? Type.SOCKS5 : type;
		host = host == null ? "" : host.trim();
		username = username == null ? "" : username.trim();
		password = password == null ? "" : password;
	}

	public boolean hasLogin() {
		return !username.isEmpty();
	}

	/** Null when these settings can be used, otherwise what is wrong, worded for the player. */
	public String problem() {
		if (host.isEmpty()) {
			return "Enter the proxy address";
		}
		if (host.chars().anyMatch(Character::isWhitespace)) {
			return "The proxy address cannot contain spaces";
		}
		if (port < 1 || port > 65535) {
			return "The port must be a number from 1 to 65535";
		}
		return null;
	}

	/** The port typed in a text field, or -1 if it is not a number. */
	public static int parsePort(String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException | NullPointerException e) {
			return -1;
		}
	}

	/** For logs: never shows the password. */
	@Override
	public String toString() {
		return type.label() + " " + host + ":" + port + (hasLogin() ? " (with login)" : "");
	}
}
