package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyProbeTest {

	private static final int TARGET_PORT = 25565;

	/** One-connection fake proxy. The handler reads the client's handshake and answers; what it saw is kept in seen. */
	private static final class FakeProxy implements AutoCloseable {
		interface Handler {
			void handle(DataInputStream in, OutputStream out) throws IOException;
		}

		final ServerSocket server;
		final AtomicReference<String> seen = new AtomicReference<>();
		private final Thread thread;

		FakeProxy(Handler handler) throws IOException {
			server = new ServerSocket(0);
			thread = new Thread(() -> {
				try (Socket socket = server.accept()) {
					handler.handle(new DataInputStream(socket.getInputStream()), socket.getOutputStream());
					socket.getOutputStream().flush();
				} catch (IOException ignored) {
				}
			});
			thread.setDaemon(true);
			thread.start();
		}

		ProxySettings settings(ProxySettings.Type type, String user, String pass) {
			return new ProxySettings(type, "127.0.0.1", server.getLocalPort(), user, pass);
		}

		@Override
		public void close() throws IOException {
			server.close();
		}
	}

	private static ProxyProbe.Result probe(ProxySettings settings) {
		return ProxyProbe.test(settings, "127.0.0.1", TARGET_PORT, 3000);
	}

	private static String readString(DataInputStream in, int length) throws IOException {
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static void socks5Connect(DataInputStream in, OutputStream out, AtomicReference<String> seen) throws IOException {
		assertEquals(5, in.readUnsignedByte());
		assertEquals(1, in.readUnsignedByte());   // CONNECT
		in.readUnsignedByte();
		assertEquals(1, in.readUnsignedByte());   // IPv4: the target is looked up before asking the proxy
		byte[] ip = new byte[4];
		in.readFully(ip);
		int port = in.readUnsignedShort();
		seen.set((ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF) + ":" + port);
		out.write(new byte[] {5, 0, 0, 1, 1, 2, 3, 4, 0, 80});
	}

	@Test
	void socks5WithoutLoginOpensATunnel() throws IOException {
		AtomicReference<String> seen = new AtomicReference<>();
		try (FakeProxy proxy = new FakeProxy((in, out) -> {
			assertEquals(5, in.readUnsignedByte());
			int count = in.readUnsignedByte();
			byte[] methods = new byte[count];
			in.readFully(methods);
			assertEquals(1, count);
			assertEquals(0, methods[0]);
			out.write(new byte[] {5, 0});
			socks5Connect(in, out, seen);
		})) {
			ProxyProbe.Result result = probe(proxy.settings(ProxySettings.Type.SOCKS5, "", ""));
			assertTrue(result.ok(), result.message());
			assertTrue(result.millis() > 0);
			assertTrue(result.message().startsWith("Ping "));
			assertEquals("127.0.0.1:25565", seen.get());
		}
	}

	@Test
	void socks5SendsTheUsernameAndPassword() throws IOException {
		AtomicReference<String> login = new AtomicReference<>();
		try (FakeProxy proxy = new FakeProxy((in, out) -> {
			in.readUnsignedByte();
			in.readFully(new byte[in.readUnsignedByte()]);
			out.write(new byte[] {5, 2});
			assertEquals(1, in.readUnsignedByte());
			String user = readString(in, in.readUnsignedByte());
			String pass = readString(in, in.readUnsignedByte());
			login.set(user + "/" + pass);
			out.write(new byte[] {1, 0});
			socks5Connect(in, out, new AtomicReference<>());
		})) {
			ProxyProbe.Result result = probe(proxy.settings(ProxySettings.Type.SOCKS5, "me", "secret"));
			assertTrue(result.ok(), result.message());
			assertEquals("me/secret", login.get());
		}
	}

	@Test
	void socks5WrongPasswordSaysSo() throws IOException {
		try (FakeProxy proxy = new FakeProxy((in, out) -> {
			in.readUnsignedByte();
			in.readFully(new byte[in.readUnsignedByte()]);
			out.write(new byte[] {5, 2});
			in.readUnsignedByte();
			in.readFully(new byte[in.readUnsignedByte()]);
			in.readFully(new byte[in.readUnsignedByte()]);
			out.write(new byte[] {1, 1});
		})) {
			ProxyProbe.Result result = probe(proxy.settings(ProxySettings.Type.SOCKS5, "me", "wrong"));
			assertFalse(result.ok());
			assertEquals("Wrong proxy username or password", result.message());
		}
	}

	@Test
	void socks5ThatNeedsALoginSaysSoWhenNoneIsSet() throws IOException {
		try (FakeProxy proxy = new FakeProxy((in, out) -> {
			in.readUnsignedByte();
			in.readFully(new byte[in.readUnsignedByte()]);
			out.write(new byte[] {5, (byte) 0xFF});
		})) {
			ProxyProbe.Result result = probe(proxy.settings(ProxySettings.Type.SOCKS5, "", ""));
			assertFalse(result.ok());
			assertEquals("The proxy needs a username and password", result.message());
		}
	}

	@Test
	void socks5ThatCannotReachTheServerSaysSo() throws IOException {
		try (FakeProxy proxy = new FakeProxy((in, out) -> {
			in.readUnsignedByte();
			in.readFully(new byte[in.readUnsignedByte()]);
			out.write(new byte[] {5, 0});
			in.readFully(new byte[10]);
			out.write(new byte[] {5, 4, 0, 1, 0, 0, 0, 0, 0, 0});
		})) {
			ProxyProbe.Result result = probe(proxy.settings(ProxySettings.Type.SOCKS5, "", ""));
			assertFalse(result.ok());
			assertEquals("The proxy cannot reach the server", result.message());
		}
	}

	@Test
	void socks4SendsTheUserIdAndOpensATunnel() throws IOException {
		AtomicReference<String> userId = new AtomicReference<>();
		try (FakeProxy proxy = new FakeProxy((in, out) -> {
			assertEquals(4, in.readUnsignedByte());
			assertEquals(1, in.readUnsignedByte());
			assertEquals(TARGET_PORT, in.readUnsignedShort());
			in.readFully(new byte[4]);
			ByteArrayOutputStream id = new ByteArrayOutputStream();
			for (int b = in.readUnsignedByte(); b != 0; b = in.readUnsignedByte()) {
				id.write(b);
			}
			userId.set(id.toString(StandardCharsets.UTF_8));
			out.write(new byte[] {0, 0x5A, 0, 0, 0, 0, 0, 0});
		})) {
			ProxyProbe.Result result = probe(proxy.settings(ProxySettings.Type.SOCKS4, "bob", "ignored"));
			assertTrue(result.ok(), result.message());
			assertEquals("bob", userId.get());
		}
	}

	@Test
	void httpConnectSendsBasicLogin() throws IOException {
		AtomicReference<String> head = new AtomicReference<>();
		try (FakeProxy proxy = new FakeProxy((in, out) -> {
			head.set(readHead(in));
			out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
		})) {
			ProxyProbe.Result result = probe(proxy.settings(ProxySettings.Type.HTTP, "me", "pw"));
			assertTrue(result.ok(), result.message());
			assertTrue(head.get().startsWith("CONNECT 127.0.0.1:25565 HTTP/1.1\r\n"), head.get());
			String expected = Base64.getEncoder().encodeToString("me:pw".getBytes(StandardCharsets.UTF_8));
			assertTrue(head.get().contains("Proxy-Authorization: Basic " + expected + "\r\n"), head.get());
		}
	}

	@Test
	void http407SaysTheLoginIsNeeded() throws IOException {
		try (FakeProxy proxy = new FakeProxy((in, out) -> {
			readHead(in);
			out.write("HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
		})) {
			ProxyProbe.Result result = probe(proxy.settings(ProxySettings.Type.HTTP, "", ""));
			assertFalse(result.ok());
			assertEquals("The proxy needs a username and password", result.message());
		}
	}

	@Test
	void anHttpProxyTestedAsSocks5SaysItIsNotSocks5() throws IOException {
		try (FakeProxy proxy = new FakeProxy((in, out) -> {
			in.readFully(new byte[3]);
			out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
		})) {
			ProxyProbe.Result result = probe(proxy.settings(ProxySettings.Type.SOCKS5, "", ""));
			assertFalse(result.ok());
			assertEquals("Not a SOCKS5 proxy", result.message());
		}
	}

	@Test
	void aClosedPortSaysTheProxyCannotBeReached() throws IOException {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		ProxyProbe.Result result = probe(new ProxySettings(ProxySettings.Type.SOCKS5, "127.0.0.1", port, "", ""));
		assertFalse(result.ok());
		assertEquals("Could not reach the proxy (wrong address or port?)", result.message());
	}

	@Test
	void badSettingsAreNotTried() {
		ProxyProbe.Result result = probe(new ProxySettings(ProxySettings.Type.HTTP, "", 8080, "", ""));
		assertFalse(result.ok());
		assertEquals("Enter the proxy address", result.message());
	}

	private static String readHead(DataInputStream in) throws IOException {
		ByteArrayOutputStream head = new ByteArrayOutputStream();
		while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
			head.write(in.readUnsignedByte());
		}
		return head.toString(StandardCharsets.ISO_8859_1);
	}

	@Test
	void settingsCheckAddressAndPort() {
		assertNull(new ProxySettings(ProxySettings.Type.SOCKS5, " 1.2.3.4 ", 1080, "", "").problem());
		assertEquals("The proxy address cannot contain spaces",
				new ProxySettings(ProxySettings.Type.SOCKS5, "1.2 .3.4", 1080, "", "").problem());
		assertEquals("The port must be a number from 1 to 65535",
				new ProxySettings(ProxySettings.Type.SOCKS5, "1.2.3.4", 0, "", "").problem());
		assertEquals("The port must be a number from 1 to 65535",
				new ProxySettings(ProxySettings.Type.SOCKS5, "1.2.3.4", 65536, "", "").problem());
		assertEquals(-1, ProxySettings.parsePort("abc"));
		assertEquals(8080, ProxySettings.parsePort(" 8080 "));
	}

	@Test
	void typeParsesAnyCaseAndFallsBackToSocks5() {
		assertEquals(ProxySettings.Type.HTTP, ProxySettings.Type.parse("http"));
		assertEquals(ProxySettings.Type.SOCKS4, ProxySettings.Type.parse(" Socks4 "));
		assertEquals(ProxySettings.Type.SOCKS5, ProxySettings.Type.parse("ftp"));
		assertEquals(ProxySettings.Type.SOCKS5, ProxySettings.Type.parse(null));
	}

	@Test
	void toStringNeverShowsThePassword() {
		String text = new ProxySettings(ProxySettings.Type.SOCKS5, "1.2.3.4", 1080, "me", "hunter2").toString();
		assertFalse(text.contains("hunter2"), text);
		assertEquals("SOCKS5 1.2.3.4:1080 (with login)", text);
	}

	@Test
	void configKeepsTheProxySettings() {
		java.util.Properties user = new java.util.Properties();
		user.setProperty("proxyEnabled", "true");
		user.setProperty("proxyType", "http");
		user.setProperty("proxyHost", "proxy.example");
		user.setProperty("proxyPort", "3128");
		user.setProperty("proxyUsername", "me");
		user.setProperty("proxyPassword", "pw");
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new java.util.Properties(), user);
		assertTrue(config.proxyEnabled);
		assertEquals(new ProxySettings(ProxySettings.Type.HTTP, "proxy.example", 3128, "me", "pw"), config.proxy());
		java.util.Properties saved = config.toProperties();
		assertEquals("HTTP", saved.getProperty("proxyType"));
		assertEquals("3128", saved.getProperty("proxyPort"));
		assertEquals("pw", saved.getProperty("proxyPassword"));
	}
}
