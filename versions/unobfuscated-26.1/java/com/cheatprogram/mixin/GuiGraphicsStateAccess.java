package com.cheatprogram.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Typed and remapped access to the extraction queue owned by a graphics instance. */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiGraphicsStateAccess {
    @Accessor("guiRenderState") GuiRenderState cheatprogram$guiRenderState();
}
