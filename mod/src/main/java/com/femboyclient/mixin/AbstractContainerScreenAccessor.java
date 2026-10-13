package com.femboyclient.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Where a container screen draws its window, and the slot under the mouse. The Wiki Bar sits under the window and its panel to
 * the right of it, and the wiki key looks up the hovered item. The game keeps these protected.
 */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

	@Accessor("leftPos")
	int femboyclient$leftPos();

	@Accessor("topPos")
	int femboyclient$topPos();

	@Accessor("imageWidth")
	int femboyclient$imageWidth();

	@Accessor("imageHeight")
	int femboyclient$imageHeight();

	@Accessor("hoveredSlot")
	Slot femboyclient$hoveredSlot();
}
