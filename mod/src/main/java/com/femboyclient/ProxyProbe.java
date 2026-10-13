package com.femboyclient;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The Proxy screen's Test button: opens a tunnel through the proxy to a Minecraft server, the same handshake the game
 * does when it joins through that proxy, and times it. Nothing is sent through the tunnel. Blocking, so it runs on a
 * background thread (see {@link ProxyState}). Free of Minecraft classes, so plain JUnit tests cover it.
 */
public final class ProxyProbe {

	/** How the test went: on success the round trip in milliseconds, on failure why, worded for the player. */
	public record Result(boolean ok, long millis, String message) {

		static Result ok(long millis) {
			return new Result(true, millis, "Ping " + millis + " ms");
		}

		static Result failed(String message) {
			return new Result(false, -1, message);
		}
	}

	private ProxyProbe() {
	}

	/**
	 * Connects to the proxy and asks it for a tunnel to the target. The target is looked up here first, as the game looks
	 * a server up itself before it connects through the proxy, so the proxy is given an IP address in both cases.
	 */
	public static Result test(ProxySettings proxy, String targetHost, int targetPort, int timeoutMs) {
		String problem = proxy.problem();
		if (problem != null) {
			return Result.failed(problem);
		}
		InetAddress target;
		try {
			target = InetAddress.getByName(targetHost);
		} catch (UnknownHostException e) {
			return Result.failed("Could not look up " + targetHost + " (no internet?)");
		}
		InetSocketAddress proxyAddress = new InetSocketAddress(proxy.host(), proxy.port());
		if (proxyAddress.isUnresolved()) {
			return Result.failed("Unknown proxy address " + proxy.host());
		}
		long start = System.nanoTime();
		try (Socket socket = new Socket()) {
			socket.connect(proxyAddress, timeoutMs);
			socket.setSoTimeout(timeoutMs);
			socket.setTcpNoDelay(true);
			DataInputStream in = new DataInputStream(socket.getInputStream());
			OutputStream out = socket.getOutputStream();
			String failure = switch (proxy.type()) {
				case SOCKS5 -> socks5(in, out, proxy, target, targetPort);
				case SOCKS4 -> socks4(in, out, proxy, target, targetPort);
				case HTTP -> http(in, out, proxy, target, targetPort);
			};
			if (failure != null) {
				return Result.failed(failure);
			}
			return Result.ok(Math.max(1, (System.nanoTime() - start) / 1_000_000));
		} catch (SocketTimeoutException e) {
			return Result.failed("The proxy did not answer in time");
		} catch (ConnectException e) {
			return Result.failed("Could not reach the proxy (wrong address or port?)");
		} catch (EOFException e) {
			return Result.failed("The proxy hung up (wrong protocol or login?)");
		} catch (IOException e) {
			return Result.failed("Connection failed: " + e.getMessage());
		}
	}

	/** SOCKS5 (RFC 1928), with username/password login (RFC 1929) when a username is set. Null on success. */
	private static String socks5(DataInputStream in, OutputStream out, ProxySettings proxy, InetAddress target, int port)
			throws IOException {
		out.write(proxy.hasLogin() ? new byte[] {5, 2, 0, 2} : new byte[] {5, 1, 0});
		out.flush();
		int version = in.readUnsignedByte();
		int method = in.readUnsignedByte();
		if (version != 5) {
			return "Not a SOCKS5 proxy";
		}
		if (method == 0xFF) {
			return proxy.hasLogin() ? "The proxy refused the login method" : "The proxy needs a username and password";
		}
		if (method == 2) {
			if (!proxy.hasLogin()) {
				return "The proxy needs a username and password";
			}
			byte[] user = proxy.username().getBytes(StandardCharsets.UTF_8);
			byte[] pass = proxy.password().getBytes(StandardCharsets.UTF_8);
			if (user.length > 255 || pass.length > 255) {
				return "Username or password is too long for SOCKS5";
			}
			ByteArrayOutputStream login = new ByteArrayOutputStream();
			login.write(1);
			login.write(user.length);
			login.write(user);
			login.write(pass.length);
			login.write(pass);
			out.write(login.toByteArray());
			out.flush();
			in.readUnsignedByte();
			if (in.readUnsignedByte() != 0) {
				return "Wrong proxy username or password";
			}
		} else if (method != 0) {
			return "The proxy wants a login method this client does not support";
		}

		byte[] address = target.getAddress();
		ByteArrayOutputStream request = new ByteArrayOutputStream();
		request.write(new byte[] {5, 1, 0, (byte) (address.length == 4 ? 1 : 4)});
		request.write(address);
		request.write(port >> 8);
		request.write(port);
		out.write(request.toByteArray());
		out.flush();
		in.readUnsignedByte();
		int reply = in.readUnsignedByte();
		in.readUnsignedByte();
		int addressType = in.readUnsignedByte();
		if (reply != 0) {
			return socks5Error(reply);
		}
		int skip = switch (addressType) {
			case 1 -> 4;
			case 3 -> in.readUnsignedByte();
			case 4 -> 16;
			default -> 0;
		};
		in.readFully(new byte[skip + 2]);
		return null;
	}

