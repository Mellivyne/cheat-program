package com.cheatprogram.compat;

import com.cheatprogram.compat.gl.RenderSystem;
import com.cheatprogram.mixin.GameRendererGuiAccess;
import com.cheatprogram.mixin.GuiGraphicsStateAccess;
import com.cheatprogram.render.RenderStateSnapshot;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.render.pip.*;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Submission barriers for a deferred native GUI mixed with immediate custom glass.
 * Every barrier really renders and consumes its queue before the next immediate primitive.
 * The existing WorldPanelTargetMixin routes native passes to the offscreen panel target.
 */
public final class GuiBridge {
    private static final Map<String,Context> contexts = new LinkedHashMap<>();
    private static final Map<GuiRenderState,Context> ownedStates = new IdentityHashMap<>();
    private static ByteBufferBuilder specialMemory;
    private static MultiBufferSource.BufferSource specialBuffers;
    private static boolean submitting;

    private GuiBridge() { }

    /** Isolated queues for the two custom offscreen/item panels, reused without per-frame GPU leaks. */
    public static GuiGraphics create(Minecraft minecraft, String owner) {
        RenderSystem.assertOnRenderThread();
        Context context = contexts.computeIfAbsent(owner, key -> new Context(minecraft));
        context.renderer.incrementFrameNumber();
        return new GuiGraphics(minecraft,context.state);
    }

    public static void flush(GuiGraphics graphics) {
        RenderSystem.assertOnRenderThread();
        if(submitting)throw new IllegalStateException("Recursive GUI submission barrier");
        Minecraft minecraft=Minecraft.getInstance();
        GuiRenderState state=((GuiGraphicsStateAccess)(Object)graphics).cheatprogram$guiRenderState();
        GameRendererGuiAccess access=(GameRendererGuiAccess)(Object)minecraft.gameRenderer;
        GuiRenderer renderer;
        Context context=ownedStates.get(state);
        if(context!=null)renderer=context.renderer;
        else if(state==access.cheatprogram$guiRenderState())renderer=access.cheatprogram$guiRenderer();
        else throw new IllegalStateException("GUI queue has no registered submission renderer");
        RenderStateSnapshot saved=new RenderStateSnapshot();
        submitting=true;
        try {
            // GuiRenderer prepares text, item models and PIP; render() consumes and resets
            // the queue, so the later vanilla frame submission cannot draw it twice.
            renderer.render(access.cheatprogram$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
        } finally {
            submitting=false;
            saved.restore();
            setupGuiCoordinates(minecraft);
        }
    }

    private static void setupGuiCoordinates(Minecraft minecraft) {
        float scale=(float)minecraft.getWindow().getGuiScale();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0,minecraft.getWindow().getWidth()/scale,
                minecraft.getWindow().getHeight()/scale,0,1000,21000),ProjectionType.ORTHOGRAPHIC);
        RenderSystem.getModelViewStack().translation(0,0,-11000);
    }

    /** Exact embedding of the new 2D GUI matrix in the custom renderer's 4D transform. */
    public static Matrix4f pose(GuiGraphics graphics) {
        var m=graphics.pose();
        return new Matrix4f().m00(m.m00()).m01(m.m01()).m10(m.m10()).m11(m.m11()).m30(m.m20()).m31(m.m21());
    }

    public static Matrix3x2f affine(Matrix4f m) {
        if(Math.abs(m.m03())>1e-6f||Math.abs(m.m13())>1e-6f||Math.abs(m.m33()-1)>1e-6f)
            throw new IllegalArgumentException("GUI transform must be affine in its XY plane");
        return new Matrix3x2f(m.m00(),m.m01(),m.m10(),m.m11(),m.m30(),m.m31());
    }

    /** Native immediate text preserves shaping, RGB gradients and raw framebuffer clipping. */
    public static void drawSpecial(GuiGraphics graphics,Consumer<MultiBufferSource.BufferSource> draw) {
        flush(graphics);
        RenderStateSnapshot saved=new RenderStateSnapshot();
        var nativeScissor=com.mojang.blaze3d.systems.RenderSystem.getScissorStateForRenderTypeDraws();
        boolean previousScissor=nativeScissor.enabled();
        int previousX=nativeScissor.x(),previousY=nativeScissor.y(),previousW=nativeScissor.width(),previousH=nativeScissor.height();
        GpuBufferSlice previousFog=com.mojang.blaze3d.systems.RenderSystem.getShaderFog();
        try {
            GameRendererGuiAccess access=(GameRendererGuiAccess)(Object)Minecraft.getInstance().gameRenderer;
            com.mojang.blaze3d.systems.RenderSystem.setShaderFog(access.cheatprogram$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
            if(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)){
                int[] box=new int[4];GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,box);
                com.mojang.blaze3d.systems.RenderSystem.enableScissorForRenderTypeDraws(box[0],box[1],box[2],box[3]);
            }else com.mojang.blaze3d.systems.RenderSystem.disableScissorForRenderTypeDraws();
            if(specialMemory==null){specialMemory=new ByteBufferBuilder(262144);specialBuffers=MultiBufferSource.immediate(specialMemory);}
            draw.accept(specialBuffers);
            specialBuffers.endBatch();
        } finally {
            if(previousScissor)com.mojang.blaze3d.systems.RenderSystem.enableScissorForRenderTypeDraws(previousX,previousY,previousW,previousH);
            else com.mojang.blaze3d.systems.RenderSystem.disableScissorForRenderTypeDraws();
            com.mojang.blaze3d.systems.RenderSystem.setShaderFog(previousFog);
            saved.restore();
            setupGuiCoordinates(Minecraft.getInstance());
        }
    }

    public static void drawString(GuiGraphics graphics,Font font,Component text,int x,int y,int color,boolean shadow) {
        Matrix4f matrix=pose(graphics);
        drawSpecial(graphics,buffers->font.drawInBatch(text,x,y,color,shadow,matrix,buffers,Font.DisplayMode.NORMAL,0,15728880));
    }

    public static void shutdown() {
        RenderSystem.assertOnRenderThread();
        for(Context context:contexts.values())context.renderer.close();
        contexts.clear();ownedStates.clear();
        if(specialMemory!=null){specialMemory.close();specialMemory=null;specialBuffers=null;}
    }

    private static final class Context {
        final GuiRenderState state=new GuiRenderState();
        final GuiRenderer renderer;
        Context(Minecraft minecraft){
            var buffers=minecraft.renderBuffers().bufferSource();
            var atlas=minecraft.getAtlasManager();
            renderer=new GuiRenderer(state,buffers,minecraft.gameRenderer.getSubmitNodeStorage(),minecraft.gameRenderer.getFeatureRenderDispatcher(),
                    List.of(new GuiEntityRenderer(buffers,minecraft.getEntityRenderDispatcher()),new GuiSkinRenderer(buffers),
                            new GuiBookModelRenderer(buffers),new GuiBannerResultRenderer(buffers,atlas),
                            new GuiSignRenderer(buffers,atlas),new GuiProfilerChartRenderer(buffers)));
            ownedStates.put(state,this);
        }
    }
}
