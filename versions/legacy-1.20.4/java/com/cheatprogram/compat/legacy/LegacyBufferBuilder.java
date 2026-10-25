package com.cheatprogram.compat.legacy;
import com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
public final class LegacyBufferBuilder extends com.mojang.blaze3d.vertex.BufferBuilder implements LegacyVertexConsumer {
    private boolean pending;
    public LegacyBufferBuilder(int size,VertexFormat.Mode mode,VertexFormat format){super(size);begin(mode,format);}
    public LegacyBufferBuilder(LegacyByteBufferBuilder memory,VertexFormat.Mode mode,VertexFormat format){this(memory.capacity,mode,format);}
    @Override public LegacyBufferBuilder addVertex(float x,float y,float z){if(pending)endVertex();super.vertex(x,y,z);pending=true;return this;}
    @Override public LegacyBufferBuilder addVertex(org.joml.Matrix4f pose,float x,float y,float z){var p=pose.transformPosition(new org.joml.Vector3f(x,y,z));return addVertex(p.x,p.y,p.z);}
    @Override public LegacyBufferBuilder setColor(int r,int g,int b,int a){super.color(r,g,b,a);return this;}
    @Override public LegacyBufferBuilder setColor(int c){return setColor(c>>16&255,c>>8&255,c&255,c>>>24);}
    @Override public LegacyBufferBuilder setUv(float u,float v){super.uv(u,v);return this;}
    @Override public LegacyBufferBuilder setUv1(int u,int v){super.overlayCoords(u,v);return this;}
    @Override public LegacyBufferBuilder setUv2(int u,int v){super.uv2(u,v);return this;}
    @Override public LegacyBufferBuilder setNormal(float x,float y,float z){super.normal(x,y,z);return this;}
    @Override public void endVertex(){super.endVertex();pending=false;}
    @Override public LegacyBufferBuilder vertex(double x,double y,double z){super.vertex(x,y,z);return this;}
    @Override public LegacyBufferBuilder color(int r,int g,int b,int a){super.color(r,g,b,a);return this;}
    @Override public LegacyBufferBuilder uv(float u,float v){super.uv(u,v);return this;}
    @Override public LegacyBufferBuilder overlayCoords(int u,int v){super.overlayCoords(u,v);return this;}
    @Override public LegacyBufferBuilder uv2(int u,int v){super.uv2(u,v);return this;}
    @Override public LegacyBufferBuilder normal(float x,float y,float z){super.normal(x,y,z);return this;}
    public RenderedBuffer buildOrThrow(){if(pending)endVertex();return end();}
    public RenderedBuffer build(){if(pending)endVertex();return endOrDiscardIfEmpty();}
}
