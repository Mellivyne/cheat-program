package com.cheatprogram.render;

import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.List;

/** Bounded album scenes; rendering and picking consume the very same transforms. */
public final class AlbumSpaceModel {
    public record Card(int index, Matrix4f matrix) { }
    /** Clear material on a local [-1,1] rectangular sheet; never an opaque room. */
    public record GlassSurface(Matrix4f matrix) { }
    private static final float PI = (float)Math.PI;
    private int count, selected;
    private String layout = "floor";
    private float position,target,zoom=1,targetZoom=1,orbit,targetOrbit,tilt,targetTilt,time;
    private float startX,startY,startTarget,startOrbit,startTilt,inspection,targetInspection;
    private boolean held;
    private final java.util.Map<Integer,Float> emphasis = new java.util.HashMap<>();
    public static boolean supports(String value) {
        return "floor".equals(value)||"stars".equals(value)||"spiral".equals(value)
                ||"sphere".equals(value)||"gallery".equals(value);
    }
    public int selected() { return selected; }
    public void reset(String kind,int index,int size) {
        layout=supports(kind)?kind:"floor"; count=Math.max(0,size); selected=bound(index);
        position=target=navigationTarget(selected);
        orbit=targetOrbit=tilt=targetTilt=0; zoom=targetZoom=1;
        inspection=targetInspection=0; held=false; time=0;
        emphasis.clear(); emphasis.put(selected,1f);
        if(layout.equals("sphere")) { targetOrbit=-sphereLongitude(selected); targetTilt=sphereLatitude(selected); orbit=targetOrbit;tilt=targetTilt; }
    }
    private int navigationTarget(int index) { return layout.equals("gallery")?(index/2)*2:index; }
    private int bound(int index) { return Math.max(0,Math.min(Math.max(0,count-1),index)); }
    private float clampPosition(float value) { return Math.max(0,Math.min(Math.max(0,count-1),value)); }
    public void select(int index) {
        selected=bound(index); target=navigationTarget(selected);
        emphasis.putIfAbsent(selected,0f);
        if(layout.equals("sphere")) {
            float wanted=-sphereLongitude(selected);
            targetOrbit+=(float)Math.atan2(Math.sin(wanted-targetOrbit),Math.cos(wanted-targetOrbit));
            targetTilt=sphereLatitude(selected);
        }
        if(layout.equals("gallery")) targetInspection=1;
    }
    public void begin(float x,float y) {
        if(!Float.isFinite(x)||!Float.isFinite(y))return;
        held=true;startX=x;startY=y;startTarget=target;startOrbit=targetOrbit;startTilt=targetTilt;
        if(layout.equals("gallery"))targetInspection=0;
    }
    public void drag(float x,float y) {
        if(!held||!Float.isFinite(x)||!Float.isFinite(y))return;
        float dx=x-startX,dy=y-startY;
        switch(layout) {
            case "sphere" -> {
                targetOrbit=startOrbit+dx*.009f;
                targetTilt=Math.max(-PI*.48f,Math.min(PI*.48f,startTilt+dy*.007f));
                if(count>85)target=clampPosition(startTarget+dy/45);
            }
            case "gallery" -> { target=clampPosition(startTarget-dy/43-dx/80); targetInspection=0; }
            case "spiral" -> {targetOrbit=startOrbit+dx*.008f;target=clampPosition(startTarget+dy/42);}
            default -> {
                target=clampPosition(startTarget-dx/76+dy/48*7);
                targetOrbit=startOrbit+dx*.0015f;targetTilt=Math.max(-.25f,Math.min(.25f,startTilt+dy*.001f));
            }
        }
    }
    public boolean release(float x,float y) {
        boolean click=held&&Float.isFinite(x)&&Float.isFinite(y)&&Math.hypot(x-startX,y-startY)<4;
        held=false;
        return click;
    }
    public void cancel(){held=false;}
    public void scroll(double amount) {
        if(!Double.isFinite(amount))return;
        float limited=(float)Math.max(-30,Math.min(30,amount));
        if(layout.equals("spiral"))target=clampPosition(target-limited);
        else {
            float minimum=layout.equals("gallery")?.65f:.35f;
            float maximum=layout.equals("sphere")?2f:layout.equals("gallery")?1.5f:2.4f;
            targetZoom=Math.max(minimum,Math.min(maximum,targetZoom*(float)Math.pow(1.12,limited)));
        }
    }
    public void update(float seconds) {
        float dt=Float.isFinite(seconds)?Math.max(0,Math.min(.1f,seconds)):0,a=1-(float)Math.exp(-11*dt);
        time+=dt; position+=(target-position)*a; if(Math.abs(target-position)<.0005f)position=target; zoom+=(targetZoom-zoom)*a;
        orbit+=(targetOrbit-orbit)*a;tilt+=(targetTilt-tilt)*a;inspection+=(targetInspection-inspection)*a;
        emphasis.replaceAll((i,v)->v+((i==selected?1:0)-v)*a);
        emphasis.entrySet().removeIf(e->e.getKey()!=selected&&e.getValue()<.001f);
    }
    private float focus(int index){return emphasis.getOrDefault(index,0f);}
    private float sphereLongitude(int index){return (float)((Math.floorMod(index,Math.min(85,Math.max(1,count)))*2.399963229728653)% (Math.PI*2));}
    private float sphereLatitude(int index){int slots=Math.min(85,Math.max(1,count)),slot=Math.floorMod(index,slots);return (float)Math.asin(1-2*(slot+.5)/slots);}
    private Matrix4f scene() {
        if(layout.equals("sphere"))return new Matrix4f().translate(0,0,-8.3f).rotateX(tilt).rotateY(orbit).scale(zoom);
        if(layout.equals("gallery")) {
            float side=selected%2==0?-1:1,p=inspection;
            float tx=side*3.6f*p,tz=-6;
            // Dolly along the actual look direction. Scaling a room instead
            // would move a selected painting out from under the gaze.
            float ex=tx-tx/zoom,ez=tz+(6-6*p)/zoom;
            return new Matrix4f().lookAt(ex,0,ez,tx,0,tz,0,1,0);
        }
        boolean turn=layout.equals("floor")||layout.equals("stars");
        return new Matrix4f().translate(0,0,-7).rotateY(turn?orbit:0).rotateX(turn?tilt:0).scale(zoom).translate(0,0,7);
    }
    public List<Card> cards() {
        var cards=new ArrayList<Card>();if(count==0)return cards;
        int center=bound(Math.round(position));
        int radius=layout.equals("gallery")?17:42;
        int first=Math.max(0,center-radius),last=(int)Math.min((long)count-1,(long)center+radius);
        if(layout.equals("sphere")){int visible=Math.min(85,count);first=Math.max(0,Math.min(count-visible,center-visible/2));last=first+visible-1;}
        Matrix4f scene=scene();
        for(int i=first;i<=last;i++) {
            float f=focus(i),x,y,z,rx=0,ry=0,size;
            if(layout.equals("sphere")) {
                float lon=sphereLongitude(i),lat=sphereLatitude(i),radiusSphere=2.7f+f*.75f;
                x=radiusSphere*(float)(Math.cos(lat)*Math.sin(lon));y=radiusSphere*(float)Math.sin(lat);z=radiusSphere*(float)(Math.cos(lat)*Math.cos(lon));
                ry=lon;rx=-lat;size=(float)(.42f+.14f*(1-Math.min(85,count)/85f))+f*.45f;
            }else if(layout.equals("gallery")) {
                float row=i/2,base=position/2;
                x=i%2==0?-3.6f:3.6f;y=0;z=-6-(row-base)*3.15f;ry=i%2==0?PI/2:-PI/2;size=.92f;
            }else if(layout.equals("floor")) {
                float row=(float)Math.floor(i/7.0),base=position/7;
                x=(i%7-3)*1.46f;y=-1.75f+f*1.7f;z=-6.2f-(row-base)*1.52f;rx=-PI*.42f*(1-f);size=.7f+f*.25f;
            }else if(layout.equals("stars")) {
                float row=i/7,base=position/7;
                x=(i%7-3)*1.75f;y=(row-base)*1.25f+.16f*(float)Math.sin(time*.45+i*2.4);
                z=-8-(float)(Math.sin(i*2.399)*1.3);ry=.15f*(float)Math.sin(i*1.8+time*.15);size=.64f+.12f*(float)((Math.sin(i*5.3)+1)*.5);
                x*=1-f*.8f;y*=1-f*.9f;z+=(-3.8f-z)*f;ry*=1-f;size+=f*.25f;
            }else {
                float d=i-position,angle=d*.61f+orbit;
                x=3.2f*(float)Math.sin(angle);y=d*.39f;z=-7.1f+2.1f*(float)Math.cos(angle);ry=-angle*.16f;
                x*=1-f*.65f;y*=1-f*.8f;z+=f*.8f;ry*=1-f;size=.68f+f*.25f;
            }
            Matrix4f matrix=new Matrix4f(scene).translate(x,y,z);
            if(layout.equals("sphere"))matrix.rotateY(ry).rotateX(rx);else matrix.rotateX(rx).rotateY(ry);
            cards.add(new Card(i,matrix.scale(size)));
        }
        return cards;
    }
    /** Browse and prefetch follow the viewport, independently of the playing song. */
    public int browseIndex() { return bound(Math.round(position)); }
    public List<Integer> preloadIndices() {
        var indices = new java.util.LinkedHashSet<Integer>();
        if (count == 0) return List.of();
        int center = browseIndex(); indices.add(center);
        for (var card : cards()) indices.add(card.index());
        int direction = target >= startTarget ? 1 : -1;
        int distance = layout.equals("gallery") ? 18 : 43;
        for (int i = distance; i < distance + 16; i++) {
            int index = center + direction * i;
            if (index >= 0 && index < count) indices.add(index);
        }
        return new ArrayList<>(indices);
    }
    private void glass(List<GlassSurface> surfaces, Matrix4f view, float x,float y,float z,float sx,float sy,float rx,float ry) {
        surfaces.add(new GlassSurface(new Matrix4f(view).translate(x,y,z).rotateY(ry).rotateX(rx).scale(sx,sy,1)));
    }
    public List<GlassSurface> glass() {
        if (!layout.equals("gallery") || count == 0) return List.of();
        var surfaces = new ArrayList<GlassSurface>(); Matrix4f view = scene();
        // The corridor is transparent liquid glass, sampling the surrounding UI.
        // No opaque ceiling, floor, architectural wall, frame or light fixtures.
        glass(surfaces,view,-4.05f,.35f,-13,17,1.9f,0,PI/2);
        glass(surfaces,view,4.05f,.35f,-13,17,1.9f,0,-PI/2);
        glass(surfaces,view,0,-1.48f,-13,4.05f,17,-PI/2,0);
        int center=browseIndex(),first=Math.max(0,center-17),last=(int)Math.min((long)count-1,(long)center+17);
        for (int i=first;i<=last;i++) {
            float z=-6-(i/2-position/2)*3.15f, side=i%2==0?-1:1;
            // A thin rounded lens supports each album without a colored frame.
            glass(surfaces,view,side*3.63f,0,z,1.04f,1.04f,0,side<0?PI/2:-PI/2);
        }
        return surfaces;
    }
}
