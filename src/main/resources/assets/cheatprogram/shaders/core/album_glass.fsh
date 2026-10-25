#version 150
// Perspective liquid-glass sheets share the UI's captured backdrop and theme.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform vec2 CanvasSize;
uniform vec2 SceneSize;
uniform vec4 ViewRect;
uniform vec4 Glass;
uniform vec4 Rim;
uniform vec4 ColorModulator;
in vec2 localPos;
in vec4 vertexColor;
out vec4 fragColor;
vec3 backdrop(vec2 uv,float clarity){
    uv=clamp(uv,vec2(.0001),vec2(.9999));
    return mix(texture(Sampler0,uv).rgb,texture(Sampler1,uv).rgb,clarity);
}
void main(){
    vec2 p=localPos-vec2(.5),q=abs(p)-vec2(.465);
    float d=min(max(q.x,q.y),0.0)+length(max(q,0.0))-.035;
    float aa=max(fwidth(d),.000001),distancePixels=d/aa;
    float coverage=smoothstep(0.0,max(Rim.x,.8),-distancePixels+.5);
    if(coverage<=0.0)discard;
    float edge=clamp(1.0+distancePixels/max(Glass.y,1.0),0.0,1.0);
    float bend=smoothstep(0.0,1.0,edge);bend*=bend;
    vec2 n=vec2(dFdx(d),dFdy(d));n/=max(length(n),.00001);
    vec2 viewport=gl_FragCoord.xy/SceneSize;
    vec2 pixel=vec2(ViewRect.x+viewport.x*ViewRect.z,
        CanvasSize.y-ViewRect.y-ViewRect.w+viewport.y*ViewRect.w);
    vec2 uv=pixel/CanvasSize,inward=-n/CanvasSize;
    float shift=Glass.x*bend,clarity=clamp(Rim.w*smoothstep(0.0,.85,edge),0.0,1.0);
    vec3 col;
    col.r=backdrop(uv+inward*shift*(1.0+Glass.z),clarity).r;
    col.g=backdrop(uv+inward*shift,clarity).g;
    col.b=backdrop(uv+inward*shift*(1.0-Glass.z),clarity).b;
    col=mix(col,vertexColor.rgb,vertexColor.a);
    col=mix(col,vec3(1),Rim.z);
    // Premultiplied output composites into the same transparent album target.
    float alpha=coverage*ColorModulator.a;
    fragColor=vec4(clamp(col,0.0,1.0)*ColorModulator.rgb*alpha,alpha);
}
