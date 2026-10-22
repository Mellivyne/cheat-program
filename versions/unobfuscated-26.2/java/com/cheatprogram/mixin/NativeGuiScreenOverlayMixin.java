package com.cheatprogram.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
/** The real GUI controller owns screen extraction from 26.2 onward. */
@Mixin(Gui.class)
public abstract class NativeGuiScreenOverlayMixin {
 @WrapOperation(method="extractRenderState",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
 private void cheatprogram$fullscreenPointer(Screen screen,GuiGraphicsExtractor graphics,int x,int y,float partial,Operation<Void> original){
  original.call(screen,graphics,x,y,partial);com.cheatprogram.hud.HudRenderer.afterScreen(graphics,screen);com.cheatprogram.render.AppCastPointer.afterScreen(graphics,screen);
 }
}
