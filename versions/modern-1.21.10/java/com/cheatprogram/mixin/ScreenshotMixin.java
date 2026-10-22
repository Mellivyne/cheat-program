package com.cheatprogram.mixin;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.dev.DevTools;
import com.cheatprogram.render.Icon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
	@ModifyVariable(method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V", at = @At("HEAD"), argsOnly = true, index = 4)
	private static Consumer<Component> cheatprogram$notifyAfterSave(Consumer<Component> callback) {
		// Capture this now: saving and its callback run asynchronously after the dev capture ends.
		boolean automatic = DevTools.isCapturingScreenshot();
		return message -> {
			callback.accept(message);
			if (!automatic && message.getContents() instanceof TranslatableContents contents
					&& "screenshot.success".equals(contents.getKey())) {
				Minecraft.getInstance().execute(() -> Notifications.push(Icon.CHECK,
						Lang.t("ui.notify.screenshot"), Lang.t("ui.notify.screenshotSaved")));
			}
		};
	}
}
