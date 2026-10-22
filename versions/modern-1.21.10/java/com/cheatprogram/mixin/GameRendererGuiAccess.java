package com.cheatprogram.mixin;

import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Native GUI submission and its initialized no-fog uniform; no reflective name lookups. */
@Mixin(GameRenderer.class)
public interface GameRendererGuiAccess {
    @Accessor("guiRenderer") GuiRenderer cheatprogram$guiRenderer();
    @Accessor("guiRenderState") GuiRenderState cheatprogram$guiRenderState();
    @Accessor("fogRenderer") FogRenderer cheatprogram$fogRenderer();
}
