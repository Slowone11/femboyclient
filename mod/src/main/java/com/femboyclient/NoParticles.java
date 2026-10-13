package com.femboyclient;

import net.minecraft.client.Minecraft;

import java.util.concurrent.atomic.AtomicLong;

/**
 * No Particles: while on, no particle is ever added to the game's particle engine, so block breaking, mob and creature effects,
 * hits and crits, explosions, potion swirls, rain splashes and Hypixel's ability particles never appear. Every particle, whoever
 * made it (the game, a packet from the server, another mod), goes through {@code ParticleEngine.add}, which
 * {@code ParticleEngineMixin} cancels. Only what you see changes: nothing is sent to the server.
 */
public final class NoParticles {

	private static final AtomicLong BLOCKED = new AtomicLong();

	private NoParticles() {
	}

	/** True while particles are hidden. Called by the mixin for every particle, so it only reads a field. */
	public static boolean active() {
		FemboyClientConfig config = FemboyClientConfig.get();
		return config.enabled && config.noParticles;
	}

	/** Called by the mixin for each particle it stops. */
	public static void blocked() {
		BLOCKED.incrementAndGet();
	}

	/** How many particles were stopped since the game started, for the G menu. */
	public static long blockedCount() {
		return BLOCKED.get();
	}

	/** Turns the module on or off. Turning it on also clears the particles already on screen. */
	public static void set(FemboyClientConfig config, boolean on) {
		config.noParticles = on;
		config.save();
		if (on) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.particleEngine != null) {
				mc.particleEngine.clearParticles();
			}
		}
	}
}
