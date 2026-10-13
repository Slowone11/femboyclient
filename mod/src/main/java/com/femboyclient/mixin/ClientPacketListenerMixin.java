package com.femboyclient.mixin;

import com.femboyclient.NoDebuff;
import com.femboyclient.PetSwapper;
import com.femboyclient.ServerStats;
import com.femboyclient.TPMaze;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packets the ported Odin modules read: the server's time (TPS) and pings (Server Display, Chat Commands), teleports (TP Maze
 * solver) and particles (No Debuff); and the menu packets Pet Swapper uses to work the /pets menu without showing it. The
 * handlers run once on the network thread, which hands the packet to the game thread and stops; TAIL is only reached on the
 * game thread, and a HEAD hook that needs the game thread checks for it itself. All optional (require = 0).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

	@Inject(method = "handleSetTime", at = @At("TAIL"), require = 0)
	private void femboyclient$tps(ClientboundSetTimePacket packet, CallbackInfo ci) {
		ServerStats.onTimeUpdate();
	}

	@Inject(method = "handlePongResponse", at = @At("TAIL"), require = 0)
	private void femboyclient$ping(ClientboundPongResponsePacket packet, CallbackInfo ci) {
		ServerStats.onPong(packet.time());
	}

	/** The game only pings the server while its network chart is open; Server Display needs the pings all the time. */
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;showNetworkCharts()Z"), require = 0)
	private boolean femboyclient$alwaysPing(boolean original) {
		return original || ServerStats.wanted();
	}

	@Inject(method = "handleMovePlayer", at = @At("TAIL"), require = 0)
	private void femboyclient$teleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		TPMaze.onTeleport();
	}

	@Inject(method = "handleOpenScreen", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$hiddenPetsMenu(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
		if (PetSwapper.onOpenScreen(packet.getContainerId(), packet.getType(), packet.getTitle())) {
			ci.cancel();
		}
	}

	@Inject(method = "handleContainerContent", at = @At("TAIL"), require = 0)
	private void femboyclient$petsMenuContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
		PetSwapper.onContent(packet.containerId(), true);
	}

	@Inject(method = "handleContainerSetSlot", at = @At("TAIL"), require = 0)
	private void femboyclient$petsMenuSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
		PetSwapper.onContent(packet.getContainerId(), false);
	}

	@Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$petsMenuClose(ClientboundContainerClosePacket packet, CallbackInfo ci) {
		if (PetSwapper.onServerClose(packet.getContainerId())) {
			ci.cancel();
		}
	}

	@Inject(method = "handleParticleEvent", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$noShieldParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		if (NoDebuff.hideParticle(packet.getParticle().getType(), packet.getX(), packet.getY(), packet.getZ())) {
			ci.cancel();
		}
	}
}
