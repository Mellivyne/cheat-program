package com.cheatprogram.mixin;

import com.cheatprogram.hud.GlassChat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

/** Replaces only wide background rectangles; native glyphs, tags and scrollbar remain native. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
    @Shadow @Final private List<GuiMessage.Line> trimmedMessages;
    @Shadow private int chatScrollbarPos;
    @Unique private boolean cheatprogram$glassBackground;

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("HEAD"))
    private void cheatprogram$glass(GuiGraphics graphics, Font font, int tick, int mouseX, int mouseY, boolean focused, boolean interactive, CallbackInfo ci) {
        cheatprogram$glassBackground = GlassChat.render(graphics, (ChatComponent)(Object)this, trimmedMessages, chatScrollbarPos, tick, focused);
    }
    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V", at = @At("RETURN"))
    private void cheatprogram$finishGlass(GuiGraphics graphics, Font font, int tick, int mouseX, int mouseY, boolean focused, boolean interactive, CallbackInfo ci) {
        cheatprogram$glassBackground = false;
    }

    // 1.21.11 routes both the main render and its line lambda through this private
    // native drawing interface. Coerce keeps that inaccessible interface opaque.
    @WrapOperation(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/ChatComponent$ChatGraphicsAccess;fill(IIIII)V"))
    private void cheatprogram$replaceBackground(@Coerce Object graphics, int left, int top, int right, int bottom, int color, Operation<Void> original) {
        if (!cheatprogram$glassBackground || right - left <= 2) original.call(graphics, left, top, right, bottom, color);
    }
}
