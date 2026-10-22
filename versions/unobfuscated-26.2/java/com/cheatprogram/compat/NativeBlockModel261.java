package com.cheatprogram.compat;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import com.cheatprogram.compat.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.*;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Native 26.1 block geometry/material extraction into persistent preview buffers. */
public final class NativeBlockModel261 {
    public static final NativeBlockModel261 INSTANCE = new NativeBlockModel261();
    private final Minecraft mc = Minecraft.getInstance();
    private final ModelBlockRenderer batched = new ModelBlockRenderer(true,true,mc.getBlockColors());
    private NativeBlockModel261() { }
    public void renderBatched(BlockState state,BlockPos position,BlockAndTintGetter level,PoseStack pose,MultiBufferSource source) {
        var model = mc.getModelManager().getBlockStateModelSet().get(state);
        batched.tesselateBlock((x,y,z,quad,instance)->{
            var consumer=source.getBuffer(quad.materialInfo().itemRenderType());
            pose.pushPose();
            try { pose.translate(x,y,z); consumer.putBakedQuad(pose.last(),quad,instance); }
            finally { pose.popPose(); }
        },0,0,0,level,position,state,model,state.getSeed(position));
    }
    public void renderSingleBlock(BlockState state,PoseStack pose,MultiBufferSource source,int light,int overlay) {
        var modelState=new BlockModelRenderState();
        new BlockModelResolver(mc.getModelManager()).update(modelState,state,BlockDisplayContext.create());
        NativeBlockEntityBaker.bakeModel(modelState,pose,source);
    }
    public void renderLiquid(BlockPos position,BlockAndTintGetter level,VertexConsumer consumer,BlockState state,FluidState fluid) {
        new FluidRenderer(mc.getModelManager().getFluidStateModelSet()).tesselate(level,position,layer->consumer,state,fluid);
    }
}
