package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;

/**
 * No Debuff (a copy of OdinClient 1.2.5.beta7's), each part with its own switch: No Blindness (no blindness fog), No Portal
 * Effect (no nether portal overlay), No Shield Particle (no witch and heart particles within 13 blocks of you, the purple
 * shield and wither impact hearts), No Water FOV (no zoom in water), No Fire Overlay (no fire on your screen), No Push (you are
 * not pushed out of blocks you stand in) and See Through Blocks (no block texture on your screen when your head is in one).
 */
public final class NoDebuff {

	private NoDebuff() {
	}

	private static boolean on() {
		FemboyClientConfig config = FemboyClientConfig.get();
		return config.enabled && config.noDebuff;
	}

	public static boolean noBlindness() {
		return on() && FemboyClientConfig.get().noDebuffBlindness;
	}

	public static boolean noPortal() {
		return on() && FemboyClientConfig.get().noDebuffPortal;
	}

	public static boolean noWaterFov() {
		return on() && FemboyClientConfig.get().noDebuffWaterFov;
	}

	public static boolean noFire() {
		return on() && FemboyClientConfig.get().noDebuffFire;
	}

	public static boolean noPush() {
		return on() && FemboyClientConfig.get().noDebuffPush;
	}

	public static boolean seeThroughBlocks() {
		return on() && FemboyClientConfig.get().noDebuffSeeThrough;
	}

	/** True if this particle from the server should not be shown. */
	public static boolean hideParticle(ParticleType<?> type, double x, double y, double z) {
		if (!on() || !FemboyClientConfig.get().noDebuffShieldParticles || (type != ParticleTypes.WITCH && type != ParticleTypes.HEART)) {
			return false;
		}
		var player = Minecraft.getInstance().player;
		return player != null && player.distanceToSqr(x, y, z) <= 169;
	}
}
