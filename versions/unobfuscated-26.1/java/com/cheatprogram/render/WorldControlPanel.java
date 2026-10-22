package com.cheatprogram.render;



import com.cheatprogram.compat.gl.BufferUploader;



import com.cheatprogram.CheatProgram;

import com.cheatprogram.core.ClientSettings;

import com.cheatprogram.core.Config;

import com.cheatprogram.core.Lang;

import com.cheatprogram.core.ModuleManager;

import com.cheatprogram.gui.ClickGuiScreen;

import com.cheatprogram.gui.GuiBase;

import com.cheatprogram.gui.WorldPanelInputScreen;

import com.cheatprogram.gui.WorldPanelIntroScreen;

import com.cheatprogram.gui.WorldPanelPlacementScreen;

import net.minecraft.client.gui.screens.Screen;

import com.cheatprogram.module.visual.Hud;

import com.mojang.blaze3d.ProjectionType;

import com.mojang.blaze3d.pipeline.RenderTarget;

import com.mojang.blaze3d.pipeline.TextureTarget;

import com.cheatprogram.compat.gl.GlStateManager;

import com.mojang.blaze3d.platform.Lighting;

import com.cheatprogram.compat.gl.RenderSystem;

import com.mojang.blaze3d.vertex.*;

import net.minecraft.client.KeyMapping;

import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import com.cheatprogram.compat.gl.CoreShaders;

import net.minecraft.world.level.ClipContext;

import net.minecraft.world.phys.HitResult;

import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import org.joml.Quaternionf;

import org.joml.Vector3f;

import org.joml.Vector4f;

import org.lwjgl.glfw.GLFW;

import org.lwjgl.opengl.GL11;

import org.lwjgl.opengl.GL30;



/** A stationary world plane. Only a proxy screen owns mouse input while inspecting its surface. */

public final class WorldControlPanel {

    private static final Minecraft MC=Minecraft.getInstance();

    private static TextureTarget target,backdrop;

    private static boolean capturing,active,failed,initializedGui,placing,backdropFrozen;

    private static final WorldPanelPreferences PREFS=WorldPanelPreferences.load();

    private static ClickGuiScreen gui;

    private static Object connection,dimension;

    private static Vec3 center;

    private static Matrix4f inverseViewProjection;

    private static Vec3 camera;

    private static long renderedAt,nextFrame,hoverSince,suppressUntil,lastExitPress;

    private static float cropX,cropY,cropWidth,cropHeight;

    private static int textureW,textureH;

    private static double pointerX=-1000,pointerY=-1000;

    private static double cursorU=.5,cursorV=.5,lastMouseX,lastMouseY;

    private static boolean cursorReady;

    private static int previousRawMouse;

    private static Vec3 placementOrigin,placementForward,placementRight;

    private WorldControlPanel() { }

    public static Hud settings(){return ModuleManager.get(Hud.class);}

    public static boolean enabled(){Hud h=settings();return h!=null&&h.worldScreenEnabled();}

    public static RenderTarget activeTarget(){return capturing?target:null;}

    public static boolean capturing(){return capturing;}

    public static boolean inputActive(){return MC.screen instanceof WorldPanelInputScreen;}

    public static double[] virtualPointer(GuiBase screen){return capturing&&screen==gui?new double[]{pointerX,pointerY}:null;}

    public static ClickGuiScreen page(){return gui;}

    public static int interactionKey(){return PREFS.interactionKey;}

    public static void interactionKey(int key){PREFS.interactionKey=key;PREFS.save();}

