package com.cheatprogram.compat.legacy;
import org.joml.*;
import net.minecraft.client.renderer.ShaderInstance;
import com.mojang.blaze3d.vertex.VertexSorting;
public final class LegacyRenderSystem {
    private static final Matrix4fStack STACK=new Matrix4fStack(64);
    public static Matrix4fStack getModelViewStack(){return STACK;}
    public static void captureModelView(){STACK.set(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix());}
    public static void withModelView(Runnable draw){
        var actual=com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();var previous=new Matrix4f(actual.last().pose());
        actual.last().pose().set(STACK);com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        try{draw.run();}
        finally{actual.last().pose().set(previous);com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();}
    }

    public static void drawWithShader(com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer mesh){withModelView(()->com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(mesh));}
    public static LegacyProjectionType getProjectionType(){return com.mojang.blaze3d.systems.RenderSystem.getVertexSorting()==VertexSorting.ORTHOGRAPHIC_Z?LegacyProjectionType.ORTHOGRAPHIC:LegacyProjectionType.PERSPECTIVE;}
    public static void setProjectionMatrix(Matrix4f m,LegacyProjectionType type){com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(m,type==LegacyProjectionType.ORTHOGRAPHIC?VertexSorting.ORTHOGRAPHIC_Z:VertexSorting.DISTANCE_TO_ORIGIN);}
    public static LegacyFogParameters getShaderFog(){var c=com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor();return new LegacyFogParameters(com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart(),com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd(),com.mojang.blaze3d.systems.RenderSystem.getShaderFogShape(),c[0],c[1],c[2],c[3]);}
    public static void setShaderFog(LegacyFogParameters p){com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(p.start());com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(p.end());com.mojang.blaze3d.systems.RenderSystem.setShaderFogShape(p.shape());com.mojang.blaze3d.systems.RenderSystem.setShaderFogColor(p.red(),p.green(),p.blue(),p.alpha());}
    public static void setShader(ShaderInstance shader){com.mojang.blaze3d.systems.RenderSystem.setShader(()->shader);}
    public static void setShader(java.util.function.Supplier<ShaderInstance> shader){com.mojang.blaze3d.systems.RenderSystem.setShader(shader);}
    public static void clearShader(){setShader((ShaderInstance)null);}
public static boolean isOnRenderThread(){return com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread();}
public static void assertOnRenderThread(){com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();}
public static void recordRenderCall(com.mojang.blaze3d.pipeline.RenderCall p0){com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(p0);}
public static void disableDepthTest(){com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();}
public static void enableDepthTest(){com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();}
public static void enableScissor(int p0, int p1, int p2, int p3){com.mojang.blaze3d.systems.RenderSystem.enableScissor(p0, p1, p2, p3);}
public static void disableScissor(){com.mojang.blaze3d.systems.RenderSystem.disableScissor();}
public static void depthFunc(int p0){com.mojang.blaze3d.systems.RenderSystem.depthFunc(p0);}
public static void depthMask(boolean p0){com.mojang.blaze3d.systems.RenderSystem.depthMask(p0);}
public static void enableBlend(){com.mojang.blaze3d.systems.RenderSystem.enableBlend();}
public static void disableBlend(){com.mojang.blaze3d.systems.RenderSystem.disableBlend();}
public static void blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor p0, com.mojang.blaze3d.platform.GlStateManager.DestFactor p1){com.mojang.blaze3d.systems.RenderSystem.blendFunc(p0, p1);}
public static void blendFunc(int p0, int p1){com.mojang.blaze3d.systems.RenderSystem.blendFunc(p0, p1);}
public static void blendFuncSeparate(com.mojang.blaze3d.platform.GlStateManager.SourceFactor p0, com.mojang.blaze3d.platform.GlStateManager.DestFactor p1, com.mojang.blaze3d.platform.GlStateManager.SourceFactor p2, com.mojang.blaze3d.platform.GlStateManager.DestFactor p3){com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(p0, p1, p2, p3);}
public static void blendFuncSeparate(int p0, int p1, int p2, int p3){com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(p0, p1, p2, p3);}
public static void enableCull(){com.mojang.blaze3d.systems.RenderSystem.enableCull();}
public static void disableCull(){com.mojang.blaze3d.systems.RenderSystem.disableCull();}
public static void enablePolygonOffset(){com.mojang.blaze3d.systems.RenderSystem.enablePolygonOffset();}
public static void disablePolygonOffset(){com.mojang.blaze3d.systems.RenderSystem.disablePolygonOffset();}
public static void polygonOffset(float p0, float p1){com.mojang.blaze3d.systems.RenderSystem.polygonOffset(p0, p1);}
public static void enableColorLogicOp(){com.mojang.blaze3d.systems.RenderSystem.enableColorLogicOp();}
public static void disableColorLogicOp(){com.mojang.blaze3d.systems.RenderSystem.disableColorLogicOp();}
public static void activeTexture(int p0){com.mojang.blaze3d.systems.RenderSystem.activeTexture(p0);}
public static void viewport(int p0, int p1, int p2, int p3){com.mojang.blaze3d.systems.RenderSystem.viewport(p0, p1, p2, p3);}
public static void colorMask(boolean p0, boolean p1, boolean p2, boolean p3){com.mojang.blaze3d.systems.RenderSystem.colorMask(p0, p1, p2, p3);}
public static void setShaderGlintAlpha(double p0){com.mojang.blaze3d.systems.RenderSystem.setShaderGlintAlpha(p0);}
public static void setShaderGlintAlpha(float p0){com.mojang.blaze3d.systems.RenderSystem.setShaderGlintAlpha(p0);}
public static float getShaderGlintAlpha(){return com.mojang.blaze3d.systems.RenderSystem.getShaderGlintAlpha();}
public static void setShaderLights(org.joml.Vector3f p0, org.joml.Vector3f p1){com.mojang.blaze3d.systems.RenderSystem.setShaderLights(p0, p1);}
public static void setupShaderLights(net.minecraft.client.renderer.ShaderInstance p0){com.mojang.blaze3d.systems.RenderSystem.setupShaderLights(p0);}
public static void setShaderColor(float p0, float p1, float p2, float p3){com.mojang.blaze3d.systems.RenderSystem.setShaderColor(p0, p1, p2, p3);}
public static float[] getShaderColor(){return com.mojang.blaze3d.systems.RenderSystem.getShaderColor();}
public static void lineWidth(float p0){com.mojang.blaze3d.systems.RenderSystem.lineWidth(p0);}
public static float getShaderLineWidth(){return com.mojang.blaze3d.systems.RenderSystem.getShaderLineWidth();}
public static void defaultBlendFunc(){com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();}
public static net.minecraft.client.renderer.ShaderInstance getShader(){return com.mojang.blaze3d.systems.RenderSystem.getShader();}
public static void setShaderTexture(int p0, net.minecraft.resources.ResourceLocation p1){com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(p0, p1);}
public static void setShaderTexture(int p0, int p1){com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(p0, p1);}
public static int getShaderTexture(int p0){return com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(p0);}
public static void setTextureMatrix(org.joml.Matrix4f p0){com.mojang.blaze3d.systems.RenderSystem.setTextureMatrix(p0);}
public static org.joml.Matrix4f getProjectionMatrix(){return com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix();}
public static org.joml.Matrix4f getTextureMatrix(){return com.mojang.blaze3d.systems.RenderSystem.getTextureMatrix();}
}
