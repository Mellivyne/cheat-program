package com.cheatprogram.render;

import com.cheatprogram.media.player.MusicArtworkCache;
import com.cheatprogram.media.player.MusicModels.Track;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import java.util.List;

/** Album cases occupy real XYZ space, rendered into an isolated, depth-tested perspective target. */
public final class MusicCoverCarouselRenderer implements AutoCloseable {
    private TextureTarget target;
    private boolean failed;
    private final CoverHover hover=new CoverHover();
    public void clearHover(){hover.clear();}
    public boolean failed(){return failed;}
    /** The wall shares cached real artwork, but does not allocate a perspective render target. */
    public void drawWall(float x,float y,float width,float height,float scale,float zoom,List<Track> tracks,List<AlbumWallModel.Card> cards,MusicArtworkCache artwork){
        if(tracks.isEmpty()||scale<=0)return;float edge=scale*Math.min(1,Math.max(.01f,zoom));R2D.pushClip(x,y,width,height);
        try{for(AlbumWallModel.Card card:cards){
            Track track=tracks.get(card.index());float px=x+card.x()*scale,py=y+card.y()*scale,size=card.size()*scale,focus=card.emphasis();
            float outline=.35f*edge;
            if(focus>.01f)R2D.rect(px-outline,py-outline,size+2*outline,size+2*outline,Math.round(235*focus)<<24|0xef5268);
            ResourceLocation image=artwork.cachedArtwork(track.provider(),track.cover());
            if(image!=null){clampArtwork(image);R2D.texture(image,px,py,size,size,0,0,1,1,0xffffffff);}
            else {R2D.rect(px,py,size,size,0xff252933);R2D.iconCentered(Icon.MUSIC,px+size*.5f,py+size*.43f,Math.min(32*scale,size*.24f),0xff787d88);}
            R2D.rrect(px,py+size*.58f,size,size*.42f,0,0x00000000,0xe6000000);
        }}finally{R2D.popClip();}
    }
    public void draw(GuiGraphics graphics,float x,float y,float width,float height,List<Track> tracks,float position,MusicArtworkCache artwork){
        draw(graphics,x,y,width,height,tracks,position,artwork,false);
    }
    public void draw(GuiGraphics graphics,float x,float y,float width,float height,List<Track> tracks,float position,MusicArtworkCache artwork,boolean ring){
        draw(graphics,x,y,width,height,tracks,position,artwork,ring,Float.NaN,Float.NaN,0);
    }
    public void draw(GuiGraphics graphics,float x,float y,float width,float height,List<Track> tracks,float position,MusicArtworkCache artwork,boolean ring,float mouseX,float mouseY,float seconds){
        hover.update(hitProjected(tracks.size(),position,ring,width,height,mouseX-x,mouseY-y,hover),seconds);
        artwork.preload(tracks,ring?RingMotion.index(position,tracks.size()):Math.round(position),4);
        if(tracks.isEmpty()||width<2||height<2||failed)return;
        RenderStateSnapshot saved=new RenderStateSnapshot();
        try{
            try{
                RenderSystem.disableScissor();int w=Math.min(1600,Math.max(1,Math.round(width))),h=Math.min(800,Math.max(1,Math.round(height)));
                if(target==null){target=new TextureTarget(w,h,true);target.setClearColor(0,0,0,0);target.setFilterMode(GL11.GL_LINEAR);}
                else if(target.width!=w||target.height!=h)target.resize(w,h);
                RenderSystem.depthMask(true);RenderSystem.colorMask(true,true,true,true);target.clear();target.bindWrite(true);
                RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11.GL_LEQUAL);RenderSystem.disableCull();RenderSystem.disableBlend();
                RenderSystem.setShaderColor(1,1,1,1);RenderSystem.setShaderFog(FogParameters.NO_FOG);
                RenderSystem.getModelViewStack().identity();RenderSystem.setTextureMatrix(new Matrix4f());
                RenderSystem.setProjectionMatrix(projection(width,height),ProjectionType.PERSPECTIVE);
                if(ring){for(RingCase item:ringCases(tracks.size(),position)){
                    float amount=hover.amount(item.index());Matrix4f model=ringModel(item,amount);
                    Track track=tracks.get(item.index());ResourceLocation texture=artwork.artwork(track.provider(),track.cover());
                    album(model,texture,Math.min(1,.62f+.38f*(float)((Math.cos(item.angle())+1)*.5)));
                }}else {int first=Math.max(0,(int)Math.floor(position)-4),last=Math.min(tracks.size()-1,(int)Math.ceil(position)+4);
                for(int i=first;i<=last;i++){
                    float d=i-position,amount=hover.amount(i);Matrix4f model=coverflowModel(d,amount);
                    ResourceLocation texture=artwork.artwork(tracks.get(i).provider(),tracks.get(i).cover());
                    album(model,texture,Math.min(1,Math.max(.45f,1-Math.abs(d)*.12f)));
                }}
            }finally{saved.restore();}
            R2D.applyState();R2D.pushClip(x,y,width,height);
            try{
                saved.intersectClip();float scale=(float)Minecraft.getInstance().getWindow().getGuiScale();
                Matrix4f pose=graphics.pose().last().pose();int fade=R2D.fade(0xffffffff)>>>24,color=fade<<24|fade<<16|fade<<8|fade;
                BufferBuilder quad=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
                quad.addVertex(pose,x/scale,y/scale,0).setUv(0,1).setColor(color);
                quad.addVertex(pose,x/scale,(y+height)/scale,0).setUv(0,0).setColor(color);
                quad.addVertex(pose,(x+width)/scale,(y+height)/scale,0).setUv(1,0).setColor(color);
                quad.addVertex(pose,(x+width)/scale,y/scale,0).setUv(1,1).setColor(color);
                RenderSystem.blendFuncSeparate(GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
                RenderSystem.setShaderColor(1,1,1,1);RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
                RenderSystem.setShaderTexture(0,target.getColorTextureId());BufferUploader.drawWithShader(quad.buildOrThrow());
            }finally{R2D.popClip();}
        }catch(RuntimeException unavailable){failed=true;}
        finally{saved.restore();}
    }
    /** Artwork is a single sheet: a case's dark thickness becomes a visible stripe when tilted. */
    private static void album(Matrix4f model,ResourceLocation texture,float light){
        if(texture==null){BufferBuilder placeholder=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
            face(placeholder,model,-1,-1,0,1,-1,0,1,1,0,-1,1,0,0xff30343c);
            RenderSystem.setShader(CoreShaders.POSITION_COLOR);BufferUploader.drawWithShader(placeholder.buildOrThrow());return;}
        clampArtwork(texture);int value=Math.round(255*light),color=0xff000000|value<<16|value<<8|value;
        BufferBuilder front=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
        front.addVertex(model,-1,1,0).setUv(0,0).setColor(color);front.addVertex(model,-1,-1,0).setUv(0,1).setColor(color);
        front.addVertex(model,1,-1,0).setUv(1,1).setColor(color);front.addVertex(model,1,1,0).setUv(1,0).setColor(color);
        RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);RenderSystem.setShaderTexture(0,texture);BufferUploader.drawWithShader(front.buildOrThrow());
    }
    private static void clampArtwork(ResourceLocation image){Minecraft.getInstance().getTextureManager().getTexture(image).setClamp(true);}
    private static Matrix4f projection(float width,float height){return new Matrix4f().perspective((float)Math.toRadians(40),width/height,.1f,40);}
    private static Matrix4f coverflowModel(float distance,float amount){return new Matrix4f().translate(distance*1.27f,.035f*amount,-3.65f-Math.abs(distance)*.68f).rotateY((float)(Math.tanh(distance*.95)*Math.toRadians(-56))).scale(1+.025f*amount);}
    private static Matrix4f ringModel(RingCase item,float amount){float prominence=1+.08f*(float)Math.exp(-item.angle()*item.angle()/.24f);return new Matrix4f().translate(item.x(),item.y()+.035f*amount,item.z()).rotateY(-item.angle()*.45f).scale(prominence*(1+.025f*amount));}
    /** Hit tests project the exact animated sheet, then compare depth at the pointer. */
    public static int hitProjected(int count,float position,boolean ring,float width,float height,float x,float y,CoverHover hover){
        if(count<=0||!Float.isFinite(position)||!Float.isFinite(x)||!Float.isFinite(y)||width<2||height<2||x<0||y<0||x>=width||y>=height)return -1;
        Matrix4f projection=projection(width,height);int found=-1;float nearest=Float.POSITIVE_INFINITY;
        if(ring){for(RingCase item:ringCases(count,position)){float depth=sheetDepth(projection,ringModel(item,hover.amount(item.index())),width,height,x,y);if(depth<nearest){nearest=depth;found=item.index();}}}
        else for(int i=Math.max(0,(int)Math.floor(position)-4);i<=Math.min(count-1,(int)Math.ceil(position)+4);i++){float depth=sheetDepth(projection,coverflowModel(i-position,hover.amount(i)),width,height,x,y);if(depth<nearest){nearest=depth;found=i;}}
        return found;
    }
    private static float sheetDepth(Matrix4f projection,Matrix4f model,float width,float height,float x,float y){
        Matrix4f matrix=new Matrix4f(projection).mul(model);Vector3f[] corners={new Vector3f(-1,1,0),new Vector3f(-1,-1,0),new Vector3f(1,-1,0),new Vector3f(1,1,0)};
        for(Vector3f corner:corners){matrix.transformProject(corner);corner.x=(corner.x+1)*width*.5f;corner.y=(1-corner.y)*height*.5f;}
        return Math.min(triangleDepth(corners[0],corners[1],corners[2],x,y),triangleDepth(corners[0],corners[2],corners[3],x,y));
    }
    private static float triangleDepth(Vector3f a,Vector3f b,Vector3f c,float x,float y){
        float denominator=(b.y-c.y)*(a.x-c.x)+(c.x-b.x)*(a.y-c.y);if(Math.abs(denominator)<.00001f)return Float.POSITIVE_INFINITY;
        float u=((b.y-c.y)*(x-c.x)+(c.x-b.x)*(y-c.y))/denominator,v=((c.y-a.y)*(x-c.x)+(a.x-c.x)*(y-c.y))/denominator,w=1-u-v;
        return u>=-.00001f&&v>=-.00001f&&w>=-.00001f?u*a.z+v*b.z+w*c.z:Float.POSITIVE_INFINITY;
    }
    /** Only hovered and fading covers are retained; there is no full-library frame scan. */
    public static final class CoverHover {
        private final java.util.Map<Integer,Float> amounts=new java.util.HashMap<>();
        public float amount(int index){return amounts.getOrDefault(index,0f);}
        public void update(int index,float seconds){if(index>=0)amounts.putIfAbsent(index,0f);float dt=Float.isFinite(seconds)?Math.max(0,Math.min(.1f,seconds)):0,decay=(float)Math.exp(-14*dt);
            amounts.replaceAll((key,value)->{float target=key==index?1:0,next=target+(value-target)*decay;return Math.abs(next-target)<.001f?target:next;});amounts.entrySet().removeIf(entry->entry.getKey()!=index&&entry.getValue()==0);}
        public void clear(){amounts.clear();}
    }
    private static void face(BufferBuilder b,Matrix4f m,float x1,float y1,float z1,float x2,float y2,float z2,float x3,float y3,float z3,float x4,float y4,float z4,int color){
        b.addVertex(m,x1,y1,z1).setColor(color);b.addVertex(m,x2,y2,z2).setColor(color);b.addVertex(m,x3,y3,z3).setColor(color);b.addVertex(m,x4,y4,z4).setColor(color);
    }
    @Override public void close(){if(target!=null){RenderStateSnapshot saved=new RenderStateSnapshot();try{target.destroyBuffers();target=null;}finally{saved.restore();}}failed=false;hover.clear();}

    public record RingCase(int index,float x,float y,float z,float angle) { }
    /** A tilted circle in 3D. Large libraries use a bounded window with its seam at the rear. */
    public static List<RingCase> ringCases(int count,float position){
        if(count<=0||!Float.isFinite(position))return List.of();int visible=Math.min(12,count),first=Math.round(position)-visible/2;
        java.util.ArrayList<RingCase> result=new java.util.ArrayList<>(visible);float step=(float)(Math.PI*2/visible),tilt=(float)Math.toRadians(20);
        for(int slot=0;slot<visible;slot++){int virtual=first+slot;float angle=(virtual-position)*step;angle=(float)Math.atan2(Math.sin(angle),Math.cos(angle));
            // The front album gently opens its immediate neighbors. This odd,
            // continuous angular field leaves the circle and rear overlap intact.
            angle+=.22f*(float)Math.sin(angle)*(float)Math.exp(-angle*angle/1.2f);float rear=2.35f*(1-(float)Math.cos(angle));
            result.add(new RingCase(Math.floorMod(virtual,count),2.35f*(float)Math.sin(angle),rear*(float)Math.sin(tilt),-4-rear*(float)Math.cos(tilt),angle));
        }
        result.sort(java.util.Comparator.comparingDouble(RingCase::z).thenComparingInt(RingCase::index));return result;
    }

    /** Circular drag and spring settlement; only user gestures may emit a song selection. */
    public static final class RingMotion {
        private float position,velocity,start,origin;private int count=1,target,settled;private boolean dragging,pending,tracking,tap;private long sample;
        public float position(){return position;}public boolean active(){return dragging||pending||tracking;}
        public static int index(float position,int count){return count<=0||!Float.isFinite(position)?0:Math.floorMod(Math.round(position),count);}
        public void reset(int index,int size){count=Math.max(1,size);position=target=settled=Math.floorMod(index,count);velocity=0;dragging=pending=tracking=tap=false;}
        public void follow(int index,int size){count=Math.max(1,size);int current=Math.round(position),delta=Math.floorMod(index-current+count/2,count)-count/2;target=current+delta;settled=Math.floorMod(index,count);dragging=pending=tap=false;tracking=true;}
        /** Navigating away retains the visible selection but cannot replay a deferred gesture on return. */
        public void cancelInteraction(){dragging=pending=tracking=tap=false;velocity=0;target=Math.round(position);settled=Math.floorMod(target,count);}
        public void begin(float x){if(!Float.isFinite(x))return;start=x;origin=position;velocity=0;dragging=true;pending=tracking=tap=false;sample=System.nanoTime();}
        public void drag(float x){if(!dragging||!Float.isFinite(x))return;long now=System.nanoTime();float next=origin+(start-x)/135f,seconds=Math.max(.004f,(now-sample)/1_000_000_000f);velocity=Math.max(-12,Math.min(12,(next-position)/seconds));position=next;sample=now;}
        public void release(boolean clickedFront){if(!dragging)return;dragging=false;tap=clickedFront;velocity*=(float)Math.exp(-Math.max(0,(System.nanoTime()-sample)/1_000_000_000d)*18);target=Math.round(position+(tap?0:Math.max(-1.2f,Math.min(1.2f,velocity*.1f))));pending=true;}
        public void move(int delta){if(dragging)return;target=(pending||tracking?target:Math.round(position))+delta;pending=true;tracking=tap=false;}
        public int update(float seconds){if(dragging)return -1;float dt=Float.isFinite(seconds)?Math.max(0,Math.min(.1f,seconds)):0,difference=position-target,term=velocity+14*difference,decay=(float)Math.exp(-14*dt);position=target+(difference+term*dt)*decay;velocity=(velocity-14*term*dt)*decay;
            if(Math.abs(position-target)<.002f&&Math.abs(velocity)<.03f){position=target;velocity=0;tracking=false;int selected=Math.floorMod(target,count);if(position<0||position>=count){position=target=selected;}if(pending){pending=false;if(tap||selected!=settled){tap=false;settled=selected;return selected;}}tap=false;}return -1;
        }
    }

    /** Analytic critical damping stays stable across frame spikes. A settled selection emits once. */
    public static final class Motion {
        private float position,velocity,start,origin;private int target,settled,max;private boolean dragging,pending,tracking;private long sample;
        public float position(){return position;}
        public boolean active(){return dragging||pending||tracking;}
        public void reset(int index,int count){max=Math.max(0,count-1);position=target=settled=bound(index);velocity=0;dragging=pending=tracking=false;}
        /** Follow confirmed playback, preserving momentum and never emitting a play command. */
        public void follow(int index,int count){max=Math.max(0,count-1);target=settled=bound(index);dragging=pending=false;tracking=true;}
        private int bound(int index){return Math.max(0,Math.min(max,index));}
        /** Retain the visible selection while discarding any deferred play command. */
        public void cancelInteraction(){dragging=pending=tracking=false;velocity=0;target=settled=bound(Math.round(position));}
        public void begin(float x){if(!Float.isFinite(x))return;start=x;origin=position;velocity=0;dragging=true;pending=tracking=false;sample=System.nanoTime();}
        public void drag(float x){
            if(!dragging||!Float.isFinite(x))return;long now=System.nanoTime();float raw=origin+(start-x)/155f;
            float clamp=Math.max(0,Math.min(max,raw)),edge=raw-clamp;float next=clamp+(float)Math.tanh(edge)*.25f;
            float seconds=Math.max(.004f,(now-sample)/1_000_000_000f);velocity=Math.max(-12,Math.min(12,(next-position)/seconds));
            position=next;sample=now;
        }
        public void release(){if(!dragging)return;dragging=false;velocity*=(float)Math.exp(-Math.max(0,(System.nanoTime()-sample)/1_000_000_000d)*18);target=bound(Math.round(position+Math.max(-.75f,Math.min(.75f,velocity*.075f))));pending=true;}
        public void move(int delta){if(dragging)return;target=bound((pending||tracking?target:Math.round(position))+delta);tracking=false;pending=target!=settled;}
        public int update(float seconds){
            if(dragging)return -1;float dt=Float.isFinite(seconds)?Math.max(0,Math.min(.1f,seconds)):0;
            float difference=position-target,decay=(float)Math.exp(-15*dt),term=velocity+15*difference;
            position=target+(difference+term*dt)*decay;velocity=(velocity-15*term*dt)*decay;
            position=Math.max(-.25f,Math.min(max+.25f,position));
            if(Math.abs(position-target)<.002f&&Math.abs(velocity)<.03f){position=target;velocity=0;tracking=false;if(pending){pending=false;if(settled!=target){settled=target;return target;}}}
            return -1;
        }
    }

    /** Exact damped motion keeps click feedback continuous when controls reverse mid-flight. */
    public static final class ControlMotion {
        private final Spring playing=new Spring(),hover=new Spring(),press=new Spring(),previous=new Spring(),next=new Spring();
        private boolean initialized;
        public void update(boolean isPlaying,boolean isHovered,float seconds){if(!initialized){playing.value=isPlaying?1:0;initialized=true;}playing.advance(isPlaying?1:0,seconds,19);hover.advance(isHovered?1:0,seconds,16);press.advance(0,seconds,22);previous.advance(0,seconds,20);next.advance(0,seconds,20);}
        public void click(int direction){Spring pulse=direction<0?previous:direction>0?next:press;pulse.velocity=Math.min(20,pulse.velocity+14);}
        public float playing(){return Math.max(0,Math.min(1,playing.value));}
        public float scale(){return 1+.055f*hover.value-.1f*press.value;}
        public float skip(int direction){return Math.max(0,Math.min(1,(direction<0?previous:next).value));}
        private static final class Spring {float value,velocity;void advance(float target,float seconds,float speed){float dt=Float.isFinite(seconds)?Math.max(0,Math.min(.1f,seconds)):0,difference=value-target,term=velocity+speed*difference,decay=(float)Math.exp(-speed*dt);value=target+(difference+term*dt)*decay;velocity=(velocity-speed*term*dt)*decay;}}
    }

    /** Bounded layered crossfade: interruptions retain visible weights instead of restarting. */
    public static final class ContentFade<T> {
        public record Layer<T>(T value,float alpha) { }
        private final java.util.LinkedHashMap<T,Float> weights=new java.util.LinkedHashMap<>();private T selected;
        public void update(T value,float seconds){
            if(value==null){weights.clear();selected=null;return;}
            if(weights.isEmpty()){selected=value;weights.put(value,1f);return;}
            selected=value;weights.putIfAbsent(value,0f);
            float dt=Float.isFinite(seconds)?Math.max(0,Math.min(.1f,seconds)):0,decay=(float)Math.exp(-11*dt);
            weights.replaceAll((item,weight)->(item.equals(selected)?1:0)+(weight-(item.equals(selected)?1:0))*decay);
            weights.entrySet().removeIf(item->!item.getKey().equals(selected)&&item.getValue()<.001f);
            while(weights.size()>4){T lowest=null;float least=Float.MAX_VALUE;for(var entry:weights.entrySet())if(!entry.getKey().equals(selected)&&entry.getValue()<least){lowest=entry.getKey();least=entry.getValue();}weights.remove(lowest);}
        }
        public java.util.List<Layer<T>> layers(){java.util.List<Layer<T>> result=new java.util.ArrayList<>(weights.size());weights.forEach((item,weight)->result.add(new Layer<>(item,weight)));return result;}
        public void clear(){weights.clear();selected=null;}
    }
}