    public static void lifecycle(){

        if(MC.getConnection()!=connection||MC.level!=null&&!MC.level.dimension().equals(dimension)){

            connection=MC.getConnection();dimension=MC.level==null?null:MC.level.dimension();

            center=null;hoverSince=0;failed=false;lastExitPress=0;initializedGui=false;inverseViewProjection=null;placing=false;active=false;

            if(inputActive())MC.setScreen(null);

            if(gui!=null){gui.removed();gui=null;}releaseTarget();

        }

        boolean next=enabled()&&MC.player!=null&&MC.level!=null;

        if(!next){

            if(active){

                if(inputActive())MC.setScreen(MC.player==null?null:new ClickGuiScreen());

                if(gui!=null){gui.removed();gui=null;}releaseTarget();

            }

            active=false;center=null;hoverSince=0;placing=false;return;

        }

        if(!active){

            active=true;failed=false;requestPlacement(MC.screen);return;

        }

        if(placing)return;

        if(center==null){requestPlacement(MC.screen);return;}

        // Dedicated editors return to their ClickGui parent. Reattach that page to

        // its world proxy instead of displaying a second, uncloseable flat copy.

        if(MC.screen instanceof ClickGuiScreen current&&current.worldEmbedded()){

			gui=current;releaseInput();initializedGui=false;KeyMapping.releaseAll();MC.setScreen(new WorldPanelInputScreen());

        }

        if(inputActive()&&(!MC.isWindowActive()||MC.getOverlay()!=null||MC.options.hideGui))leave();

    }

    public static void requestPlacement(Screen parent){

        if(MC.player==null||MC.level==null||Config.restartRequired())return;

        Hud h=settings();if(h==null)return;if(!h.isEnabled())h.setEnabled(true);h.worldScreen(true);active=true;failed=false;placing=true;

        if(parent instanceof ClickGuiScreen current){gui=current;gui.setWorldEmbedded(true);initializedGui=false;}

        releaseInput();

        MC.setScreen(PREFS.acknowledged?new WorldPanelPlacementScreen(parent):new WorldPanelIntroScreen(parent));

    }

    public static void acknowledgeAndPreview(Screen parent){PREFS.acknowledged=true;PREFS.save();MC.setScreen(new WorldPanelPlacementScreen(parent));}

    public static void beginPreview(){

        if(MC.player==null)return;

        placing=true;backdropFrozen=false;placementOrigin=MC.player.getEyePosition();

        double yaw=Math.toRadians(MC.player.getYRot());placementForward=new Vec3(-Math.sin(yaw),0,Math.cos(yaw));placementRight=new Vec3(Math.cos(yaw),0,Math.sin(yaw));

        settings().worldScreenYaw(MC.player.getYRot());settings().worldScreenPitch(0);previewOffsets(0,1,3);

    }

    public static void previewOffsets(double right,double up,double forward){

        if(placementOrigin==null)return;

        Vec3 offset=placementRight.scale(right).add(0,up,0).add(placementForward.scale(forward));if(offset.length()>8)offset=offset.normalize().scale(8);

        center=placementOrigin.add(offset);nextFrame=0;

    }

    public static void commitPlacement(){placing=false;backdropFrozen=false;nextFrame=0;ensurePage();MC.setScreen(null);}

    public static void cancelPlacement(Screen parent){placing=false;settings().worldScreen(false);releaseInput();

        if(parent instanceof ClickGuiScreen current)current.setWorldEmbedded(false);MC.setScreen(parent==null?new ClickGuiScreen():parent);

    }

    public static void placeAhead(){

        if(MC.player==null||MC.level==null)return;

        Hud h=settings();if(h==null)return;

        beginPreview();

    }

    public static void enter(){

        if(!enabled()||placing||center==null||failed||MC.getOverlay()!=null)return;

        ensurePage();KeyMapping.releaseAll();hoverSince=0;MC.setScreen(new WorldPanelInputScreen());

    }

    public static void releaseInput(){if(gui!=null)gui.suspendWorldInput();cursorReady=false;KeyMapping.releaseAll();}

    public static void leave(){releaseInput();hoverSince=0;suppressUntil=System.currentTimeMillis()+1200;if(inputActive())MC.setScreen(null);}

    public static void restoreOrdinary(){

        Hud h=settings();if(h!=null)h.worldScreen(false);

        suppressUntil=System.currentTimeMillis()+1200;lastExitPress=0;

        MC.setScreen(new ClickGuiScreen());

    }

    /** Called before ordinary key dispatch, including while the proxy screen is open. */

    public static boolean key(int key,int action){

        if(!enabled()||MC.player==null)return false;

        if(key==com.cheatprogram.core.ClientSettings.INSTANCE.guiKey){

            if(action==GLFW.GLFW_PRESS){long now=System.currentTimeMillis();if(lastExitPress>0&&now-lastExitPress<=settings().worldScreenDoubleMillis())restoreOrdinary();else lastExitPress=now;}

            return true;

        }

        if(key==PREFS.interactionKey&&!placing&&(MC.screen==null||inputActive())){

            if(action==GLFW.GLFW_PRESS){if(inputActive())leave();else enter();}return true;

        }

        return false;

    }

