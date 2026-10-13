package com.femboyclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

/**
 * Camera (a copy of OdinClient 1.2.5.beta7's): No Front Camera skips the front-facing third person view; Distance sets how far
 * the third person camera is from you (vanilla is 4); Camera Clip lets it go through blocks instead of moving in front of
 * them. Freelook: while its key is held (or until it is pressed again, with Toggle), the camera goes to third person and the
 * mouse turns the camera around you while you keep looking and walking the way you were. Only what you see changes.
 */
public final class CameraTweaks {

	public static final HandMan.Range DISTANCE = new HandMan.Range(30, 120, 1);     // tenths of a block

	private static boolean freelook, keyWasDown;
	private static float yaw, pitch;
	private static CameraType lastType;

	private CameraTweaks() {
	}

	private static boolean on() {
		FemboyClientConfig config = FemboyClientConfig.get();
		return config.enabled && config.camera;
	}

	/** The third person distance to use, from the game's own (4 times your size). */
	public static float distance(float vanilla) {
		return on() ? vanilla / 4F * FemboyClientConfig.get().cameraDistance / 10F : vanilla;
	}

	public static boolean clip() {
		return on() && FemboyClientConfig.get().cameraClip;
	}

	public static boolean freelook() {
		return freelook;
	}

	public static float yaw() {
		return yaw;
	}

	public static float pitch() {
		return pitch;
	}

	/** The mouse moved while freelooking: it turns the camera, the same amount it would have turned you. */
	public static void turn(double dx, double dy) {
		yaw += (float) dx * 0.15F;
		pitch = Mth.clamp(pitch + (float) dy * 0.15F, -90F, 90F);
	}

	/** Called every client tick. */
	public static void tick(Minecraft mc) {
		FemboyClientConfig config = FemboyClientConfig.get();
		boolean down = config.freelookKey >= 0 && McCompat.screen(mc) == null && InputConstants.isKeyDown(mc.getWindow(), config.freelookKey);
		if (mc.player == null) {
			freelook = false;
			keyWasDown = down;
			return;
		}
		if (on() && config.cameraNoFront && mc.options.getCameraType() == CameraType.THIRD_PERSON_FRONT) {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		}
		if (down && !keyWasDown) {
			if (!freelook && on()) {
				start(mc);
			} else if (freelook && (config.freelookToggle || !on())) {
				stop(mc);
			}
		}
		if (freelook && ((!down && !config.freelookToggle) || !on())) {
			stop(mc);
		}
		keyWasDown = down;
	}

	private static void start(Minecraft mc) {
		yaw = mc.player.getYRot();
		pitch = mc.player.getXRot();
		freelook = true;
		lastType = mc.options.getCameraType();
		mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
	}

	private static void stop(Minecraft mc) {
		freelook = false;
		mc.options.setCameraType(lastType != null ? lastType : CameraType.FIRST_PERSON);
		lastType = null;
	}
}
