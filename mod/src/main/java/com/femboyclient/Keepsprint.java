package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;

/**
 * Keepsprint: sprints in every direction, not just forward like vanilla (which stops sprinting the moment you
 * strafe or walk backward). Every client tick, while any movement key is held and you are not sneaking, this
 * forces sprinting back on, right after the game's own tick would otherwise have turned it off. Letting go of
 * every movement key, or holding sneak, stops it exactly like vanilla.
 */
final class Keepsprint {

	private Keepsprint() {
	}

	static void tick(Minecraft mc) {
		if (!FemboyClientConfig.get().keepsprint || mc.player == null) {
			return;
		}
		ClientInput input = mc.player.input;
		if (input == null) {
			return;
		}
		Input keys = input.keyPresses;
		boolean moving = keys.forward() || keys.backward() || keys.left() || keys.right();
		if (moving && !keys.shift()) {
			mc.player.setSprinting(true);
		}
	}
}
