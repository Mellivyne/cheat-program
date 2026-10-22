package com.cheatprogram.mixin;

import com.cheatprogram.module.visual.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class FreecamMouseMixin {
	@Redirect(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void cheatprogram$turnCamera(LocalPlayer player, double yaw, double pitch) {
		if (Freecam.isActive()) Freecam.look(yaw, pitch);
		else player.turn(yaw, pitch);
	}

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$noBodyClicks(long window, net.minecraft.client.input.MouseButtonInfo event, int action, CallbackInfo ci) {
		int button=event.button();
		Minecraft mc = Minecraft.getInstance();
		if (Freecam.isActive() && window == mc.getWindow().handle() && mc.gui.screen() == null
				&& mc.gui.overlay() == null && button >= 0 && button <= 1) ci.cancel();
	}

	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$noBodyScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (Freecam.isActive() && window == mc.getWindow().handle() && mc.gui.screen() == null && mc.gui.overlay() == null) ci.cancel();
	}
}
