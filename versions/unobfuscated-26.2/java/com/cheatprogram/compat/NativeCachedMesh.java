package com.cheatprogram.compat;
import com.cheatprogram.compat.gl.RenderSystem;
import com.cheatprogram.render.RenderStateSnapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import java.util.*;
/** GPU-resident geometry with genuine prepared native materials and dynamic transforms. */
public final class NativeCachedMesh implements AutoCloseable {
 private GpuBuffer vertices,indices;
 private MeshData.DrawState state;
 private byte[] originalVertices;
 private float lastAlpha=1;
 private static final Map<RenderPipeline,RenderPipeline[]> variants=new IdentityHashMap<>();
 private static int serial;
 private static com.mojang.blaze3d.systems.GpuDevice device(){return com.mojang.blaze3d.systems.RenderSystem.getDevice();}
 public void upload(MeshData mesh){close();try(mesh){
  state=mesh.drawState();var data=mesh.vertexBuffer();originalVertices=new byte[data.remaining()];data.duplicate().get(originalVertices);
  vertices=device().createBuffer(()->"Cheat Program cached native vertices",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_COPY_DST,data);
  if(mesh.indexBuffer()!=null)indices=device().createBuffer(()->"Cheat Program cached native indices",GpuBuffer.USAGE_INDEX,mesh.indexBuffer());
 }catch(RuntimeException failure){close();throw failure;}}
 private void alpha(float alpha){
  if(lastAlpha==alpha)return;lastAlpha=alpha;var element=state.format().getElement("Color");
  if(element==null||element.format()!=com.mojang.blaze3d.GpuFormat.RGBA8_UNORM)return;
  var bytes=org.lwjgl.system.MemoryUtil.memAlloc(originalVertices.length);
  try{bytes.put(originalVertices).flip();for(int i=0;i<state.vertexCount();i++){
   int offset=i*state.format().getVertexSize()+element.offset()+3;
   bytes.put(offset,(byte)Math.round((originalVertices[offset]&255)*Math.clamp(alpha,0,1)));
  }device().createCommandEncoder().writeToBuffer(vertices.slice(),bytes);}finally{org.lwjgl.system.MemoryUtil.memFree(bytes);}
 }
 public void draw(RenderType type,Matrix4f model,Matrix4f projection,RenderTarget target,float alpha,boolean writeDepth,boolean throughWalls){
  if(vertices==null||state==null)return;if(target==null)target=Minecraft.getInstance().gameRenderer.mainRenderTarget();
  RenderStateSnapshot saved=new RenderStateSnapshot();var stack=com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();stack.pushMatrix();
  try{
   alpha(alpha);stack.set(model);if(!RenderSystem.getProjectionMatrix().equals(projection))RenderSystem.setProjectionMatrix(projection,RenderSystem.getProjectionType());
   var prepared=type.prepare();var pipeline=variant(prepared.pipeline(),writeDepth,throughWalls,alpha<.999f||!writeDepth);
   GpuBuffer indexBuffer=indices;var indexType=state.indexType();
   if(indexBuffer==null){var seq=com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(state.primitiveTopology());indexBuffer=seq.getBuffer(state.indexCount());indexType=seq.type();}
   RenderSystem.invalidateNativeState();
   try(var pass=device().createCommandEncoder().createRenderPass(()->"Cheat Program cached native material",target.getColorTextureView(),Optional.empty(),target.getDepthTextureView(),OptionalDouble.empty())){
    pass.setPipeline(pipeline);com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass);
    pass.setUniform("DynamicTransforms",prepared.dynamicTransforms());pass.setVertexBuffer(0,vertices.slice());pass.setIndexBuffer(indexBuffer,indexType);
    var clip=prepared.scissorState();if(clip.enabled())pass.enableScissor(clip.x(),clip.y(),clip.width(),clip.height());
    for(var texture:prepared.textures())pass.bindTexture(texture.name(),texture.textureView(),texture.sampler());
    pass.drawIndexed(state.indexCount(),1,0,0,0);
   }
  }finally{stack.popMatrix();saved.restore();}
 }
 public static void immediate(RenderType type,MeshData mesh,Matrix4f model,Matrix4f projection,float alpha,boolean throughWalls){
  try(var cache=new NativeCachedMesh()){cache.upload(mesh);cache.draw(type,model,projection,null,alpha,false,throughWalls);}
 }
 private static RenderPipeline variant(RenderPipeline p,boolean writeDepth,boolean walls,boolean blend){
  int key=(writeDepth?1:0)|(walls?2:0)|(blend?4:0);var all=variants.computeIfAbsent(p,k->new RenderPipeline[8]);
  if(all[key]==null)all[key]=new OverlayPipeline(p,writeDepth,walls,blend);return all[key];
 }
 private static final class OverlayPipeline extends RenderPipeline {
  OverlayPipeline(RenderPipeline p,boolean depth,boolean walls,boolean blend){
   super(Identifier.fromNamespaceAndPath("cheatprogram","native_overlay/"+(++serial)),p.getVertexShader(),p.getFragmentShader(),p.getShaderDefines(),p.getBindGroupLayouts(),colors(p,blend),depth(p,depth,walls),p.getPolygonMode(),p.isCull(),p.getVertexFormatBindings(),p.getPrimitiveTopology(),1000000+serial);
  }
  private static ColorTargetState[] colors(RenderPipeline source,boolean blend){
   var original=source.getColorTargetStates();var result=original.clone();for(int i=0;i<result.length;i++){
    var color=original[i];if(color!=null)result[i]=new ColorTargetState(blend?Optional.of(color.blendFunction().orElse(BlendFunction.TRANSLUCENT)):color.blendFunction(),color.format(),color.writeMask());
   }return result;
  }
  private static DepthStencilState depth(RenderPipeline p,boolean write,boolean walls){var d=p.getDepthStencilState();return d==null?null:new DepthStencilState(walls?com.mojang.blaze3d.platform.CompareOp.ALWAYS_PASS:d.depthTest(),write&&d.writeDepth(),d.depthBiasScaleFactor(),d.depthBiasConstant());}
 }
 public static void shutdown(){variants.clear();}
 public void close(){if(vertices!=null){vertices.close();vertices=null;}if(indices!=null){indices.close();indices=null;}state=null;originalVertices=null;lastAlpha=1;}
}
