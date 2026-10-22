package com.cheatprogram.capture;

/** Ordered relative cursor positions mapped to a resolution-independent source point. */
public final class AppCastPointerState {
    public record Mapping(int windowWidth,int windowHeight,int framebufferWidth,int framebufferHeight,int clientWidth,int clientHeight) {
        private boolean valid(){return windowWidth>0&&windowHeight>0&&framebufferWidth>0&&framebufferHeight>0&&clientWidth>0&&clientHeight>0;}
        private boolean sameCoordinateSpace(Mapping other){return other!=null&&windowWidth==other.windowWidth&&windowHeight==other.windowHeight&&framebufferWidth==other.framebufferWidth&&framebufferHeight==other.framebufferHeight;}
    }
    private double u,v,lastX,lastY;private Mapping mapping;private boolean initialized;
    public AppCastPointerState(double u,double v){this.u=clamp(u);this.v=clamp(v);}
    public double u(){return u;}public double v(){return v;}
    /** Focus/cursor ownership changes invalidate relative coordinates, never the source point. */
    public void reset(){initialized=false;mapping=null;}
    /** Establish after a lifecycle change, before accepting any further motion. */
    public void baseline(double x,double y,Mapping next){reset();if(!valid(x,y,next))return;lastX=x;lastY=y;mapping=next;initialized=true;}
    public boolean move(double x,double y,Mapping next,double speed){
        if(!valid(x,y,next)||!Double.isFinite(speed)||speed<=0){reset();return false;}
        if(!initialized||!next.sameCoordinateSpace(mapping)){baseline(x,y,next);return false;}
        double previousU=u,previousV=v;
        // Source resize preserves UV, but uses the new native size for the next
        // delta. Only a change to GLFW's coordinate space invalidates its baseline.
        u=clamp(u+(x-lastX)*next.framebufferWidth/next.windowWidth*speed/next.clientWidth);
        v=clamp(v+(y-lastY)*next.framebufferHeight/next.windowHeight*speed/next.clientHeight);
        lastX=x;lastY=y;mapping=next;return u!=previousU||v!=previousV;
    }
    private static boolean valid(double x,double y,Mapping mapping){return Double.isFinite(x)&&Double.isFinite(y)&&mapping!=null&&mapping.valid();}
    private static double clamp(double value){return Double.isFinite(value)?Math.max(0,Math.min(1,value)):0;}
}
