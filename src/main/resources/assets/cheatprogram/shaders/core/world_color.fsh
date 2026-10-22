#version 150
uniform sampler2D InSampler;
uniform vec4 Params;
uniform vec4 Tint;
uniform vec2 HalfPixel;
in vec2 texCoord;
out vec4 fragColor;
void main(){
    vec3 original=texture(InSampler,texCoord).rgb;
    float luma=dot(original,vec3(.2126,.7152,.0722));
    vec3 color=mix(vec3(luma),original,Params.y)*Params.x;
    color*=mix(vec3(1),Tint.rgb,Tint.w);
    vec3 glow=vec3(0);
    if(Params.z>.001){
        glow=max(original-vec3(.68),vec3(0));
        glow+=max(texture(InSampler,texCoord+vec2(4,0)*HalfPixel).rgb-vec3(.68),vec3(0));
        glow+=max(texture(InSampler,texCoord-vec2(4,0)*HalfPixel).rgb-vec3(.68),vec3(0));
        glow+=max(texture(InSampler,texCoord+vec2(0,4)*HalfPixel).rgb-vec3(.68),vec3(0));
        glow+=max(texture(InSampler,texCoord-vec2(0,4)*HalfPixel).rgb-vec3(.68),vec3(0));
    }
    color+=glow/5*Params.z;
    fragColor=vec4(mix(original,clamp(color,0,1),Params.w),1);
}
