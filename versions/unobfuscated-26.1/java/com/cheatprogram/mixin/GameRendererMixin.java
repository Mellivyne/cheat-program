package com.cheatprogram.mixin;
import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.visual.NoHurtCam;
import com.cheatprogram.render.Render3D;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Unique private DeltaTracker cheatprogram$guiDelta;
    @Unique private boolean cheatprogram$guiLevel,cheatprogram$guiResources,cheatprogram$guiPending;
    @Shadow private void extractGui(DeltaTracker delta,boolean shouldRenderLevel,boolean resourcesLoaded){throw new AssertionError();}

    // 26.1 extracts GUI before render() clears the scene. Our glass mixes native
    // submission barriers with immediate GL, so execute its genuine extraction
    // at the native GUI render barrier, after world rendering and post effects.
    @WrapOperation(method="extract",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/GameRenderer;extractGui(Lnet/minecraft/client/DeltaTracker;ZZ)V"))
    private void cheatprogram$deferGui(GameRenderer self,DeltaTracker delta,boolean level,boolean resources,Operation<Void> original){
        cheatprogram$guiDelta=delta;cheatprogram$guiLevel=level;cheatprogram$guiResources=resources;cheatprogram$guiPending=true;
    }
    @Inject(method="render",at=@At("HEAD"))
    private void cheatprogram$pointerLifecycle(DeltaTracker delta,boolean renderLevel,CallbackInfo ci){
        com.cheatprogram.hud.HudRenderer.beginFrame();com.cheatprogram.render.AppCastPointer.beforeFrame();
    }
    @WrapOperation(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
    private void cheatprogram$renderGui(GuiRenderer renderer,GpuBufferSlice fog,Operation<Void> original){
        if(cheatprogram$guiPending){
            cheatprogram$guiPending=false;
            extractGui(cheatprogram$guiDelta,cheatprogram$guiLevel,cheatprogram$guiResources);
            cheatprogram$guiDelta=null;
        }
        original.call(renderer,fog);
    }
    @WrapOperation(method="extractGui",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/Screen;extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
    private void cheatprogram$fullscreenPointer(Screen screen,GuiGraphicsExtractor graphics,int x,int y,float partial,Operation<Void> original){
        original.call(screen,graphics,x,y,partial);
        com.cheatprogram.hud.HudRenderer.afterScreen(graphics,screen);com.cheatprogram.render.AppCastPointer.afterScreen(graphics,screen);
    }
    @Inject(method="renderLevel",at=@At("TAIL"))
    private void cheatprogram$worldColors(DeltaTracker delta,CallbackInfo ci){
        var colors=com.cheatprogram.module.visual.WorldColors.active();if(colors!=null)colors.draw();
    }
    @Inject(method="bobHurt",at=@At("HEAD"),cancellable=true)
    private void cheatprogram$noHurtCam(CameraRenderState camera,PoseStack pose,CallbackInfo ci){if(NoHurtCam.active())ci.cancel();}

    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;ZLnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;)V"))
    private void cheatprogram$render3d(LevelRenderer renderer,GraphicsResourceAllocator allocator,DeltaTracker delta,boolean outline,
            CameraRenderState state,Matrix4fc view,GpuBufferSlice fog,Vector4f fogColor,boolean sky,ChunkSectionsToRender chunks,Operation<Void> original){
        // Capture the actual world projection, including bob/nausea effects,
        // before nested native feature passes can temporarily change it.
        Matrix4f projection=new Matrix4f(com.cheatprogram.compat.gl.RenderSystem.getProjectionMatrix());
        original.call(renderer,allocator,delta,outline,state,view,fog,fogColor,sky,chunks);
        try{
            ModuleManager.onRender3D(new Render3D(Minecraft.getInstance().gameRenderer.getMainCamera(),new Matrix4f(view),projection,delta.getGameTimeDeltaPartialTick(true)));
        }catch(Throwable t){CheatProgram.LOGGER.error("World overlay rendering failed",t);}
    }
}
