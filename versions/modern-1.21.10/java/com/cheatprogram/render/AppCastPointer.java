package com.cheatprogram.render;

import com.cheatprogram.compat.gl.BufferUploader;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.GuiBase;
import com.cheatprogram.module.visual.AppCast;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import com.cheatprogram.compat.gl.CoreShaders;
import org.lwjgl.glfw.GLFW;

/** A single software pointer survives exclusive-fullscreen hardware-cursor changes. */
public final class AppCastPointer {
    private static boolean ordinaryOwned;
    private static long ownedWindow;
    private AppCastPointer(){ }
    private static boolean ordinary(Screen screen){
        Minecraft mc=Minecraft.getInstance();AppCast cast=ModuleManager.get(AppCast.class);
        return screen instanceof GuiBase&&mc.screen==screen&&mc.isWindowActive()&&mc.getWindow().isFullscreen()
                &&cast!=null&&cast.isEnabled()&&WorldControlPanel.virtualPointer((GuiBase)screen)==null;
    }
    public static void beforeFrame(){if(!ordinary(Minecraft.getInstance().screen))releaseOrdinary();}
    private static void releaseOrdinary(){
        if(!ordinaryOwned)return;ordinaryOwned=false;
        if(GLFW.glfwGetInputMode(ownedWindow,GLFW.GLFW_CURSOR)==GLFW.GLFW_CURSOR_HIDDEN)
            GLFW.glfwSetInputMode(ownedWindow,GLFW.GLFW_CURSOR,GLFW.GLFW_CURSOR_NORMAL);
    }
    /** Runs after the real screen, never while capturing an embedded page. */
    public static void afterScreen(GuiGraphics graphics,Screen screen){
        if(!ordinary(screen)){releaseOrdinary();return;}
        Minecraft mc=Minecraft.getInstance();long window=mc.getWindow().handle();ownedWindow=window;ordinaryOwned=true;
        if(GLFW.glfwGetInputMode(window,GLFW.GLFW_CURSOR)!=GLFW.GLFW_CURSOR_HIDDEN)
            GLFW.glfwSetInputMode(window,GLFW.GLFW_CURSOR,GLFW.GLFW_CURSOR_HIDDEN);
        float x=(float)(mc.mouseHandler.xpos()*mc.getWindow().getWidth()/Math.max(1,mc.getWindow().getScreenWidth()));
        float y=(float)(mc.mouseHandler.ypos()*mc.getWindow().getHeight()/Math.max(1,mc.getWindow().getScreenHeight()));
        R2D.begin(graphics);try{draw(x,y);}finally{R2D.end();}
    }
    /** Coordinates are physical framebuffer pixels on the current UI canvas. */
    public static void draw(float x,float y){
        if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||y<0||x>=R2D.width()||y>=R2D.height())return;
        float size=Math.max(12,Math.min(26,R2D.height()/65f));R2D.applyState();
        triangle(x-1,y-1,size+3,0xff101010);triangle(x,y,size,0xffffffff);R2D.applyState();
    }
    private static void triangle(float x,float y,float size,int color){
        float scale=(float)Minecraft.getInstance().getWindow().getGuiScale();var pose=R2D.canvasPose();
        RenderSystem.setShader(CoreShaders.POSITION_COLOR);RenderSystem.setShaderColor(1,1,1,1);
        BufferBuilder b=Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.POSITION_COLOR);
        b.addVertex(pose,x/scale,y/scale,0).setColor(color);
        b.addVertex(pose,x/scale,(y+size)/scale,0).setColor(color);
        b.addVertex(pose,(x+size*.65f)/scale,(y+size*.65f)/scale,0).setColor(color);
        BufferUploader.drawWithShader(b.buildOrThrow());
    }
}
