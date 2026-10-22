package com.cheatprogram.render;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/** Square mosaic geometry, independent of rendering and account state. */
public final class AlbumWallModel {
    public static final float CARD=52, GAP=1, PITCH=CARD+GAP, EXTRA=3*PITCH;
    public static final float MIN_ZOOM=.18f, MAX_ZOOM=3.6f;
    public static final int PRELOAD_LIMIT=96;
    private static final int SIDE=8, PER_PATCH=40;
    private static final float PATCH=SIDE*PITCH;
    public record Card(int index,float x,float y,float size,float emphasis,float hover) {
        public boolean contains(float px,float py){return px>=x&&py>=y&&px<x+size&&py<y+size;}
    }
    private record Tile(float x,float y,float size) {float cx(){return x+size*.5f;}float cy(){return y+size*.5f;}}
    /** A browsing checkpoint owns copies so later search gestures cannot mutate it. */
    public static final class State {
        private final int count,selected;private final float zoom,panX,panY,width,height;private final boolean following;
        private final float[] x,y,size,tx,ty,ts,emphasis;private final BitSet moving;
        private State(AlbumWallModel wall){count=wall.count;selected=wall.selected;zoom=wall.zoom;panX=wall.panX;panY=wall.panY;width=wall.width;height=wall.height;following=wall.following;x=wall.x.clone();y=wall.y.clone();size=wall.size.clone();tx=wall.tx.clone();ty=wall.ty.clone();ts=wall.ts.clone();emphasis=wall.emphasis.clone();moving=(BitSet)wall.moving.clone();}
    }
    private int count,patchColumns=1,patchRows=1,patchOffset,selected;
    private float width,height,zoom=1,panX,panY,startX,startY,originX,originY;
    private float[] x=new float[0],y=new float[0],size=new float[0],tx=new float[0],ty=new float[0],ts=new float[0],emphasis=new float[0];
    private final BitSet moving=new BitSet();
    private final java.util.Map<Integer,Float> hover=new java.util.HashMap<>();private int hovered=-1;
    private int pressed=-1;private boolean held,dragged,following;
    public int selected(){return selected;}public float zoom(){return zoom;}
    public float panX(){return panX;}public float panY(){return panY;}
    public int columns(){return patchColumns*SIDE;}public int count(){return count;}
    public State save(){return new State(this);}
    public void restore(State state){
        if(state==null)return;count=state.count;configurePatches();selected=bound(state.selected);zoom=state.zoom;panX=state.panX;panY=state.panY;width=state.width;height=state.height;following=state.following;
        x=state.x.clone();y=state.y.clone();size=state.size.clone();tx=state.tx.clone();ty=state.ty.clone();ts=state.ts.clone();emphasis=state.emphasis.clone();moving.clear();moving.or(state.moving);hover.clear();hovered=-1;cancel();
    }
    public void reset(int index,int size){
        count=Math.max(0,size);configurePatches();selected=bound(index);zoom=1;
        x=new float[count];y=new float[count];this.size=new float[count];tx=new float[count];ty=new float[count];ts=new float[count];emphasis=new float[count];moving.clear();hover.clear();hovered=-1;
        for(int patch=0;patch<patchCount();patch++)base(patch);
        if(count>0){focus(selected);for(int i=0;i<count;i++){x[i]=tx[i];y[i]=ty[i];this.size[i]=ts[i];}emphasis[selected]=1;}
        cancel();following=true;centerNow();
    }
    private void configurePatches(){int patches=patchCount();patchColumns=Math.max(1,Math.min(patches,(int)Math.ceil(Math.sqrt(patches*1.8))));patchRows=Math.max(1,(patches+patchColumns-1)/patchColumns);patchOffset=Math.max(0,Math.min(patches-1,(patchRows/2)*patchColumns+patchColumns/2));}
    private int patchSlot(int patch){return Math.floorMod(patch+patchOffset,Math.max(1,patchCount()));}
    private int patchCount(){return (count+PER_PATCH-1)/PER_PATCH;}
    public void viewport(float w,float h){
        if(!Float.isFinite(w)||!Float.isFinite(h)||w<=0||h<=0)return;
        if(width==w&&height==h)return;
        if(width==0||height==0){width=w;height=h;centerNow();return;}
        panX+=(w-width)*.5f;panY+=(h-height)*.5f;width=w;height=h;clampPan();
    }
    public void select(int index){
        if(count==0)return;int next=bound(index);following=true;if(next==selected)return;
        int oldPatch=selected/PER_PATCH;selected=next;base(oldPatch);focus(selected);moving.set(oldPatch);moving.set(selected/PER_PATCH);
    }
    public void update(float seconds){
        float dt=Float.isFinite(seconds)?Math.max(0,Math.min(.1f,seconds)):0,decay=(float)Math.exp(-14*dt);
        hover.replaceAll((index,value)->approach(value,index==hovered?1:0,decay));
        hover.entrySet().removeIf(entry->entry.getKey()!=hovered&&entry.getValue()==0);
        for(int patch=moving.nextSetBit(0);patch>=0;patch=moving.nextSetBit(patch+1)){
            boolean active=false;for(int i=patch*PER_PATCH;i<Math.min(count,(patch+1)*PER_PATCH);i++){
                x[i]=approach(x[i],tx[i],decay);y[i]=approach(y[i],ty[i],decay);size[i]=approach(size[i],ts[i],decay);emphasis[i]=approach(emphasis[i],i==selected?1:0,decay);
                active|=x[i]!=tx[i]||y[i]!=ty[i]||size[i]!=ts[i]||emphasis[i]!=(i==selected?1:0);
            }if(!active)moving.clear(patch);
        }
        if(following&&!held&&count>0){float targetX=width*.5f-(x[selected]+size[selected]*.5f)*zoom,targetY=height*.5f-(y[selected]+size[selected]*.5f)*zoom;
            panX=approach(panX,targetX,decay);panY=approach(panY,targetY,decay);
        }
    }
    private static float approach(float value,float target,float decay){float next=target+(value-target)*decay;return Math.abs(next-target)<.001f?target:next;}
    /** Hover grows only into the seam; mosaic targets and pan/zoom anchors stay unchanged. */
    public Card card(int index){if(index<0||index>=count)return null;float amount=hover.getOrDefault(index,0f),grow=.45f*GAP*zoom*amount;return new Card(index,panX+x[index]*zoom-grow,panY+y[index]*zoom-grow,size[index]*zoom+2*grow,emphasis[index],amount);}
    public void hoverAt(float x,float y){if(held)return;hovered=hit(x,y);if(hovered>=0)hover.putIfAbsent(hovered,0f);}
    /** The mosaic has a direct spatial index; a frame visits only intersecting 40-cover patches. */
    public List<Card> visible(float margin){
        if(count==0||width<=0||height<=0)return List.of();float pad=Float.isFinite(margin)?Math.max(0,margin):0;
        int left=Math.max(0,(int)Math.floor((-pad-panX)/zoom/PATCH)),right=Math.min(patchColumns-1,(int)Math.floor((width+pad-panX)/zoom/PATCH));
        int top=Math.max(0,(int)Math.floor((-pad-panY)/zoom/PATCH)),bottom=Math.min(patchRows-1,(int)Math.floor((height+pad-panY)/zoom/PATCH));
        List<Card> result=new ArrayList<>();
        for(int row=top;row<=bottom;row++)for(int col=left;col<=right;col++){int slot=row*patchColumns+col;if(slot>=patchCount())continue;int patch=Math.floorMod(slot-patchOffset,patchCount());for(int i=patch*PER_PATCH;i<Math.min(count,(patch+1)*PER_PATCH);i++){
            Card card=card(i);if(card.x()+card.size()>=-pad&&card.y()+card.size()>=-pad&&card.x()<=width+pad&&card.y()<=height+pad)result.add(card);
        }}
        result.sort(Comparator.comparingInt((Card card)->card.index()==selected?1:0).thenComparingDouble(Card::emphasis).thenComparingInt(Card::index));return result;
    }
    /** Visible covers precede one surrounding band; idle cache preloading stays independent. */
    public List<Integer> preloadIndices(){
        List<Card> candidates=new ArrayList<>(visible(PITCH*zoom));candidates.sort(Comparator.comparingInt((Card card)->intersectsViewport(card)?0:1).thenComparingDouble(this::distance));
        List<Integer> result=new ArrayList<>(Math.min(PRELOAD_LIMIT,candidates.size()));for(Card card:candidates){if(result.size()>=PRELOAD_LIMIT)break;result.add(card.index());}return result;
    }
    public int hit(float x,float y){if(!inside(x,y))return -1;List<Card> cards=visible(0);for(int i=cards.size()-1;i>=0;i--)if(cards.get(i).contains(x,y))return cards.get(i).index();return -1;}
    public void begin(float x,float y){cancel();if(!finite(x,y)||!inside(x,y))return;held=true;following=false;startX=x;startY=y;originX=panX;originY=panY;pressed=hit(x,y);}
    public void drag(float x,float y){if(!held||!finite(x,y))return;if(Math.hypot(x-startX,y-startY)>=4)dragged=true;if(dragged){panX=originX+x-startX;panY=originY+y-startY;clampPan();}}
    /** Returns only a same-cover click; dragging and release outside never select. */
    public int release(float x,float y){if(!held)return -1;drag(x,y);int result=!dragged&&pressed>=0&&hit(x,y)==pressed?pressed:-1;cancel();return result;}
    public void cancel(){held=false;dragged=false;pressed=-1;}
    /** Scaling about the cursor preserves its world point, including at either zoom limit. */
    public void zoomAt(float x,float y,double amount){
        if(!finite(x,y)||!Double.isFinite(amount)||!inside(x,y))return;float next=(float)Math.max(MIN_ZOOM,Math.min(MAX_ZOOM,zoom*Math.exp(Math.max(-10,Math.min(10,amount))*.16)));
        float ratio=next/zoom;panX=x-(x-panX)*ratio;panY=y-(y-panY)*ratio;zoom=next;following=false;
        if(held){pressed=-1;dragged=true;startX=x;startY=y;originX=panX;originY=panY;}
    }
    public void pan(float dx,float dy){if(!finite(dx,dy))return;following=false;panX+=dx;panY+=dy;clampPan();}
    private int bound(int index){return Math.max(0,Math.min(Math.max(0,count-1),index));}
    private void centerNow(){if(count>0){panX=width*.5f-(x[selected]+size[selected]*.5f)*zoom;panY=height*.5f-(y[selected]+size[selected]*.5f)*zoom;}}
    private void clampPan(){if(count==0||width<=0||height<=0)return;panX=Math.max(width*.5f-(patchColumns*PATCH-GAP)*zoom,Math.min(width*.5f,panX));panY=Math.max(height*.5f-(patchRows*PATCH-GAP)*zoom,Math.min(height*.5f,panY));}
    private boolean inside(float x,float y){return x>=0&&y>=0&&x<width&&y<height;}
    private static boolean finite(float x,float y){return Float.isFinite(x)&&Float.isFinite(y);}
    private boolean intersectsViewport(Card card){return card.x()+card.size()>=0&&card.y()+card.size()>=0&&card.x()<=width&&card.y()<=height;}
    private double distance(Card card){return Math.hypot(card.x()+card.size()*.5f-width*.5f,card.y()+card.size()*.5f-height*.5f);}
    /** Eight merged 2x2 groups plus eight split groups exactly cover each 8x8 patch. */
    private void base(int patch){List<Tile> tiles=tiles(patch,-1);int first=patch*PER_PATCH;for(int i=first;i<Math.min(count,first+PER_PATCH);i++){Tile tile=tiles.get(i-first);tx[i]=tile.x();ty[i]=tile.y();ts[i]=tile.size();if(size[i]==0){x[i]=tx[i];y[i]=ty[i];size[i]=ts[i];}}}
    /** A 4x4 focus replaces four groups; three merged and nine split groups fill the rest. */
    private void focus(int index){
        int patch=index/PER_PATCH,first=patch*PER_PATCH,last=Math.min(count,first+PER_PATCH);List<Tile> baseline=tiles(patch,-1);Tile original=baseline.get(index-first);
        int spatial=patchSlot(patch);float ox=(spatial%patchColumns)*PATCH,oy=(spatial/patchColumns)*PATCH;
        int quadrant=(original.cx()-ox>=PATCH*.5f?1:0)+(original.cy()-oy>=PATCH*.5f?2:0);List<Tile> available=tiles(patch,quadrant);Tile expanded=available.remove(0);tx[index]=expanded.x();ty[index]=expanded.y();ts[index]=expanded.size();
        // Nearest available positions keep movement inside the selected patch and favor existing sizes.
        List<Integer> remaining=new ArrayList<>();for(int i=first;i<last;i++)if(i!=index)remaining.add(i);
        remaining.sort(Comparator.comparingDouble(i->Math.hypot(x[i]+size[i]*.5f-expanded.cx(),y[i]+size[i]*.5f-expanded.cy())));
        for(int i:remaining){int nearest=0;double best=Double.MAX_VALUE;for(int slot=0;slot<available.size();slot++){Tile tile=available.get(slot);double dx=x[i]+size[i]*.5f-tile.cx(),dy=y[i]+size[i]*.5f-tile.cy(),ds=size[i]-tile.size(),cost=dx*dx+dy*dy+ds*ds*.65;if(cost<best){best=cost;nearest=slot;}}Tile tile=available.remove(nearest);tx[i]=tile.x();ty[i]=tile.y();ts[i]=tile.size();}
    }
    private List<Tile> tiles(int patch,int quadrant){
        int spatial=patchSlot(patch);float ox=(spatial%patchColumns)*PATCH,oy=(spatial/patchColumns)*PATCH;List<Integer> groups=new ArrayList<>();int qx=quadrant<0?-10:(quadrant%2)*2,qy=quadrant<0?-10:(quadrant/2)*2;
        for(int group=0;group<16;group++){int gx=group%4,gy=group/4;if(gx>=qx&&gx<qx+2&&gy>=qy&&gy<qy+2)continue;groups.add(group);}
        groups.sort(Comparator.comparingLong(group->Integer.toUnsignedLong(mix(patch*73+group*131))));int merges=quadrant<0?8:3;BitSet merged=new BitSet();for(int i=0;i<merges;i++)merged.set(groups.get(i));
        List<Tile> result=new ArrayList<>(PER_PATCH);if(quadrant>=0)result.add(new Tile(ox+qx*2*PITCH,oy+qy*2*PITCH,4*PITCH-GAP));
        List<Tile> rest=new ArrayList<>();for(int group:groups){int gx=group%4,gy=group/4;float px=ox+gx*2*PITCH,py=oy+gy*2*PITCH;if(merged.get(group))rest.add(new Tile(px,py,2*PITCH-GAP));else for(int sub=0;sub<4;sub++)rest.add(new Tile(px+(sub%2)*PITCH,py+(sub/2)*PITCH,CARD));}
        rest.sort(Comparator.comparingDouble(Tile::y).thenComparingDouble(Tile::x));result.addAll(rest);return result;
    }
    private static int mix(int value){value^=value>>>16;value*=0x7feb352d;value^=value>>>15;value*=0x846ca68b;return value^(value>>>16);}
}
