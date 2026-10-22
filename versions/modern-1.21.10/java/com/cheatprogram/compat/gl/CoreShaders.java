package com.cheatprogram.compat.gl;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

/** Small owned shaders for immediate overlay meshes; native items still use vanilla RenderPipelines. */
public enum CoreShaders {
    POSITION_COLOR, POSITION_TEX_COLOR, RENDERTYPE_LINES, RENDERTYPE_ENTITY_SOLID;
    private CompiledShaderProgram shader;
    public CompiledShaderProgram program(){
        if(shader!=null)return shader;
        boolean texture=this==POSITION_TEX_COLOR||this==RENDERTYPE_ENTITY_SOLID;
        boolean line=this==RENDERTYPE_LINES;
        VertexFormat format=line?DefaultVertexFormat.POSITION_COLOR_NORMAL:texture?DefaultVertexFormat.POSITION_TEX_COLOR:DefaultVertexFormat.POSITION_COLOR;
        String vertex="#version 150\nin vec3 Position; in vec4 Color; out vec4 vertexColor; uniform mat4 ModelViewMat; uniform mat4 ProjMat;\n";
        if(texture)vertex+="in vec2 UV0; out vec2 texCoord;\n";
        if(line)vertex+="in vec3 Normal; uniform float LineWidth; uniform vec2 ScreenSize;\n";
        vertex+="void main(){vec4 clip=ProjMat*ModelViewMat*vec4(Position,1.0); vertexColor=Color;";
        if(texture)vertex+="texCoord=UV0;";
        if(line)vertex+="vec4 end=ProjMat*ModelViewMat*vec4(Position+Normal,1.0); vec2 delta=(end.xy/end.w-clip.xy/clip.w)*ScreenSize; float n=length(delta); vec2 side=n>0.00001?vec2(-delta.y,delta.x)/n:vec2(0.0,1.0); clip.xy+=side*(LineWidth/ScreenSize)*clip.w*((gl_VertexID%2)==0?1.0:-1.0);";
        vertex+="gl_Position=clip;}";
        String fragment="#version 150\nin vec4 vertexColor; uniform vec4 ColorModulator; out vec4 fragColor;\n";
        if(texture)fragment+="in vec2 texCoord; uniform sampler2D Sampler0;\n";
        fragment+="void main(){fragColor=vertexColor*ColorModulator"+(texture?"*texture(Sampler0,texCoord)":"")+"; if(fragColor.a<0.001)discard;}";
        return shader=new CompiledShaderProgram(vertex,fragment,format);
    }
}
