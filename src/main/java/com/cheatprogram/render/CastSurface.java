package com.cheatprogram.render;

/** Shared tessellated surface for projection, picking and the software cursor. Width/height are arc lengths. */
public record CastSurface(double width,double height,double degrees,boolean vertical,int subdivisions) {
    public record Point(double x,double y,double z) {
        public Point add(Point p){return new Point(x+p.x,y+p.y,z+p.z);}
        public Point subtract(Point p){return new Point(x-p.x,y-p.y,z-p.z);}
        public Point scale(double s){return new Point(x*s,y*s,z*s);}
        public double dot(Point p){return x*p.x+y*p.y+z*p.z;}
        public Point cross(Point p){return new Point(y*p.z-z*p.y,z*p.x-x*p.z,x*p.y-y*p.x);}
        public Point normalized(){return scale(1/Math.sqrt(dot(this)));}
    }
    public record Hit(double u,double v,double distance) {}
    public CastSurface {
        width=Double.isFinite(width)?Math.max(.001,width):3;
        height=Double.isFinite(height)?Math.max(.001,height):2;
        degrees=Double.isFinite(degrees)?Math.max(-160,Math.min(160,degrees)):0;
        subdivisions=Math.max(8,Math.min(128,subdivisions));
    }
    public int segments(){return Math.abs(degrees)<.001?1:subdivisions;}
    private Point edge(double coordinate){
        double t=vertical?.5-coordinate:coordinate-.5;
        double length=vertical?height:width,angle=Math.toRadians(degrees),s=t*length,z=0;
        if(Math.abs(angle)>.00001){s=length*Math.sin(t*angle)/angle;z=length*(1-Math.cos(t*angle))/angle;}
        return vertical?new Point(0,s,z):new Point(s,0,z);
    }
    /** Interpolate exactly on the rendered strip rather than on an idealized cylinder. */
    public Point point(double u,double v){
        double c=vertical?v:u;int n=segments(),i=Math.max(0,Math.min(n-1,(int)Math.floor(c*n)));
        Point a=edge(i/(double)n),b=edge((i+1)/(double)n);
        Point p=a.add(b.subtract(a).scale(c*n-i));
        return p.add(vertical?new Point((u-.5)*width,0,0):new Point(0,(.5-v)*height,0));
    }
    public Point normal(double u,double v){
        int n=segments(),i=Math.max(0,Math.min(n-1,(int)Math.floor((vertical?v:u)*n)));
        Point tangent=edge((i+1)/(double)n).subtract(edge(i/(double)n));
        return (vertical?tangent.cross(new Point(1,0,0)):new Point(0,-1,0).cross(tangent)).normalized();
    }
    public Hit pick(Point origin,Point ray,boolean bounded){
        Hit best=null;int n=segments();
        for(int i=0;i<n;i++){
            double a=i/(double)n,b=(i+1)/(double)n;
            Point p=point(vertical?0:a,vertical?a:0);
            Point du=point(vertical?1:b,vertical?a:0).subtract(p);
            Point dv=point(vertical?0:a,vertical?b:1).subtract(p);
            Point normal=dv.cross(du);double denominator=normal.dot(ray);
            if(Math.abs(denominator)<1e-12)continue;
            double t=normal.dot(p.subtract(origin))/denominator;
            if(t<.05||t>512||best!=null&&t>=best.distance())continue;
            Point local=origin.add(ray.scale(t)).subtract(p);
            double x=local.dot(du)/du.dot(du),y=local.dot(dv)/dv.dot(dv);
            double along=vertical?y:x,across=vertical?x:y;
            double eps=1e-8;
            if((along< -eps&&(bounded||i!=0))||(along>1+eps&&(bounded||i!=n-1)))continue;
            if(bounded&&(across< -eps||across>1+eps))continue;
            best=new Hit(vertical?x:(i+x)/n,vertical?(i+y)/n:y,t);
        }
        return best;
    }
}
