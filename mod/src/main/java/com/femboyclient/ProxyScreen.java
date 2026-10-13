package com.femboyclient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * The proxy form, opened from the Proxy button on the server list ({@link ProxyButton}). Test tries the typed settings
 * without saving them; Apply saves them, makes every server connection use them and tests them. Uses the game's own
 * widgets, since it is opened from a vanilla screen.
 */
final class ProxyScreen extends Screen {

	private static final int WIDTH = 220;
	private static final int ROW = 24;
	private static final int LABEL = 11;
	private static final int GREEN = 0xFF55FF55, RED = 0xFFFF5555, YELLOW = 0xFFFFFF55, GRAY = 0xFFA0A0A0, WHITE = 0xFFFFFFFF;

	private final Screen parent;

	private CycleButton<Boolean> enabledButton;
	private CycleButton<ProxySettings.Type> typeButton;
	private EditBox hostBox, portBox, userBox, passBox;
	private int left, hostLabelY, userLabelY, passLabelY, statusY;

	private String statusText;
	private int statusColor;
	private int testRun;   // so a slow test's answer cannot overwrite a newer one
	private boolean followApplied = true;   // show the applied proxy's status as it changes, until Test is pressed

	ProxyScreen(Screen parent) {
		super(Component.literal("Proxy"));
		this.parent = parent;
		showAppliedStatus();
	}

	@Override
	protected void init() {
		FemboyClientConfig config = FemboyClientConfig.get();
		// Keep what was typed when the window is resized (init runs again then).
		boolean enabled = enabledButton != null ? enabledButton.getValue() : config.proxyEnabled;
		ProxySettings.Type type = typeButton != null ? typeButton.getValue() : ProxySettings.Type.parse(config.proxyType);
		String host = hostBox != null ? hostBox.getValue() : config.proxyHost;
		String port = portBox != null ? portBox.getValue() : Integer.toString(config.proxyPort);
		String user = userBox != null ? userBox.getValue() : config.proxyUsername;
		String pass = passBox != null ? passBox.getValue() : config.proxyPassword;

		left = width / 2 - WIDTH / 2;
		int total = ROW * 2 + (LABEL + ROW) * 3 + 14 + ROW * 2;
		int y = Math.max(30, (height - total) / 2);

		enabledButton = addRenderableWidget(CycleButton.onOffBuilder(enabled)
				.create(left, y, WIDTH, 20, Component.literal("Proxy")));
		y += ROW;
		typeButton = addRenderableWidget(CycleButton.<ProxySettings.Type>builder(t -> Component.literal(t.label()), type)
				.withValues(ProxySettings.Type.values())
				.create(left, y, WIDTH, 20, Component.literal("Protocol")));
		y += ROW;

		hostLabelY = y;
		y += LABEL;
		hostBox = addRenderableWidget(new EditBox(font, left, y, WIDTH - 60, 20, Component.literal("Proxy address")));
		hostBox.setMaxLength(255);
		hostBox.setValue(host);
		hostBox.setHint(Component.literal("IP or host name").withStyle(ChatFormatting.DARK_GRAY));
		portBox = addRenderableWidget(new EditBox(font, left + WIDTH - 56, y, 56, 20, Component.literal("Port")));
		portBox.setMaxLength(5);
		portBox.setValue(port);
		portBox.setHint(Component.literal("Port").withStyle(ChatFormatting.DARK_GRAY));
		y += ROW;

		userLabelY = y;
		y += LABEL;
		userBox = addRenderableWidget(new EditBox(font, left, y, WIDTH, 20, Component.literal("Username")));
		userBox.setMaxLength(255);
		userBox.setValue(user);
		y += ROW;

		passLabelY = y;
		y += LABEL;
		passBox = addRenderableWidget(new EditBox(font, left, y, WIDTH, 20, Component.literal("Password")));
		passBox.setMaxLength(255);
		passBox.setValue(pass);
		passBox.addFormatter((text, start) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
		y += ROW;

		statusY = y + 2;
		y += 14;
		addRenderableWidget(Button.builder(Component.literal("Test"), b -> test())
				.bounds(left, y, WIDTH / 2 - 2, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply())
				.bounds(left + WIDTH / 2 + 2, y, WIDTH / 2 - 2, 20).build());
		y += ROW;
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
				.bounds(left, y, WIDTH, 20).build());
	}

	private ProxySettings typed() {
		return new ProxySettings(typeButton.getValue(), hostBox.getValue(), ProxySettings.parsePort(portBox.getValue()),
				userBox.getValue(), passBox.getValue());
	}

	/** Tries the typed settings. Saves nothing. */
	private void test() {
		ProxySettings settings = typed();
		String problem = settings.problem();
		if (problem != null) {
			setStatus(problem, RED);
			return;
		}
		int run = ++testRun;
		followApplied = false;
		setStatus("Testing " + settings.type().label() + " to " + ProxyState.TEST_HOST + "...", YELLOW);
		ProxyState.test(settings, result -> minecraft.execute(() -> {
			if (run == testRun) {
				setStatus(result.ok() ? "Works! " + result.message() : result.message(), result.ok() ? GREEN : RED);
			}
		}));
	}

	/** Saves the typed settings; from now on every server connection uses them. Then tests them. */
	private void apply() {
		ProxySettings settings = typed();
		boolean enabled = enabledButton.getValue();
		String problem = settings.problem();
		if (enabled && problem != null) {
			setStatus(problem, RED);
			return;
		}
		FemboyClientConfig config = FemboyClientConfig.get();
		config.proxyEnabled = enabled;
		config.proxyType = settings.type().name();
		config.proxyHost = settings.host();
		config.proxyPort = problem == null ? settings.port() : config.proxyPort;
		config.proxyUsername = settings.username();
		config.proxyPassword = settings.password();
		config.save();
		testRun++;
		followApplied = true;
		ProxyState.applied();
		showAppliedStatus();
	}

	private void showAppliedStatus() {
		ProxyState.Status status = ProxyState.status();
		String message = ProxyState.message();
		switch (status) {
			case CONNECTED -> setStatus("Connected. " + message, GREEN);
			case FAILED -> setStatus(message, RED);
			case TESTING -> setStatus("Testing...", YELLOW);
			case OFF -> setStatus(message, GRAY);
			default -> setStatus("", GRAY);
		}
	}

	private void setStatus(String text, int color) {
		statusText = text;
		statusColor = color;
	}

	@Override
	public void tick() {
		super.tick();
		// The applied proxy's test answers in the background: show it when it does.
		if (followApplied) {
			showAppliedStatus();
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title, width / 2, Math.max(10, hostLabelY - ROW * 2 - 18), WHITE);
		graphics.text(font, "Proxy address and port", left, hostLabelY + 1, GRAY);
		graphics.text(font, "Username (optional)", left, userLabelY + 1, GRAY);
		graphics.text(font, "Password (optional)", left, passLabelY + 1, GRAY);
		if (!statusText.isEmpty()) {
			graphics.centeredText(font, font.plainSubstrByWidth(statusText, width - 20), width / 2, statusY, statusColor);
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}
}
