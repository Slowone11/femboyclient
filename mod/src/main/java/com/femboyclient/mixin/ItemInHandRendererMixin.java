package com.femboyclient.mixin;

import com.femboyclient.FemboyClientConfig;
import com.femboyclient.HandMan;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link HandMan} change the first-person hand. {@code submitArmWithItem} draws one arm (with its item, or empty) each
 * frame. Every hook is optional (require = 0): if a game version renames something, the game still starts and only that part
 * of HandMan stops working.
 */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {

	@Shadow
	private void applyItemArmAttackTransform(PoseStack pose, HumanoidArm arm, float attack) {
	}

	/** Size, position and rotation, applied once the game has started this arm's own pose. */
	@Inject(method = "submitArmWithItem", require = 0,
			at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;pushPose()V", shift = At.Shift.AFTER, ordinal = 0))
	private void femboyclient$handManTransform(AbstractClientPlayer player, float partialTick, float xRot, InteractionHand hand,
			float attack, ItemStack stack, float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (HandMan.active()) {
			HandMan.transform(pose, arm(player, hand));
		}
	}

	/** No Swing: the hand never swings forward. The swing still happens; only the drawing ignores it. */
	@ModifyVariable(method = "submitArmWithItem", at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 0)
	private float femboyclient$handManNoSwing(float attack) {
		return HandMan.active() && FemboyClientConfig.get().handNoSwing ? 0F : attack;
	}

	/** No Equip Reset: the item does not dip down and come back up when you switch to it. */
	@ModifyVariable(method = "submitArmWithItem", at = @At("HEAD"), argsOnly = true, ordinal = 3, require = 0)
	private float femboyclient$handManNoEquip(float equip) {
		return HandMan.active() && FemboyClientConfig.get().handNoEquipReset ? 0F : equip;
	}

	/**
	 * No Block: an item held up to block (a sword or a shield) is drawn as if it were not, so it swings normally instead.
	 * Not a @Redirect: MixinExtras 0.5.4 crashes the game while loading any @Redirect in this class ("ArrayList cannot be
	 * cast to AnnotationNode").
	 */
	@ModifyExpressionValue(method = "submitArmWithItem", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;isUsingItem()Z"))
	private boolean femboyclient$handManNoBlock(boolean using, AbstractClientPlayer player) {
		return using && !(noBlock() && player.getUseItem().getUseAnimation() == ItemUseAnimation.BLOCK);
	}

	/**
	 * Block Hit: the 1.7 look, where the item keeps swinging while you block. The block pose starts with the game's normal
	 * hand position (applyItemArmTransform) and the swing is added right after it, before the block turn.
	 */
	@Inject(method = "submitArmWithItem", require = 0,
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;applyItemArmTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/HumanoidArm;F)V",
					shift = At.Shift.AFTER))
	private void femboyclient$handManBlockHit(AbstractClientPlayer player, float partialTick, float xRot, InteractionHand hand,
			float attack, ItemStack stack, float equip, PoseStack pose, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		if (HandMan.active() && FemboyClientConfig.get().handBlockHit && !noBlock() && player.isUsingItem()
				&& player.getUsedItemHand() == hand && stack.getUseAnimation() == ItemUseAnimation.BLOCK) {
			applyItemArmAttackTransform(pose, arm(player, hand), attack);
		}
	}

	private static boolean noBlock() {
		return HandMan.active() && FemboyClientConfig.get().handNoBlock;
	}

	private static HumanoidArm arm(AbstractClientPlayer player, InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND ? player.getMainArm() : player.getMainArm().getOpposite();
	}
}
