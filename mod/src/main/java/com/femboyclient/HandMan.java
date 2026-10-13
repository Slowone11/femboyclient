package com.femboyclient;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.world.entity.HumanoidArm;

/**
 * HandMan: changes how your first-person hand and held item look, a copy of OdinClient's Animations module. Size, position
 * and rotation move the hand around where the game draws it ({@code ItemInHandRendererMixin}); Speed makes the swing
 * animation faster or slower ({@code LivingEntityMixin}). The switches hide the swing, the dip when you change items, and the
 * block pose, or keep the swing showing while you block. Only what you see changes: nothing is sent to the server, and
 * attacks and mining are not any faster.
 *
 * Every number is kept as a whole number in hundredths (degrees for the rotations), the same steps and ranges as Odin:
 * size and speed are exponents (0 is the game's own size and speed), X/Y/Z are fractions of the game's own hand position.
 */
public final class HandMan {

	/** The smallest and biggest value of a setting, and the step its G menu slider snaps to. */
	public record Range(int min, int max, int step) {
		public int clamp(int value) {
			return Math.max(min, Math.min(max, value));
		}
	}

	public static final Range SIZE = new Range(-150, 150, 5);
	public static final Range X = new Range(-250, 150, 5);
	public static final Range Y = new Range(-150, 150, 5);
	public static final Range Z = new Range(-150, 300, 5);
	public static final Range ROTATION = new Range(-180, 180, 5);
	public static final Range SPEED = new Range(-200, 100, 5);

	// Where the game puts the right hand's item, before the swing. The left hand is the mirror image.
	private static final float HAND_X = 0.56F, HAND_Y = -0.52F, HAND_Z = -0.72F;

	private HandMan() {
	}

	/** True while the module is on. Called every frame by the mixins, so it only reads fields. */
	public static boolean active() {
		FemboyClientConfig config = FemboyClientConfig.get();
		return config.enabled && config.handMan;
	}

	/**
	 * Moves, turns and scales the hand about the spot the game draws it at, so a smaller hand stays in the corner instead of
	 * sliding to the middle of the screen. Called right after the game starts drawing one arm.
	 */
	public static void transform(PoseStack pose, HumanoidArm arm) {
		FemboyClientConfig c = FemboyClientConfig.get();
		if (c.handSize == 0 && c.handX == 0 && c.handY == 0 && c.handZ == 0 && c.handYaw == 0 && c.handPitch == 0 && c.handRoll == 0) {
			return;
		}
		float side = arm == HumanoidArm.RIGHT ? 1F : -1F;
		float px = side * HAND_X, py = HAND_Y, pz = HAND_Z;
		pose.translate(px + side * HAND_X * c.handX / 100F, py - HAND_Y * c.handY / 100F, pz + HAND_Z * c.handZ / 100F);
		pose.mulPose(Axis.XP.rotationDegrees(c.handPitch));
		pose.mulPose(Axis.YP.rotationDegrees(side * c.handYaw));
		pose.mulPose(Axis.ZP.rotationDegrees(side * c.handRoll));
		float scale = scale(c.handSize);
		pose.scale(scale, scale, scale);
		pose.translate(-px, -py, -pz);
	}

	/** The size setting as a factor: 0 is 1×, each +1.00 is e times bigger. */
	public static float scale(int size) {
		return (float) Math.exp(size / 100.0);
	}

	/**
	 * How many ticks one swing takes. {@code vanilla} is the game's own length (haste and mining fatigue included),
	 * {@code plain} the held item's length without them, used when Ignore Haste is on. Never less than one tick.
	 */
	public static int swingDuration(int vanilla, int plain, int speed, boolean ignoreHaste) {
		int base = ignoreHaste ? plain : vanilla;
		return Math.max(1, (int) (base * Math.exp(-speed / 100.0)));
	}

	/** Size or speed for the G menu, as a percentage of the game's own: "100%". */
	public static String percent(int exponent) {
		return Math.round(Math.exp(exponent / 100.0) * 100) + "%";
	}

	/** X, Y or Z for the G menu: "+0.25". */
	public static String offset(int hundredths) {
		return String.format("%+.2f", hundredths / 100.0);
	}

	/** Puts every HandMan setting back to the game's own look. The on/off switch is left alone. */
	public static void reset(FemboyClientConfig config) {
		config.handSize = 0;
		config.handX = 0;
		config.handY = 0;
		config.handZ = 0;
		config.handYaw = 0;
		config.handPitch = 0;
		config.handRoll = 0;
		config.handSpeed = 0;
		config.handIgnoreHaste = false;
		config.handBlockHit = false;
		config.handNoEquipReset = false;
		config.handNoSwing = false;
		config.handNoBlock = false;
		config.save();
	}
}
