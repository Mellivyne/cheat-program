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
public record AppCastPlane(Vec3 center,float width,float height,float yaw,float pitch,float roll) {
    public record Hit(double u,double v,double distance) { }
    public Quaternionf rotation(){return new Quaternionf().rotateY((float)Math.toRadians(-yaw)).rotateX((float)Math.toRadians(pitch)).rotateZ((float)Math.toRadians(roll));}
    private Vec3 rotated(double x,double y,double z){Vector3f p=rotation().transform(new Vector3f((float)x,(float)y,(float)z));return new Vec3(p);}
    public Vec3 right(){return rotated(1,0,0);}
    public Vec3 up(){return rotated(0,1,0);}
    public Vec3 normal(){return rotated(0,0,1);}
    public Vec3 point(double u,double v){return center.add(right().scale((u-.5)*width)).add(up().scale((.5-v)*height));}
    public Hit pick(Vec3 origin,Vec3 ray,boolean bounded){
        Vec3 n=normal();double denominator=n.dot(ray);if(Math.abs(denominator)<.00001)return null;
        double distance=n.dot(center.subtract(origin))/denominator;if(distance<.05||distance>512)return null;
        Vec3 local=origin.add(ray.scale(distance)).subtract(center);
        double u=.5+local.dot(right())/width,v=.5-local.dot(up())/height;
        if(bounded&&(u<0||u>1||v<0||v>1))return null;return new Hit(u,v,distance);
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
                b.addVertex(pose,-width/2,height/2,0).setUv(0,0).setColor(color);
                b.addVertex(pose,-width/2,-height/2,0).setUv(0,1).setColor(color);
                b.addVertex(pose,width/2,-height/2,0).setUv(1,1).setColor(color);
                b.addVertex(pose,width/2,height/2,0).setUv(1,0).setColor(color);
                BufferUploader.drawWithShader(b.buildOrThrow());
            }
            if(frame||texture==null){
                Vec3 a=point(0,0),b=point(1,0),c=point(1,1),d=point(0,1);
                int color=texture==null?0xffd9a661:0xffc9e4ff;
                renderer.line(a,b,color,1.5f);renderer.line(b,c,color,1.5f);renderer.line(c,d,color,1.5f);renderer.line(d,a,color,1.5f);
            }
        }finally{RenderSystem.setShaderColor(1,1,1,1);renderer.end();}
    }
    /** The application pointer belongs to the plane, not to the player's HUD. */
    public void cursor(Render3D renderer,double u,double v){
        Vec3 tip=point(u,v).add(normal().scale(.004));double size=Math.min(width,height)*.025;
        Vec3 a=tip.add(right().scale(size*.55)).subtract(up().scale(size)),b=tip.add(right().scale(size*.18)).subtract(up().scale(size*.78));
        renderer.begin();try{renderer.line(tip,a,0xcc000000,4);renderer.line(a,b,0xcc000000,4);renderer.line(b,tip,0xcc000000,4);renderer.line(tip,a,0xffffffff,2);renderer.line(a,b,0xffffffff,2);renderer.line(b,tip,0xffffffff,2);}finally{renderer.end();}
    }
}
