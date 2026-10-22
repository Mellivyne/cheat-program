package com.cheatprogram.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Typed and remapped access to the extraction queue owned by a graphics instance. */
@Mixin(GuiGraphics.class)
public interface GuiGraphicsStateAccess {
    @Accessor("guiRenderState") GuiRenderState cheatprogram$guiRenderState();
}
