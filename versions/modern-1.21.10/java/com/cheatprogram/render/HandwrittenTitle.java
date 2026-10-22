package com.cheatprogram.render;

import com.cheatprogram.compat.gl.BufferUploader;

import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import com.cheatprogram.compat.gl.CoreShaders;
import org.joml.Matrix4f;
import java.awt.Color;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

/** Authored center-line calligraphy, revealed by travelled pen distance, never by a rectangular wipe. */
public final class HandwrittenTitle {
    private record Stroke(float ax,float ay,float bx,float by,float begin,float end,boolean start) { }
    private static final List<Stroke> STROKES=new ArrayList<>();
    private static final float WIDTH;
    private static final float MIN_Y, MAX_Y;
    private static final int[] TRANS_COLORS={0x55cdfc,0xf7a8b8,0xffffff,0xf7a8b8};
    private static final float[] CIRCLE_X=new float[9],CIRCLE_Y=new float[9];
    private static float length;
    static {
        for(int i=0;i<=8;i++){CIRCLE_X[i]=(float)Math.cos(i*Math.PI/4);CIRCLE_Y[i]=(float)Math.sin(i*Math.PI/4);}
        float offset=0;
        for(char letter:"Cheat Program".toCharArray()){
            if(letter==' '){offset+=35;continue;}
            Path2D.Float path=new Path2D.Float();float advance;
            switch(letter){
                case 'C'->{path.moveTo(80,24);path.curveTo(50,-10,2,22,0,60);path.curveTo(-2,100,40,108,72,82);path.curveTo(82,72,86,78,94,88);advance=94;}
                case 'h'->{path.moveTo(0,88);path.curveTo(30,54,40,-20,21,0);path.curveTo(10,22,2,68,4,89);path.curveTo(28,32,57,40,43,77);path.curveTo(38,98,60,98,67,86);advance=67;}
                case 'e'->{path.moveTo(0,86);path.curveTo(34,75,42,47,23,48);path.curveTo(-6,48,-1,100,31,96);path.curveTo(40,96,50,91,54,86);advance=54;}
                case 'a'->{path.moveTo(0,86);path.curveTo(7,49,45,38,43,62);path.curveTo(42,94,2,108,6,75);path.curveTo(13,50,34,45,45,52);path.curveTo(42,69,30,105,58,86);advance=58;}
                case 't'->{path.moveTo(0,86);path.curveTo(22,64,39,3,27,22);path.curveTo(18,37,5,89,25,96);path.curveTo(36,102,48,90,52,86);path.moveTo(7,53);path.curveTo(22,51,37,49,47,47);advance=52;}
                case 'P'->{path.moveTo(0,88);path.curveTo(6,63,25,-3,23,8);path.curveTo(20,44,13,97,14,102);path.moveTo(20,25);path.curveTo(35,-12,94,10,75,45);path.curveTo(65,63,36,69,23,56);path.moveTo(14,88);path.curveTo(35,105,66,95,84,86);advance=84;}
                case 'r'->{path.moveTo(0,86);path.curveTo(16,70,20,50,19,48);path.curveTo(21,39,30,44,27,53);path.curveTo(45,30,62,50,43,63);path.curveTo(35,80,42,93,57,86);advance=57;}
                case 'o'->{path.moveTo(0,86);path.curveTo(2,52,43,38,47,60);path.curveTo(55,87,24,110,8,87);path.curveTo(-6,67,15,48,39,51);path.curveTo(44,62,45,83,62,75);path.curveTo(68,72,65,85,68,86);advance=68;}
                case 'g'->{path.moveTo(0,86);path.curveTo(10,45,46,43,44,60);path.curveTo(40,95,0,108,6,72);path.curveTo(15,49,34,48,45,55);path.curveTo(35,114,29,161,8,152);path.curveTo(-7,137,25,112,66,86);advance=66;}
                case 'm'->{path.moveTo(0,86);path.curveTo(9,74,18,51,20,48);path.curveTo(19,66,10,89,13,91);path.curveTo(33,39,54,39,43,80);path.curveTo(39,99,41,91,47,79);path.curveTo(68,31,88,48,76,81);path.curveTo(72,99,91,102,105,86);advance=105;}
                default->{advance=30;}
            }
            var iterator=path.getPathIterator(null,1.25);float[] point=new float[6];float px=0,py=0;boolean start=true;
            while(!iterator.isDone()){
                int type=iterator.currentSegment(point);float nx=point[0]+offset,ny=point[1];
                if(type==PathIterator.SEG_LINETO){float distance=(float)Math.hypot(nx-px,ny-py);if(distance>.0001){STROKES.add(new Stroke(px,py,nx,ny,length,length+distance,start));length+=distance;start=false;}}
                if(type==PathIterator.SEG_MOVETO)start=true;
                px=nx;py=ny;iterator.next();
            }
            offset+=advance;
        }
        WIDTH=offset;
        float minY=Float.POSITIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        for(Stroke stroke:STROKES){minY=Math.min(minY,Math.min(stroke.ay,stroke.by));maxY=Math.max(maxY,Math.max(stroke.ay,stroke.by));}
        MIN_Y=minY;MAX_Y=maxY;
    }
    private HandwrittenTitle(){ }
    public static float aspect(){return WIDTH/170f;}
    /** Includes glow and round caps, for keeping the authored path inside the viewport. */
    public static float halfHeight(float width,float thickness,float glow){
        return width/WIDTH*((MAX_Y-MIN_Y)/2+6*thickness*(glow>0?2.1f:1));
    }
    public static void draw(float centerX,float centerY,float width,float progress,float opacity,String palette,float phase,float thickness,float glow){
        if(progress<=0||opacity<=0||width<=0||!Float.isFinite(width)||!R2D.isActive())return;
        float scale=width/WIDTH,x=centerX-width/2,y=centerY-(MIN_Y+MAX_Y)/2*scale,radius=6*scale*thickness;
        Matrix4f pose=R2D.canvasPose();float gui=(float)Minecraft.getInstance().getWindow().getGuiScale();
        var previous=RenderSystem.getShader();
        try{
            RenderSystem.setShader(CoreShaders.POSITION_COLOR);
            for(int pass=2;pass>=0;pass--){
                if(pass>0&&glow<=0)continue;
                float spread=pass==2?2.1f:pass==1?1.45f:1;
                float alpha=opacity*(pass==0?1:pass==1?.18f*glow:.055f*glow);
                BufferBuilder buffer=Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.POSITION_COLOR);
                float cutoff=length*Math.max(0,Math.min(1,progress));
                for(Stroke stroke:STROKES){
                    if(stroke.begin>=cutoff)break;
                    float portion=Math.min(1,(cutoff-stroke.begin)/(stroke.end-stroke.begin));
                    float bx=stroke.ax+(stroke.bx-stroke.ax)*portion,by=stroke.ay+(stroke.by-stroke.ay)*portion;
                    float ax=x+stroke.ax*scale,ay=y+stroke.ay*scale,ex=x+bx*scale,ey=y+by*scale;
                    float dx=ex-ax,dy=ey-ay,len=(float)Math.hypot(dx,dy);if(len<.001)continue;
                    float r=radius*spread,nx=-dy/len*r,ny=dx/len*r;
                    int start=color(stroke.ax/WIDTH,palette,phase,alpha),end=color(bx/WIDTH,palette,phase,alpha);
                    vertex(buffer,pose,gui,ax+nx,ay+ny,start);vertex(buffer,pose,gui,ax-nx,ay-ny,start);vertex(buffer,pose,gui,ex-nx,ey-ny,end);
                    vertex(buffer,pose,gui,ax+nx,ay+ny,start);vertex(buffer,pose,gui,ex-nx,ey-ny,end);vertex(buffer,pose,gui,ex+nx,ey+ny,end);
                    if(stroke.start)disk(buffer,pose,gui,ax,ay,r,start);
                    disk(buffer,pose,gui,ex,ey,r,end);
                }
                MeshData mesh=buffer.build();if(mesh!=null)BufferUploader.drawWithShader(mesh);
            }
        }finally{if(previous==null)RenderSystem.clearShader();else RenderSystem.setShader(previous);}
    }
    private static void disk(BufferBuilder b,Matrix4f pose,float gui,float x,float y,float radius,int color){
        for(int i=0;i<8;i++){
            vertex(b,pose,gui,x,y,color);vertex(b,pose,gui,x+CIRCLE_X[i]*radius,y+CIRCLE_Y[i]*radius,color);
            vertex(b,pose,gui,x+CIRCLE_X[i+1]*radius,y+CIRCLE_Y[i+1]*radius,color);
        }
    }
    private static void vertex(BufferBuilder b,Matrix4f pose,float gui,float x,float y,int color){b.addVertex(pose,x/gui,y/gui,0).setColor(color);}
    private static int color(float x,String palette,float phase,float opacity){
        int rgb;
        if(palette.equals("intro_white"))rgb=0xffffff;
        else if(palette.equals("intro_trans")){
            // Five equal spatial bands reproduce the supplied flag; a four-step mirrored cycle avoids blue->blue seams.
            float u=x*5+phase;int step=(int)Math.floor(u);float edge=u-step;
            int a=TRANS_COLORS[Math.floorMod(step,4)],c=TRANS_COLORS[Math.floorMod(step+1,4)];
            float mix=edge<.7f?0:(edge-.7f)/.3f;mix=mix*mix*(3-2*mix);
            rgb=mix(a,c,mix);
        }else rgb=Color.HSBtoRGB(.015f+Math.max(0,Math.min(1,x))*.77f,1,1)&0xffffff;
        return Math.round(Math.min(1,opacity)*255)<<24|rgb;
    }
    private static int mix(int a,int b,float t){int r=Math.round((a>>16&255)*(1-t)+(b>>16&255)*t),g=Math.round((a>>8&255)*(1-t)+(b>>8&255)*t),c=Math.round((a&255)*(1-t)+(b&255)*t);return r<<16|g<<8|c;}
}
