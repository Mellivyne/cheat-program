package com.cheatprogram.compat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
/** Runs vanilla feature preparation into the blueprint's persistent geometry builders. */
public final class NativeBlockEntityBaker {
 private static final ThreadLocal<MultiBufferSource> destination=new ThreadLocal<>();
 private static Pipeline pipeline;
 private NativeBlockEntityBaker(){}
 public static MultiBufferSource destination(){return destination.get();}
 public static <T extends BlockEntity,S extends BlockEntityRenderState> void bake(BlockEntityRenderer<T,S> renderer,T entity,PoseStack pose,MultiBufferSource target){
  var state=renderer.createRenderState();renderer.extractRenderState(entity,state,0,Vec3.ZERO,null);state.lightCoords=15728880;
  var camera=new CameraRenderState();camera.initialized=true;
  collect(target,submits->renderer.submit(state,pose,submits,camera));
 }
 public static void bakeModel(BlockModelRenderState state,PoseStack pose,MultiBufferSource target){
  collect(target,submits->state.submit(pose,submits,15728880,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,0));
 }
 private static void collect(MultiBufferSource target,java.util.function.Consumer<SubmitNodeStorage> submit){
  if(pipeline==null)pipeline=new Pipeline();
  var previous=destination.get();destination.set(target);var nodes=new SubmitNodeStorage();
  try{submit.accept(nodes);try(var prepared=pipeline.features.prepareFrame(nodes)){} }
  finally{if(previous==null)destination.remove();else destination.set(previous);pipeline.buffers.endFrame();}
 }
 private static final class Pipeline {
  final RenderBuffers buffers=new RenderBuffers(1);
  final FeatureRenderDispatcher features;
  Pipeline(){var mc=Minecraft.getInstance();features=new FeatureRenderDispatcher(buffers,mc.getModelManager(),mc.getAtlasManager(),mc.font,mc.gameRenderer.gameRenderState());}
 }
}
