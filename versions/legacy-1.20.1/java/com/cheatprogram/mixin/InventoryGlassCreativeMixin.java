package com.cheatprogram.mixin;
import com.cheatprogram.render.InventoryGlass;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(CreativeModeInventoryScreen.class)
public abstract class InventoryGlassCreativeMixin {
 @Inject(method="renderBg",at=@At("HEAD")) private void cheatprogram$begin(GuiGraphics g,float partial,int x,int y,CallbackInfo ci){InventoryGlass.beginBackground();}
 @Redirect(method="renderTabButton",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"))
 private void cheatprogram$tab(GuiGraphics g,ResourceLocation t,int x,int y,int u,int v,int w,int h){
  if(!InventoryGlass.control(g,(Screen)(Object)this,x,y,w,h,v%64>=32,false))g.blit(t,x,y,u,v,w,h);
 }
 @Redirect(method="renderBg",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"))
 private void cheatprogram$background(GuiGraphics g,ResourceLocation t,int x,int y,int u,int v,int w,int h){
  boolean painted=t.getPath().endsWith("tabs.png")?InventoryGlass.control(g,(Screen)(Object)this,x,y,w,h,true,u>=244):InventoryGlass.background(g,(AbstractContainerScreen<?>)(Object)this,x,y,w,h);
  if(!painted)g.blit(t,x,y,u,v,w,h);
 }
}