    public static boolean ordinaryOpen(){

        if(!enabled())return false;

        return true;

    }

    private static void ensurePage(){if(gui==null){gui=new ClickGuiScreen();gui.setWorldEmbedded(true);initializedGui=false;}}

    public static double[] pointer(){

        if(MC.getWindow()==null)return null;

        if(inputActive())return new double[]{cursorU,cursorV};

        return hit(MC.mouseHandler.xpos()/Math.max(1,MC.getWindow().getScreenWidth()),MC.mouseHandler.ypos()/Math.max(1,MC.getWindow().getScreenHeight()));

    }

    /** GLFW already applies desktop pointer settings; no Minecraft camera sensitivity is involved. */

    public static void updateCursor(){

        if(!inputActive()||!MC.isWindowActive())return;

        double[] x=new double[1],y=new double[1];GLFW.glfwGetCursorPos(MC.getWindow().handle(),x,y);

        if(cursorReady){cursorU=Math.max(0,Math.min(1,cursorU+(x[0]-lastMouseX)*MC.getWindow().getWidth()/Math.max(1,MC.getWindow().getScreenWidth())/Math.max(1,cropWidth)));

            cursorV=Math.max(0,Math.min(1,cursorV+(y[0]-lastMouseY)*MC.getWindow().getHeight()/Math.max(1,MC.getWindow().getScreenHeight())/Math.max(1,cropHeight)));}

        lastMouseX=x[0];lastMouseY=y[0];cursorReady=true;nextFrame=0;

    }

    public static void beginCursor(){cursorReady=false;long window=MC.getWindow().handle();if(GLFW.glfwRawMouseMotionSupported()){previousRawMouse=GLFW.glfwGetInputMode(window,GLFW.GLFW_RAW_MOUSE_MOTION);GLFW.glfwSetInputMode(window,GLFW.GLFW_RAW_MOUSE_MOTION,GLFW.GLFW_FALSE);}GLFW.glfwSetInputMode(window,GLFW.GLFW_CURSOR,GLFW.GLFW_CURSOR_DISABLED);updateCursor();}

    public static void endCursor(){cursorReady=false;long window=MC.getWindow().handle();GLFW.glfwSetInputMode(window,GLFW.GLFW_CURSOR,GLFW.GLFW_CURSOR_NORMAL);if(GLFW.glfwRawMouseMotionSupported())GLFW.glfwSetInputMode(window,GLFW.GLFW_RAW_MOUSE_MOTION,previousRawMouse);MC.mouseHandler.setIgnoreFirstMove();}

    public static double[] guiPoint(double[] uv){

        if(uv==null)return null;var window=MC.getWindow();

        return new double[]{(cropX+uv[0]*cropWidth)*window.getGuiScaledWidth()/Math.max(1,window.getWidth()),

                (cropY+uv[1]*cropHeight)*window.getGuiScaledHeight()/Math.max(1,window.getHeight())};

    }

    /** Pixel fractions are unprojected through the real level view, not an assumed FOV. */

    private static double[] hit(double sx,double sy){

        if(center==null||camera==null||inverseViewProjection==null||System.nanoTime()-renderedAt>500_000_000L)return null;

        Vector4f near=new Vector4f((float)sx*2-1,1-(float)sy*2,-1,1).mul(inverseViewProjection);

        Vector4f far=new Vector4f((float)sx*2-1,1-(float)sy*2,1,1).mul(inverseViewProjection);

        if(Math.abs(near.w)<1e-8||Math.abs(far.w)<1e-8)return null;

        Vec3 origin=camera.add(near.x/near.w,near.y/near.w,near.z/near.w);

        Vec3 ray=new Vec3(far.x/far.w-near.x/near.w,far.y/far.w-near.y/near.w,far.z/far.w-near.z/near.w).normalize();

        Vec3[] axes=axes();Vec3 normal=axes[0].cross(axes[1]);double denom=normal.dot(ray);

        if(denom>-.0001)return null;

        double distance=center.subtract(origin).dot(normal)/denom;if(distance<=0||distance>128)return null;

        Vec3 point=origin.add(ray.scale(distance)),local=point.subtract(center);

        double w=settings().worldScreenWidth(),h=w*452/720;

        double u=local.dot(axes[0])/w+.5,v=.5-local.dot(axes[1])/h;

        if(u<0||u>1||v<0||v>1)return null;

        if(!settings().worldScreenThroughWalls()&&MC.level!=null&&MC.player!=null){

            var block=MC.level.clip(new ClipContext(camera,point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,MC.player));

            if(block.getType()!=HitResult.Type.MISS&&block.getLocation().distanceToSqr(camera)+.04<point.distanceToSqr(camera))return null;

        }

        return new double[]{u,v};

    }

