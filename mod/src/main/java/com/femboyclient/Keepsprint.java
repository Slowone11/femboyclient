package com.femboyclient;

import net.minecraft.client.Minecraft;

/**
 * Auto Sprint (a copy of OdinClient's): holds the sprint key down for you every client tick, so you sprint whenever
 * vanilla would let you sprint with the key held. The class and config key keep the old Keepsprint name so an
 * existing config file still loads. Not while Pet Swapper has you standing still.
 */
final class Keepsprint {

	private Keepsprint() {
	}

	static void tick(Minecraft mc) {
		if (!FemboyClientConfig.get().keepsprint || mc.player == null || PetSwapper.frozen()) {
			return;
		}
		mc.options.keySprint.setDown(true);
	}
}
