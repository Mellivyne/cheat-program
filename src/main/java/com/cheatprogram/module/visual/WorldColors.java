package com.cheatprogram.module.visual;

import com.cheatprogram.core.*;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.render.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import com.mojang.blaze3d.platform.GlStateManager;

/** Applies to the rendered world before GUI drawing; no weather packets or world fields are altered. */
public final class WorldColors extends Module {
    private final ModeSetting preset=add(new ModeSetting("preset","tone_custom","tone_custom","tone_warm","tone_ice","tone_dream","tone_monochrome"));
    private final NumberSetting strength=add(new NumberSetting("strength",60,0,100,5,"%"));
    private final NumberSetting red=add(new NumberSetting("red",255,0,255,1));
    private final NumberSetting green=add(new NumberSetting("green",240,0,255,1));
    private final NumberSetting blue=add(new NumberSetting("blue",225,0,255,1));
    private final NumberSetting saturation=add(new NumberSetting("saturation",110,0,200,5,"%"));
    private final NumberSetting exposure=add(new NumberSetting("exposure",100,25,200,5,"%"));
    private final NumberSetting bloom=add(new NumberSetting("bloom",15,0,100,5,"%"));
    private final BoolSetting fog=add(new BoolSetting("fog",true));
    private final NumberSetting fogStart=add(new NumberSetting("fogStart",24,0,256,1)).visibleWhen(fog::on);
    private final NumberSetting fogEnd=add(new NumberSetting("fogEnd",128,4,512,4)).visibleWhen(fog::on);
    private final BoolSetting preserveFluids=add(new BoolSetting("preserveFluids",true));
    private final ModeSetting weather=add(new ModeSetting("weather","weather_original","weather_original","weather_clear","weather_rain","weather_storm"));
    private final NumberSetting transition=add(new NumberSetting("transition",1200,100,5000,100,"ms"));
    private float rain,thunder;
    private Object world;
    public WorldColors(){super("worldcolors",Category.VISUAL);}
    public static WorldColors active(){WorldColors colors=ModuleManager.get(WorldColors.class);return colors!=null&&colors.isEnabled()&&inGame()?colors:null;}
    @Override public void onTick(){
        if(world!=mc.level){world=null;rain=mc.level.getRainLevel(1);thunder=mc.level.getThunderLevel(1);world=mc.level;}
        if(weather.is("weather_original")){rain=mc.level.getRainLevel(1);thunder=mc.level.getThunderLevel(1);return;}
        float speed=(float)(1-Math.exp(-50/transition.val()*4));
        rain+=((weather.is("weather_original")?rain:weather.is("weather_clear")?0:1)-rain)*speed;
        thunder+=((weather.is("weather_storm")?1:0)-thunder)*speed;
    }
    @Override public void onDisable(){world=null;}
    public float weather(float original,boolean storm){return weather.is("weather_original")||world!=mc.level?original:storm?thunder:rain;}
    private Vector4f tint(){return switch(preset.get()){
        case "tone_warm"->new Vector4f(1,.86f,.69f,1);case "tone_ice"->new Vector4f(.65f,.88f,1,1);
        case "tone_dream"->new Vector4f(.94f,.72f,1,1);case "tone_monochrome"->new Vector4f(1,1,1,1);default->new Vector4f(red.floatVal()/255,green.floatVal()/255,blue.floatVal()/255,1);
    };}
    private boolean normal(Camera camera){return !preserveFluids.on()||camera.getFluidInCamera()==FogType.NONE;}
    public Vector4f fogColor(Vector4f original,Camera camera){
        if(!fog.on()||!normal(camera))return original;
        Vector4f tint=tint();float factor=strength.floatVal()/100;
        return new Vector4f(original.x+(tint.x-original.x)*factor,original.y+(tint.y-original.y)*factor,original.z+(tint.z-original.z)*factor,original.w);
    }
    public FogParameters fog(FogParameters original,Camera camera){
        if(!fog.on()||!normal(camera))return original;
        float start=Math.min(fogStart.floatVal(),fogEnd.floatVal()-1),end=Math.max(start+1,fogEnd.floatVal());
        return new FogParameters(start,end,original.shape(),original.red(),original.green(),original.blue(),original.alpha());
    }
    public void draw(){
        if(!isEnabled()||strength.val()<=0||!inGame()||mc.getOverlay()!=null||com.cheatprogram.render.RearMirrorRenderer.isRendering())return;
        var shader=Shaders.get(Shaders.WORLD_COLOR);if(shader==null)return;
        boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),blend=GL11.glIsEnabled(GL11.GL_BLEND),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int readFramebuffer=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),drawFramebuffer=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport=new int[4],scissor=new int[4],colorMask=new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,scissor);GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK,colorMask);
        boolean clipped=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int srcRgb=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dstRgb=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),srcAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int texture0=RenderSystem.getShaderTexture(0),texture1=RenderSystem.getShaderTexture(1),texture2=RenderSystem.getShaderTexture(2);
        float[] shaderColor=RenderSystem.getShaderColor().clone();
        var previous=RenderSystem.getShader();
        try{
            RenderSystem.disableScissor();
            int texture=Backdrop.capture(0);
            RenderSystem.disableDepthTest();RenderSystem.depthMask(false);RenderSystem.disableCull();RenderSystem.disableBlend();
            RenderSystem.colorMask(true,true,true,true);RenderSystem.setShaderColor(1,1,1,1);
            Vector4f color=tint();shader.safeGetUniform("Tint").set(color.x,color.y,color.z,1);
            shader.safeGetUniform("HalfPixel").set(1f/mc.getMainRenderTarget().width,1f/mc.getMainRenderTarget().height);
            Backdrop.drawFullscreen(Shaders.WORLD_COLOR,texture,exposure.floatVal()/100,preset.is("tone_monochrome")?0:saturation.floatVal()/100,bloom.floatVal()/100,strength.floatVal()/100);
        }finally{
            RenderSystem.depthMask(mask);if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();
            if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();
            RenderSystem.blendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);RenderSystem.colorMask(colorMask[0]!=0,colorMask[1]!=0,colorMask[2]!=0,colorMask[3]!=0);
            RenderSystem.setShaderColor(shaderColor[0],shaderColor[1],shaderColor[2],shaderColor[3]);
            RenderSystem.setShaderTexture(0,texture0);RenderSystem.setShaderTexture(1,texture1);RenderSystem.setShaderTexture(2,texture2);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,readFramebuffer);GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,drawFramebuffer);
            RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            if(clipped)RenderSystem.enableScissor(scissor[0],scissor[1],scissor[2],scissor[3]);else RenderSystem.disableScissor();
            if(previous!=null)RenderSystem.setShader(previous);else RenderSystem.clearShader();
        }
    }
}