    private static Vec3[] axes(){

        Hud h=settings();Quaternionf q=new Quaternionf().rotationY((float)Math.toRadians(180-h.worldScreenYaw()))

                .rotateX((float)Math.toRadians(h.worldScreenPitch())).rotateZ((float)Math.toRadians(h.worldScreenRoll()));

        Vector3f right=q.transform(new Vector3f(1,0,0)),up=q.transform(new Vector3f(0,1,0));

        return new Vec3[]{new Vec3(right.x,right.y,right.z),new Vec3(up.x,up.y,up.z)};

    }

    public static void move(double dx,double dy){if(center!=null){Vec3[] a=axes();center=center.add(a[0].scale(dx)).add(a[1].scale(dy));nextFrame=0;}}

    public static void rotate(double dx,double dy){Hud h=settings();h.worldScreenYaw(h.worldScreenYaw()+(float)dx);h.worldScreenPitch(h.worldScreenPitch()+(float)dy);nextFrame=0;}

    public static void resize(double amount){Hud h=settings();h.worldScreenWidth(h.worldScreenWidth()*Math.pow(1.10,amount));nextFrame=0;}

    public static void render(Render3D world){

        if(!enabled()||!active||center==null||failed||RearMirrorRenderer.isRendering()||MC.options.hideGui

                ||MC.getOverlay()!=null||MC.screen!=null&&!inputActive()&&!(MC.screen instanceof WorldPanelPlacementScreen))return;

        camera=world.camera;inverseViewProjection=new Matrix4f(RenderSystem.getProjectionMatrix()).mul(world.viewMatrix()).invert();renderedAt=System.nanoTime();

        ensurePage();

        updateCursor();double[] uv=inputActive()?pointer():null;

        pointerX=uv==null?-1000:cropX+uv[0]*cropWidth;pointerY=uv==null?-1000:cropY+uv[1]*cropHeight;

        long now=System.nanoTime();int fps=settings().worldScreenFrameRate();

        if(target==null||now>=nextFrame){

            capture(world.partialTick);nextFrame=now+1_000_000_000L/Math.max(1,fps);

        }

        if(target==null||failed)return;

        float[] savedColor=RenderSystem.getShaderColor().clone();world.begin();

        try{

            if(!settings().worldScreenThroughWalls())RenderSystem.enableDepthTest();

            RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);RenderSystem.setShaderTexture(0,com.cheatprogram.compat.gl.Framebuffers.color(target));

            float brightness=settings().worldScreenBrightness();RenderSystem.setShaderColor(brightness,brightness,brightness,1);

            Vec3[] axes=axes();double w=settings().worldScreenWidth(),h=w*452/720;

            Vec3 right=axes[0].scale(w/2),up=axes[1].scale(h/2);

            Vec3 tl=center.subtract(right).add(up).subtract(camera),bl=center.subtract(right).subtract(up).subtract(camera),

                    br=center.add(right).subtract(up).subtract(camera),tr=center.add(right).add(up).subtract(camera);

            float u0=cropX/textureW,u1=(cropX+cropWidth)/textureW,v0=1-cropY/textureH,v1=1-(cropY+cropHeight)/textureH;

            int color=Math.round(255*settings().worldScreenOpacity())<<24|0xffffff;

            BufferBuilder buffer=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);

            vertex(buffer,tl,u0,v0,color);vertex(buffer,bl,u0,v1,color);vertex(buffer,br,u1,v1,color);vertex(buffer,tr,u1,v0,color);

