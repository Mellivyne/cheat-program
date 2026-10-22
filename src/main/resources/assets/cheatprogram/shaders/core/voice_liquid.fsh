#version 150
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 ScreenSize;
uniform vec2 Size;
uniform vec4 Flow; // integrated circulation phase, actual smoothed microphone level, result morph, pixel scale
uniform vec4 ColorModulator;
in vec2 localPos;
in vec4 vertexColor;
out vec4 fragColor;

float box(vec2 p,vec2 halfSize,float radius){
    vec2 q=abs(p)-halfSize+radius;
    return min(max(q.x,q.y),0.0)+length(max(q,0.0))-radius;
}
float sq(float x){return x*x;}
void main(){
    vec2 halfSize=Size*.5,p=localPos-halfSize;
    float morph=clamp(Flow.z,0.0,1.0),scale=max(Flow.w,.01);
    float radius=min(halfSize.y,mix(halfSize.y,23.0*scale,morph));
    float d=box(p,halfSize,radius),aa=max(fwidth(d),.25);
    float coverage=1.0-smoothstep(.4*scale,1.7*scale,d);
    if(coverage<=0.0)discard;
    vec2 e=vec2(.6*scale,0.0);
    vec2 n=vec2(box(p+e.xy,halfSize,radius)-box(p-e.xy,halfSize,radius),box(p+e.yx,halfSize,radius)-box(p-e.yx,halfSize,radius));
    n=length(n)>.0001?normalize(n):vec2(0.0,-1.0);
    vec2 q=p/max(halfSize,vec2(1.0));
    float dome=sqrt(max(.0,1.0-dot(q,q)*.91));
    float rim=1.0-smoothstep(0.0,max(3.0*scale,halfSize.y*.30),-d);
    vec2 uv=gl_FragCoord.xy/ScreenSize;
    vec2 bend=vec2(-n.x,n.y)*(12.0*scale*rim*rim)+vec2(-q.x,q.y)*(6.5*scale*dome);
    vec2 sampleUV=clamp(uv+bend/ScreenSize,vec2(.001),vec2(.999));
    vec3 scene=mix(texture(Sampler0,sampleUV).rgb,texture(Sampler1,sampleUV).rgb,.88);
    // Two immiscible bodies share one advected interface; neither layer crosses it.
    float x=q.x,phase=Flow.x;
    float circulation=sin(x*3.7+phase)*.057+sin(x*7.3-phase*.5)*.026;
    float fold=sin(x*2.1-phase*1.5)*sin(x*4.5+phase)*(.024+Flow.y*.052);
    float boundary=-.02+circulation+fold;
    float fluidWidth=max(.007,aa/max(halfSize.y,1.0));
    float lower=smoothstep(boundary-fluidWidth,boundary+fluidWidth,q.y);
    float inner=1.0-smoothstep(.78,1.01,length(q*vec2(.91,1.0)));
    float whiteDensity=mix(.12,.80,inner)*(.83+.17*dome);
    float blackDensity=mix(.67,.985,inner);
    vec3 ink=mix(vec3(.005),vec3(.995),lower);
    float density=mix(blackDensity,whiteDensity,lower);
    vec3 col=mix(scene,ink,density);
    // A bright meniscus and restrained circulation shading reveal the curved liquid contact.
    float meniscus=exp(-sq((q.y-boundary)/.022))*(.14+.19*dome);
    col=mix(col,vec3(1.0),meniscus*(1.0-morph));
    float top=1.0-smoothstep(-.32,.48,q.y);
    float insideRim=exp(-sq((d+1.4*scale)/(1.15*scale)))*top;
    col=mix(col,vec3(0.0),insideRim*.92);
    float outsideRim=smoothstep(-.2*scale,.55*scale,d)*top;
    col=mix(col,vec3(0.0),outsideRim);
    // Monochrome grazing reflections; no dispersion, hue animation or colored emission.
    float glint=exp(-sq((d+2.6*scale)/(1.2*scale)))*(1.0-top)*.74;
    glint+=exp(-sq((q.x+.46)/.20)-sq((q.y+.69)/.045))*.40;
    col=mix(col,vec3(1.0),clamp(glint,0.0,.78));
    vec3 pill=mix(scene,vec3(.003),.93);
    pill=mix(pill,vec3(1.0),glint*.35);
    col=mix(col,pill,morph);
    fragColor=vec4(clamp(col,0.0,1.0),coverage)*ColorModulator;
}
