package com.cheatprogram.mixin;

import com.cheatprogram.core.ModuleManager;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress", at = @At("HEAD"),cancellable=true)
	private void cheatprogram$keyPress(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if(window==mc.getWindow().getWindow()&&action==org.lwjgl.glfw.GLFW.GLFW_PRESS&&mc.options.keyFullscreen.matches(key,scanCode))com.cheatprogram.capture.AppCastWindowCompatibility.release();
		if(window==mc.getWindow().getWindow()&&com.cheatprogram.render.WorldControlPanel.key(key,action)){ci.cancel();return;}
		if(window==mc.getWindow().getWindow()&&com.cheatprogram.module.visual.AppCast.key(key,action)){ci.cancel();return;}
		if (window == mc.getWindow().getWindow() && mc.screen == null && mc.getOverlay() == null && mc.player != null) {
			ModuleManager.onInput(key, action);
		}
	}
}
