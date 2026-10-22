package com.cheatprogram.compat;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.rendertype.RenderType;
import java.util.*;
/** CPU geometry collectors feeding the actual native 26.2 material pipeline. */
@FunctionalInterface
public interface MultiBufferSource {
    VertexConsumer getBuffer(RenderType type);
    static BufferSource immediate(ByteBufferBuilder scratch){return new BufferSource();}
    final class Entry implements AutoCloseable {
        final ByteBufferBuilder memory=new ByteBufferBuilder(16384);
        final BufferBuilder builder;
        Entry(RenderType type){builder=new BufferBuilder(memory,type.primitiveTopology(),type.format());}
        public void close(){memory.close();}
    }
    class BufferSource implements MultiBufferSource {
        private final Map<RenderType,Entry> entries=new LinkedHashMap<>();
        public BufferSource(){}
        public VertexConsumer getBuffer(RenderType type){return entries.computeIfAbsent(type,Entry::new).builder;}
        public void endBatch(){for(RenderType type:new ArrayList<>(entries.keySet()))endBatch(type);}
        public void endBatch(RenderType type){
            Entry entry=entries.remove(type);if(entry==null)return;
            try(entry){var mesh=entry.builder.build();if(mesh!=null)NativeCachedMesh.immediate(type,mesh,
                new org.joml.Matrix4f(com.cheatprogram.compat.gl.RenderSystem.getModelViewStack()),
                com.cheatprogram.compat.gl.RenderSystem.getProjectionMatrix(),1,false);}
        }
    }
}
