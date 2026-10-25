package com.cheatprogram.compat.legacy;
import org.joml.Matrix4f;
public interface LegacyVertexConsumer extends com.mojang.blaze3d.vertex.VertexConsumer {
    static LegacyVertexConsumer wrap(com.mojang.blaze3d.vertex.VertexConsumer out){
        if(out instanceof LegacyVertexConsumer compatible)return compatible;
        return new LegacyVertexConsumer(){
            public LegacyVertexConsumer addVertex(float x,float y,float z){out.vertex(x,y,z);return this;}
            public LegacyVertexConsumer setColor(int r,int g,int b,int a){out.color(r,g,b,a);return this;}
            public LegacyVertexConsumer setUv(float u,float v){out.uv(u,v);return this;}
            public LegacyVertexConsumer setUv1(int u,int v){out.overlayCoords(u,v);return this;}
            public LegacyVertexConsumer setUv2(int u,int v){out.uv2(u,v);return this;}
            public LegacyVertexConsumer setNormal(float x,float y,float z){out.normal(x,y,z);return this;}
            public void endVertex(){out.endVertex();}
        };
    }
    LegacyVertexConsumer addVertex(float x,float y,float z);
    default LegacyVertexConsumer addVertex(Matrix4f matrix,float x,float y,float z){var p=matrix.transformPosition(new org.joml.Vector3f(x,y,z));return addVertex(p.x,p.y,p.z);}
    LegacyVertexConsumer setColor(int r,int g,int b,int a);
    default LegacyVertexConsumer setColor(int color){return setColor(color>>16&255,color>>8&255,color&255,color>>>24);}
    default LegacyVertexConsumer setColor(float r,float g,float b,float a){return setColor((int)(r*255),(int)(g*255),(int)(b*255),(int)(a*255));}
    LegacyVertexConsumer setUv(float u,float v);
    LegacyVertexConsumer setUv1(int u,int v);
    LegacyVertexConsumer setUv2(int u,int v);
    LegacyVertexConsumer setNormal(float x,float y,float z);
    @Override default LegacyVertexConsumer vertex(double x,double y,double z){return addVertex((float)x,(float)y,(float)z);}
    @Override default LegacyVertexConsumer color(int r,int g,int b,int a){return setColor(r,g,b,a);}
    @Override default LegacyVertexConsumer uv(float u,float v){return setUv(u,v);}
    @Override default LegacyVertexConsumer overlayCoords(int u,int v){return setUv1(u,v);}
    @Override default LegacyVertexConsumer uv2(int u,int v){return setUv2(u,v);}
    @Override default LegacyVertexConsumer normal(float x,float y,float z){return setNormal(x,y,z);}
    @Override default void endVertex(){ }
    @Override default void defaultColor(int r,int g,int b,int a){setColor(r,g,b,a);}
    @Override default void unsetDefaultColor(){ }
}
