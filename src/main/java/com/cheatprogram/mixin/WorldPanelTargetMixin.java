package com.cheatprogram.mixin;
import com.cheatprogram.render.WorldControlPanel;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(Minecraft.class)
public abstract class WorldPanelTargetMixin {
    @ModifyReturnValue(method="getMainRenderTarget",at=@At("RETURN"))
    private RenderTarget cheatprogram$worldPanel(RenderTarget original){RenderTarget target=WorldControlPanel.activeTarget();return target==null?original:target;}
}
