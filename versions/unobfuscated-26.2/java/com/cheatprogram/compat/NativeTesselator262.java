package com.cheatprogram.compat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.*;
/** Own CPU scratch buffer for the existing immediate custom meshes. */
public final class NativeTesselator262 implements AutoCloseable {
    private static final NativeTesselator262 INSTANCE=new NativeTesselator262();
    private final ByteBufferBuilder memory=new ByteBufferBuilder(786432);
    private NativeTesselator262() { }
    public static NativeTesselator262 getInstance(){return INSTANCE;}
    public BufferBuilder begin(PrimitiveTopology topology,VertexFormat format){
        com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();
        memory.clear();return new BufferBuilder(memory,topology,format);
    }
    @Override public void close(){memory.close();}
}