            BufferUploader.drawWithShader(buffer.buildOrThrow());

        }finally{world.end();RenderSystem.setShaderColor(savedColor[0],savedColor[1],savedColor[2],savedColor[3]);}

    }

    private static void vertex(BufferBuilder b,Vec3 v,float u,float t,int color){b.addVertex((float)v.x,(float)v.y,(float)v.z).setUv(u,t).setColor(color);}

    private static void capture(float partial){

        RenderTarget main=MC.getMainRenderTarget();RenderStateSnapshot saved=new RenderStateSnapshot();

        try{

            int w=MC.getWindow().getWidth(),h=MC.getWindow().getHeight();

            if(w<1||h<1)return;

            if(target==null){target=new TextureTarget("Cheat Program offscreen", w,h,true);com.cheatprogram.compat.gl.Framebuffers.setFilterMode(target, GL11.GL_LINEAR);}

            else if(target.width!=w||target.height!=h){target.resize(w,h);initializedGui=false;}

            textureW=w;textureH=h;

            float automatic=Math.min(w/1060f,h/760f),scale=Math.max(.55f,automatic*(float)ClientSettings.INSTANCE.uiScale.fraction());

            // Same layout pixels as the ordinary screen; the world quad samples only its panel.

            cropWidth=720*scale;cropHeight=452*scale;cropX=Math.round((w-cropWidth)/2);cropY=Math.round((h-cropHeight)/2);

            RenderSystem.disableScissor();RenderSystem.depthMask(true);com.cheatprogram.compat.gl.Framebuffers.clear(target);

            if(!backdropFrozen||backdrop==null){

                if(backdrop==null)backdrop=new TextureTarget("Cheat Program offscreen", w,h,false);else if(backdrop.width!=w||backdrop.height!=h)backdrop.resize(w,h);

                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,com.cheatprogram.compat.gl.Framebuffers.framebuffer(main));GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,com.cheatprogram.compat.gl.Framebuffers.framebuffer(backdrop));

                GlStateManager._glBlitFrameBuffer(0,0,main.width,main.height,0,0,w,h,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_NEAREST);

                backdropFrozen=!placing;

            }

            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,com.cheatprogram.compat.gl.Framebuffers.framebuffer(backdrop));

            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,com.cheatprogram.compat.gl.Framebuffers.framebuffer(target));

            GlStateManager._glBlitFrameBuffer(0,0,backdrop.width,backdrop.height,0,0,w,h,GL11.GL_COLOR_BUFFER_BIT,GL11.GL_LINEAR);

            capturing=true;com.cheatprogram.compat.gl.Framebuffers.bindWrite(target, true);

            double gs=MC.getWindow().getGuiScale();

            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0,(float)(w/gs),(float)(h/gs),0,1000,21000),ProjectionType.ORTHOGRAPHIC);

            RenderSystem.getModelViewStack().translation(0,0,-11000);MC.gameRenderer.getLighting().setupFor(Lighting.Entry.ITEMS_3D);

            if(!initializedGui){gui.init(MC.getWindow().getGuiScaledWidth(),MC.getWindow().getGuiScaledHeight());initializedGui=true;}

            GuiGraphicsExtractor graphics=com.cheatprogram.compat.GuiBridge.create(MC, "world-control-panel");

            gui.render(graphics,0,0,partial);com.cheatprogram.compat.GuiBridge.flush(graphics);

        }catch(Exception failure){

            failed=true;CheatProgram.LOGGER.warn("World control panel capture failed; ordinary panel restored",failure);

            MC.execute(WorldControlPanel::restoreOrdinary);

        }finally{capturing=false;if(R2D.isActive())R2D.end();saved.restore();}

    }

    public static String help(){return Lang.t("ui.worldpanel.controls",com.cheatprogram.util.KeyNames.name(com.cheatprogram.core.ClientSettings.INSTANCE.guiKey));}

    private static void releaseTarget(){if(target!=null||backdrop!=null){RenderStateSnapshot saved=new RenderStateSnapshot();try{if(target!=null){target.destroyBuffers();target=null;}if(backdrop!=null){backdrop.destroyBuffers();backdrop=null;}backdropFrozen=false;}finally{saved.restore();}}}

    public static void shutdown(){if(gui!=null){gui.removed();gui=null;}releaseTarget();active=false;capturing=false;center=null;placing=false;}

}

