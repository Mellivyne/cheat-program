#version 150

uniform sampler2D Sampler0;
uniform vec2 Size;
uniform float Radius;
uniform vec4 ImageTransform;
uniform vec4 CropBounds;
uniform vec4 Glass;     // refraction pixels, bevel pixels, frost, refraction strength
uniform vec4 ImageTone; // brightness, final alpha, AA pixels, unused
uniform vec4 ColorModulator;
in vec2 localPos;
in vec4 vertexColor;
out vec4 fragColor;

float distanceToPanel(vec2 p) {
    float r=min(Radius,min(Size.x,Size.y)*.5);
    vec2 q=abs(p)-Size*.5+r;
    return min(max(q.x,q.y),0.0)+length(max(q,0.0))-r;
}
vec4 photo(vec2 pixels) {
    vec2 uv=(pixels/Size)*ImageTransform.xy+ImageTransform.zw;
    vec2 inset=min(vec2(.0001),(CropBounds.zw-CropBounds.xy)*.25);
    return texture(Sampler0,clamp(uv,CropBounds.xy+inset,CropBounds.zw-inset));
}
void main() {
    vec2 p=localPos-Size*.5;
    float d=distanceToPanel(p);
    // Never paint beyond the original rounded panel, including its animated opening scale.
    float aa=max(fwidth(d),ImageTone.z);
    float coverage=1.0-smoothstep(-aa,0.0,d);
    if(coverage<=0.0) discard;
    vec2 e=vec2(.75,0.0);
    vec2 n=vec2(distanceToPanel(p+e.xy)-distanceToPanel(p-e.xy),distanceToPanel(p+e.yx)-distanceToPanel(p-e.yx));
    float nl=length(n); n=nl>.0001?n/nl:vec2(0.0,-1.0);
    float edge=clamp(1.0+d/max(Glass.y,1.0),0.0,1.0);
    float curve=edge*edge*(3.0-2.0*edge);
    float shift=Glass.x*curve*curve*min(Glass.w,1.5);
    vec2 bent=localPos-n*shift;
    vec4 sharp=photo(bent);
    float blur=Glass.z*11.0;
    vec4 frosted=(sharp*4.0+photo(bent+vec2(blur,blur))+photo(bent+vec2(-blur,blur))+
                  photo(bent+vec2(blur,-blur))+photo(bent-vec2(blur,blur)))/8.0;
    vec3 col=mix(sharp.rgb,frosted.rgb,Glass.z)*ImageTone.x;
    // A restrained directional sheen follows the curved edge without a solid outline.
    float sheen=pow(max(0.0,dot(n,normalize(vec2(-.55,-.85)))),5.0)*curve*.09*min(Glass.w,1.5);
    col=mix(col,vec3(1.0),sheen+Glass.z*.025);
    fragColor=vec4(clamp(col,0.0,1.0),sharp.a*ImageTone.y*coverage)*ColorModulator;
}
