package com.cheatprogram.compat.legacy;
import com.mojang.blaze3d.vertex.VertexFormat;
public final class LegacyTesselator {
    private static final LegacyTesselator INSTANCE=new LegacyTesselator();
    public static LegacyTesselator getInstance(){return INSTANCE;}
    public LegacyBufferBuilder begin(VertexFormat.Mode mode,VertexFormat format){return new LegacyBufferBuilder(32768,mode,format);}
}
