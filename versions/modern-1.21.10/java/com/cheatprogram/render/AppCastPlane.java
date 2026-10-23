package com.cheatprogram.render;

import com.cheatprogram.compat.gl.BufferUploader;

import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.cheatprogram.compat.gl.CoreShaders;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A genuine oriented world plane. Camera rotation is used only to project and pick it. */
public record AppCastPlane(Vec3 center,float width,float height,float yaw,float pitch,float roll,float curvature,boolean verticalCurve,int curveSegments) {
    public AppCastPlane(Vec3 center,float width,float height,float yaw,float pitch,float roll){this(center,width,height,yaw,pitch,roll,0,false,48);}
    private CastSurface surface(){return new CastSurface(width,height,curvature,verticalCurve,curveSegments);}
    public Vec3 normal(double u,double v){var p=surface().normal(u,v);return rotated(p.x(),p.y(),p.z());}
    public record Hit(double u,double v,double distance) { }
    public Quaternionf rotation(){return new Quaternionf().rotateY((float)Math.toRadians(-yaw)).rotateX((float)Math.toRadians(pitch)).rotateZ((float)Math.toRadians(roll));}
    private Vec3 rotated(double x,double y,double z){Vector3f p=rotation().transform(new Vector3f((float)x,(float)y,(float)z));return new Vec3(p);}
    public Vec3 right(){return rotated(1,0,0);}
    public Vec3 up(){return rotated(0,1,0);}
    public Vec3 normal(){return rotated(0,0,1);}
    public Vec3 point(double u,double v){var p=surface().point(u,v);return center.add(rotated(p.x(),p.y(),p.z()));}
    public Hit pick(Vec3 origin,Vec3 ray,boolean bounded){
        Vec3 p=origin.subtract(center),right=right(),up=up(),normal=normal();
        var hit=surface().pick(new CastSurface.Point(p.dot(right),p.dot(up),p.dot(normal)),new CastSurface.Point(ray.dot(right),ray.dot(up),ray.dot(normal)),bounded);
        return hit==null?null:new Hit(hit.u(),hit.v(),hit.distance());
    }
    private void vertex(BufferBuilder b,Matrix4f pose,CastSurface surface,double u,double v,int color){
        var p=surface.point(u,v);b.addVertex(pose,(float)p.x(),(float)p.y(),(float)p.z()).setUv((float)u,(float)v).setColor(color);
    }
    public void draw(Render3D renderer,ResourceLocation texture,float opacity,boolean throughWalls,boolean frame){
        draw(renderer,texture,opacity,throughWalls,frame,1);
    }
    public void draw(Render3D renderer,ResourceLocation texture,float opacity,boolean throughWalls,boolean frame,float brightness){
        if(renderer.camera.distanceToSqr(center)>512*512)return;
        Matrix4f pose=new Matrix4f().translation((float)(center.x-renderer.camera.x),(float)(center.y-renderer.camera.y),(float)(center.z-renderer.camera.z)).rotate(rotation());
        renderer.begin();
        try{
            if(!throughWalls)RenderSystem.enableDepthTest();RenderSystem.setShaderColor(1,1,1,1);
            if(texture!=null){
                RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);RenderSystem.setShaderTexture(0,texture);
                BufferBuilder b=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
                int color=Math.round(Math.max(0,Math.min(1,opacity))*255)<<24|0xffffff;
                float light=Math.max(.1f,Math.min(2,brightness));RenderSystem.setShaderColor(light,light,light,1);
                CastSurface surface=surface();int strips=surface.segments();
                for(int i=0;i<strips;i++){
                    double from=i/(double)strips,to=(i+1)/(double)strips;
                    double u0=verticalCurve?0:from,u1=verticalCurve?1:to,v0=verticalCurve?from:0,v1=verticalCurve?to:1;
                    vertex(b,pose,surface,u0,v0,color);vertex(b,pose,surface,u0,v1,color);
                    vertex(b,pose,surface,u1,v1,color);vertex(b,pose,surface,u1,v0,color);
                }
                BufferUploader.drawWithShader(b.buildOrThrow());
            }
            if(frame||texture==null){
                int color=texture==null?0xffd9a661:0xffc9e4ff;int strips=surface().segments();
                for(int i=0;i<strips;i++){
                    double a=i/(double)strips,b=(i+1)/(double)strips;
                    if(verticalCurve){renderer.line(point(0,a),point(0,b),color,1.5f);renderer.line(point(1,a),point(1,b),color,1.5f);}
                    else{renderer.line(point(a,0),point(b,0),color,1.5f);renderer.line(point(a,1),point(b,1),color,1.5f);}
                }
                if(verticalCurve){renderer.line(point(0,0),point(1,0),color,1.5f);renderer.line(point(0,1),point(1,1),color,1.5f);}
                else{renderer.line(point(0,0),point(0,1),color,1.5f);renderer.line(point(1,0),point(1,1),color,1.5f);}

            }
        }finally{RenderSystem.setShaderColor(1,1,1,1);renderer.end();}
    }
    /** The application pointer belongs to the plane, not to the player's HUD. */
    public void cursor(Render3D renderer,double u,double v){
        double size=Math.min(width,height)*.025;
        Vec3 offset=normal(u,v).scale(.004),tip=point(u,v).add(offset);
        Vec3 a=point(u+size*.55/width,v+size/height).add(offset),b=point(u+size*.18/width,v+size*.78/height).add(offset);
        renderer.begin();try{renderer.line(tip,a,0xcc000000,4);renderer.line(a,b,0xcc000000,4);renderer.line(b,tip,0xcc000000,4);renderer.line(tip,a,0xffffffff,2);renderer.line(a,b,0xffffffff,2);renderer.line(b,tip,0xffffffff,2);}finally{renderer.end();}
    }
}