	private static String socks5Error(int reply) {
		return switch (reply) {
			case 2 -> "The proxy does not allow this connection";
			case 3 -> "The proxy has no route to the network";
			case 4 -> "The proxy cannot reach the server";
			case 5 -> "The server refused the proxy's connection";
			case 6 -> "The proxy timed out reaching the server";
			default -> "The proxy refused the connection (code " + reply + ")";
		};
	}

	/** SOCKS4: IPv4 only, and the username (if any) is sent as the user id. Null on success. */
	private static String socks4(DataInputStream in, OutputStream out, ProxySettings proxy, InetAddress target, int port)
			throws IOException {
		if (!(target instanceof Inet4Address)) {
			return "SOCKS4 only works with IPv4 servers";
		}
		ByteArrayOutputStream request = new ByteArrayOutputStream();
		request.write(new byte[] {4, 1, (byte) (port >> 8), (byte) port});
		request.write(target.getAddress());
		request.write(proxy.username().getBytes(StandardCharsets.UTF_8));
		request.write(0);
		out.write(request.toByteArray());
		out.flush();
		byte[] reply = new byte[8];
		in.readFully(reply);
		if (reply[0] != 0) {
			return "Not a SOCKS4 proxy";
		}
		return switch (reply[1] & 0xFF) {
			case 0x5A -> null;
			case 0x5C, 0x5D -> "The proxy rejected the user id";
			default -> "The proxy refused the connection";
		};
	}

	/** HTTP CONNECT, with Basic login when a username is set. Null on success. */
	private static String http(DataInputStream in, OutputStream out, ProxySettings proxy, InetAddress target, int port)
			throws IOException {
		String hostPort = (target.getAddress().length == 4 ? target.getHostAddress() : "[" + target.getHostAddress() + "]")
				+ ":" + port;
		StringBuilder request = new StringBuilder()
				.append("CONNECT ").append(hostPort).append(" HTTP/1.1\r\n")
				.append("Host: ").append(hostPort).append("\r\n");
		if (proxy.hasLogin()) {
			String login = proxy.username() + ":" + proxy.password();
			request.append("Proxy-Authorization: Basic ")
					.append(Base64.getEncoder().encodeToString(login.getBytes(StandardCharsets.UTF_8))).append("\r\n");
		}
		request.append("\r\n");
		out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
		out.flush();

		String head = readHead(in);
		String statusLine = head.lines().findFirst().orElse("");
		if (!statusLine.startsWith("HTTP/")) {
			return "Not an HTTP proxy";
		}
		String[] parts = statusLine.split(" ", 3);
		int status = parts.length > 1 ? ProxySettings.parsePort(parts[1]) : -1;
		if (status >= 200 && status < 300) {
			return null;
		}
		if (status == 407) {
			return proxy.hasLogin() ? "Wrong proxy username or password" : "The proxy needs a username and password";
		}
		return "The proxy answered: " + statusLine.substring(Math.min(statusLine.length(), 9)).trim();
	}

	/** Reads an HTTP response head, up to and including the blank line. At most 8 KB. */
	private static String readHead(InputStream in) throws IOException {
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		int matched = 0;
		while (head.size() < 8192) {
			int b = in.read();
			if (b < 0) {
				throw new EOFException();
			}
			head.write(b);
			matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))
					? matched + 1 : (b == '\r' ? 1 : 0);
			if (matched == 4) {
				break;
			}
		}
		return head.toString(StandardCharsets.ISO_8859_1);
	}
}
