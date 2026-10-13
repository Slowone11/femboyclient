package com.femboyclient;

import io.netty.channel.ChannelHandler;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;

import java.net.InetSocketAddress;

/**
 * Builds the Netty handler that sends a server connection through the applied proxy. Added first in the pipeline of every
 * connection the game opens to a server (joining and the server list's pings) by {@code ConnectionChannelMixin}. The
 * handler holds back the game's packets until the proxy has opened the tunnel. Netty's proxy handlers are bundled in the
 * jar (build.gradle), because Minecraft does not ship them.
 */
public final class ProxyHandlers {

	private static final long CONNECT_TIMEOUT_MS = 10_000;

	private ProxyHandlers() {
	}

	/** A new handler for one connection, or null when the proxy is off. */
	public static ChannelHandler create() {
		ProxySettings proxy = ProxyState.active();
		if (proxy == null) {
			return null;
		}
		InetSocketAddress address = new InetSocketAddress(proxy.host(), proxy.port());
		String user = proxy.hasLogin() ? proxy.username() : null;
		String pass = proxy.hasLogin() ? proxy.password() : null;
		ProxyHandler handler = switch (proxy.type()) {
			case SOCKS5 -> user == null ? new Socks5ProxyHandler(address) : new Socks5ProxyHandler(address, user, pass);
			case SOCKS4 -> user == null ? new Socks4ProxyHandler(address) : new Socks4ProxyHandler(address, user);
			case HTTP -> user == null ? new HttpProxyHandler(address) : new HttpProxyHandler(address, user, pass);
		};
		handler.setConnectTimeoutMillis(CONNECT_TIMEOUT_MS);
		return handler;
	}
}
