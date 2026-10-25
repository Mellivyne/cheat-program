package com.cheatprogram.compat.legacy;
import net.minecraft.client.renderer.*;
public final class LegacyCoreShaders {
public static final java.util.function.Supplier<ShaderInstance> RENDERTYPE_ENTITY_SOLID=GameRenderer::getRendertypeEntitySolidShader;
public static final java.util.function.Supplier<ShaderInstance> RENDERTYPE_LINES=GameRenderer::getRendertypeLinesShader;
public static final java.util.function.Supplier<ShaderInstance> POSITION_TEX_COLOR=GameRenderer::getPositionTexColorShader;
public static final java.util.function.Supplier<ShaderInstance> POSITION_COLOR=GameRenderer::getPositionColorShader;
}