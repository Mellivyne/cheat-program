package com.cheatprogram.compat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.cheatprogram.render.RenderStateSnapshot;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.*;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
/** Isolated real avatar and particle submission, including actual provider atlas/layer/shader. */
public final class NativePreviewRenderer implements AutoCloseable {
 private static final ThreadLocal<RenderTarget> target=new ThreadLocal<>();
 public static RenderTarget target(){return target.get();}
 private final Minecraft mc=Minecraft.getInstance();
 private final RenderBuffers buffers=new RenderBuffers(1);
 private final FeatureRenderDispatcher features=new FeatureRenderDispatcher(buffers,mc.getModelManager(),mc.getAtlasManager(),mc.font,mc.gameRenderer.gameRenderState());
 private SubmitNodeStorage submits=new SubmitNodeStorage();
 private final QuadParticleRenderState quads=new QuadParticleRenderState();
 public void avatar(AvatarRenderer renderer,AvatarRenderState state,PoseStack pose,int light){state.lightCoords=light;var camera=new CameraRenderState();camera.initialized=true;renderer.submit(state,pose,submits,camera);}
 public void particle(Particle particle,Camera camera,float partial){
  if(particle.getGroup()==ParticleRenderType.NO_RENDER)return;
  if(particle instanceof SingleQuadParticle quad){quad.extract(quads,camera,partial);}
  else if(particle.getGroup()==ParticleRenderType.ITEM_PICKUP){var group=new ItemPickupParticleGroup(mc.particleEngine);group.add(particle);group.extractRenderState(null,camera,partial).submit(submits,cameraState(camera));}
  else if(particle.getGroup()==ParticleRenderType.ELDER_GUARDIANS){var group=new ElderGuardianParticleGroup(mc.particleEngine);group.add(particle);group.extractRenderState(null,camera,partial).submit(submits,cameraState(camera));}
  else throw new IllegalArgumentException("Unsupported native particle group: "+particle.getGroup());
 }
 private static CameraRenderState cameraState(Camera camera){var state=new CameraRenderState();state.initialized=true;state.pos=camera.position();state.orientation.set(camera.rotation());return state;}
 public void draw(RenderTarget output){
  RenderSystem.assertOnRenderThread();var saved=new RenderStateSnapshot();var clip=RenderSystem.getScissorStateForRenderTypeDraws();
  boolean clipped=clip.enabled();int x=clip.x(),y=clip.y(),w=clip.width(),h=clip.height();
  var color=RenderSystem.outputColorTextureOverride;var depth=RenderSystem.outputDepthTextureOverride;var previous=target.get();
  RenderSystem.outputColorTextureOverride=output.getColorTextureView();RenderSystem.outputDepthTextureOverride=output.getDepthTextureView();target.set(output);
  try{RenderSystem.disableScissorForRenderTypeDraws();com.cheatprogram.compat.gl.RenderSystem.invalidateNativeState();
   if(!quads.isEmpty())quads.submit(submits,new CameraRenderState());features.renderAllFeatures(submits);
  }finally{RenderSystem.outputColorTextureOverride=color;RenderSystem.outputDepthTextureOverride=depth;
   if(previous==null)target.remove();else target.set(previous);
   if(clipped)RenderSystem.enableScissorForRenderTypeDraws(x,y,w,h);else RenderSystem.disableScissorForRenderTypeDraws();
   quads.clear();submits=new SubmitNodeStorage();buffers.endFrame();saved.restore();
  }
 }
 public void close(){features.close();buffers.close();}
}
