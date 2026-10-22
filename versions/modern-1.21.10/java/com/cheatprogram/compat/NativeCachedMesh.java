package com.cheatprogram.compat;

import com.cheatprogram.compat.gl.RenderSystem;
import com.cheatprogram.mixin.RenderPipelineAccess;
import com.cheatprogram.render.RenderStateSnapshot;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/** Persistent native GPU geometry retaining its material pipeline and actual atlas samplers. */
public final class NativeCachedMesh implements AutoCloseable {
    private GpuBuffer vertices,indices;
    private MeshData.DrawState state;
    private static final Map<RenderPipeline,RenderPipeline[]> variants=new IdentityHashMap<>();
    private static int serial;
    private static com.mojang.blaze3d.systems.GpuDevice device(){return com.mojang.blaze3d.systems.RenderSystem.getDevice();}
    public void upload(MeshData mesh){
        close();
        try(mesh){
            state=mesh.drawState();vertices=device().createBuffer(()->"Cheat Program cached native vertices",GpuBuffer.USAGE_VERTEX,mesh.vertexBuffer());
            if(mesh.indexBuffer()!=null)indices=device().createBuffer(()->"Cheat Program cached native indices",GpuBuffer.USAGE_INDEX,mesh.indexBuffer());
        }catch(RuntimeException failure){close();throw failure;}
    }
    public void draw(RenderType type,Matrix4f model,Matrix4f projection,RenderTarget target,float alpha,boolean writeDepth,boolean throughWalls){
        if(vertices==null||state==null)return;
        if(target==null)target=Minecraft.getInstance().getMainRenderTarget();
        RenderStateSnapshot saved=new RenderStateSnapshot();
        GpuTextureView[] textures=new GpuTextureView[12];
        for(int i=0;i<12;i++)textures[i]=com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(i);
        try{
            type.setupRenderState();
            if(!RenderSystem.getProjectionMatrix().equals(projection))RenderSystem.setProjectionMatrix(projection,RenderSystem.getProjectionType());
            var transform=com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms().writeTransform(model,new Vector4f(1,1,1,alpha),new Vector3f(),RenderSystem.getTextureMatrix(),RenderSystem.getShaderLineWidth());
            GpuBuffer indexBuffer=indices;VertexFormat.IndexType indexType=state.indexType();
            if(indexBuffer==null){var sequential=com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(state.mode());indexBuffer=sequential.getBuffer(state.indexCount());indexType=sequential.type();}
            boolean blend=alpha<.999f||!writeDepth;
            var pipeline=variant(type.pipeline(),writeDepth,throughWalls,blend);
            try(var pass=device().createCommandEncoder().createRenderPass(()->"Cheat Program cached native material",target.getColorTextureView(),OptionalInt.empty(),target.getDepthTextureView(),OptionalDouble.empty())){
                pass.setPipeline(pipeline);com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass);pass.setUniform("DynamicTransforms",transform);pass.setVertexBuffer(0,vertices);pass.setIndexBuffer(indexBuffer,indexType);
                var scissor=com.mojang.blaze3d.systems.RenderSystem.getScissorStateForRenderTypeDraws();if(scissor.enabled())pass.enableScissor(scissor.x(),scissor.y(),scissor.width(),scissor.height());
                for(int i=0;i<12;i++){var image=com.mojang.blaze3d.systems.RenderSystem.getShaderTexture(i);if(image!=null)pass.bindSampler("Sampler"+i,image);}
                pass.drawIndexed(0,0,state.indexCount(),1);
            }
        }finally{
            type.clearRenderState();
            for(int i=0;i<12;i++)com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(i,textures[i]);
            saved.restore();
        }
    }
    public static void immediate(RenderType type,MeshData mesh,Matrix4f model,Matrix4f projection,float alpha,boolean throughWalls){
        try(var cached=new NativeCachedMesh()){cached.upload(mesh);cached.draw(type,model,projection,null,alpha,false,throughWalls);}
    }
    private static RenderPipeline variant(RenderPipeline source,boolean writeDepth,boolean throughWalls,boolean blend){
        int key=(writeDepth?1:0)|(throughWalls?2:0)|(blend?4:0);var values=variants.computeIfAbsent(source,ignored->new RenderPipeline[8]);
        if(values[key]==null)values[key]=new OverlayPipeline(source,writeDepth,throughWalls,blend);return values[key];
    }
    private static final class OverlayPipeline extends RenderPipeline {
        OverlayPipeline(RenderPipeline p,boolean writeDepth,boolean throughWalls,boolean blend){
            super(ResourceLocation.fromNamespaceAndPath("cheatprogram","native_overlay/"+(++serial)),
                  ((RenderPipelineAccess)p).cheatprogram$vertexShader(),((RenderPipelineAccess)p).cheatprogram$fragmentShader(),((RenderPipelineAccess)p).cheatprogram$defines(),
                  p.getSamplers(),p.getUniforms(),blend?Optional.of(p.getBlendFunction().orElse(BlendFunction.TRANSLUCENT)):p.getBlendFunction(),
                  throughWalls?DepthTestFunction.NO_DEPTH_TEST:p.getDepthTestFunction(),p.getPolygonMode(),p.isCull(),p.isWriteColor(),p.isWriteAlpha(),writeDepth&&p.isWriteDepth(),p.getColorLogic(),p.getVertexFormat(),p.getVertexFormatMode(),
                  ((RenderPipelineAccess)p).cheatprogram$biasScale(),((RenderPipelineAccess)p).cheatprogram$biasConstant(),1000000+serial);
        }
    }
    public static void shutdown(){variants.clear();}
    @Override public void close(){if(vertices!=null){vertices.close();vertices=null;}if(indices!=null){indices.close();indices=null;}state=null;}
}
