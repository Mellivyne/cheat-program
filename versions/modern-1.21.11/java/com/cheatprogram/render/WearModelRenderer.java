package com.cheatprogram.render;

import com.cheatprogram.compat.gl.VertexBuffer;

import com.cheatprogram.module.visual.ModelWear;
import com.cheatprogram.util.WearModelData;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import com.cheatprogram.compat.gl.CoreShaders;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import java.io.*;
import java.util.*;

/** Static triangles upload once; changing world/preview transforms does not rebuild the mesh. */
public final class WearModelRenderer {
    private record Mesh(VertexBuffer buffer,int texture) { }
    private static WearModelData.Model current,broken;
    private static final List<Identifier> textures=new ArrayList<>();
    private static final List<Mesh> meshes=new ArrayList<>();
    private static long revision;
    private WearModelRenderer() { }
    public static boolean failed(WearModelData.Model model){return model==broken;}
    public static void release(){for(var mesh:meshes)mesh.buffer.close();meshes.clear();for(var id:textures)Minecraft.getInstance().getTextureManager().release(id);textures.clear();current=null;broken=null;}
    private static void upload(WearModelData.Model model)throws IOException{
        if(current==model)return;release();
        try{
            for(byte[] png:model.textures()){
                NativeImage image=NativeImage.read(new ByteArrayInputStream(png));DynamicTexture texture=new DynamicTexture(() -> "Cheat Program texture", image);
                Identifier id=Identifier.fromNamespaceAndPath("cheatprogram","modelwear/"+(revision++));
                try{com.cheatprogram.compat.gl.RenderSystem.setTextureFilter(texture, true, false);Minecraft.getInstance().getTextureManager().register(id,texture);textures.add(id);}
                catch(RuntimeException e){texture.close();throw e;}
            }
            for(var part:model.parts()){
                boolean textured=part.texture()>=0;BufferBuilder builder=Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,textured?DefaultVertexFormat.POSITION_TEX_COLOR:DefaultVertexFormat.POSITION_COLOR);
                float[] v=part.vertices();
                for(int at=0;at<v.length;at+=27){
                    float ax=v[at+9]-v[at],ay=v[at+10]-v[at+1],az=v[at+11]-v[at+2],bx=v[at+18]-v[at],by=v[at+19]-v[at+1],bz=v[at+20]-v[at+2];
                    float nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx,length=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
                    float light=length<.00001f?1:.58f+.42f*Math.abs((ny*.8f+nx*.35f+nz*.45f)/length);
                    for(int vertex=0;vertex<3;vertex++){int i=at+vertex*9;VertexConsumer out=builder.addVertex(v[i],v[i+1],v[i+2]);if(textured)out.setUv(v[i+3],v[i+4]);out.setColor(v[i+5]*light,v[i+6]*light,v[i+7]*light,v[i+8]);}
                }
                MeshData data=builder.build();if(data==null)continue;VertexBuffer buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);
                try{buffer.bind();buffer.upload(data);meshes.add(new Mesh(buffer,part.texture()));}
                catch(RuntimeException e){buffer.close();throw e;}finally{VertexBuffer.unbind();}
            }
            current=model;
        }catch(IOException|RuntimeException e){release();broken=model;throw e;}
    }
    public static void world(Render3D renderer,WearModelData.Model model,Vec3 feet,ModelWear module,float bodyYaw){
        RenderStateSnapshot saved=new RenderStateSnapshot();
        try{RenderSystem.getModelViewStack().set(renderer.viewMatrix());RenderSystem.enableDepthTest();RenderSystem.depthMask(true);
            draw(new Matrix4f().translation((float)(feet.x-renderer.camera.x),(float)(feet.y-renderer.camera.y),(float)(feet.z-renderer.camera.z)).mul(module.transform(bodyYaw)),model,(float)module.opacity.fraction());
        }finally{saved.restore();}
    }
    public static void draw(Matrix4f pose,WearModelData.Model model,float opacity){
        if(model==broken)return;
        try{upload(model);}catch(IOException|RuntimeException unavailable){return;}
        RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableCull();RenderSystem.setShaderColor(1,1,1,Math.max(0,Math.min(1,opacity)));
        Matrix4f modelView=new Matrix4f(RenderSystem.getModelViewStack()).mul(pose);
        try{for(Mesh mesh:meshes){boolean textured=mesh.texture>=0;RenderSystem.setShader(textured?CoreShaders.POSITION_TEX_COLOR:CoreShaders.POSITION_COLOR);if(textured)RenderSystem.setShaderTexture(0,textures.get(mesh.texture));
            try{mesh.buffer.bind();mesh.buffer.drawWithShader(modelView,RenderSystem.getProjectionMatrix(),RenderSystem.getShader());}finally{VertexBuffer.unbind();}
        }}finally{RenderSystem.setShaderColor(1,1,1,1);}
    }
}
