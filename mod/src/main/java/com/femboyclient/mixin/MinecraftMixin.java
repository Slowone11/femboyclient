package com.femboyclient.mixin;

import com.femboyclient.TeammatesHighlight;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets {@link TeammatesHighlight} outline your dungeon teammates with the game's glowing outline. Optional (require = 0). */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

	@Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$teammateOutline(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (TeammatesHighlight.outlined(entity)) {
			cir.setReturnValue(true);
		}
	}
}
