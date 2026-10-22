package com.cheatprogram.compat.gl;

import com.google.gson.*;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.*;
import org.lwjgl.opengl.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Own GLSL programs, retaining the original glass uniforms across the vanilla pipeline migration. */
public final class CompiledShaderProgram implements AutoCloseable {
    private final int id;
    private final Map<String,Uniform> uniforms=new LinkedHashMap<>();
    private final Map<String,Integer> samplers=new LinkedHashMap<>();
    public final Uniform LIGHT0_DIRECTION, LIGHT1_DIRECTION;
    public CompiledShaderProgram(String vertex,String fragment,VertexFormat format) {
        int v=compile(GL20.GL_VERTEX_SHADER,vertex), f=0, program=0;
        try {
            f=compile(GL20.GL_FRAGMENT_SHADER,fragment);program=GL20.glCreateProgram();
            GL20.glAttachShader(program,v);GL20.glAttachShader(program,f);
            var names=format.getElements();for(int i=0;i<names.size();i++)GL20.glBindAttribLocation(program,i,names.get(i).name());
            GL20.glLinkProgram(program);
            if(GL20.glGetProgrami(program,GL20.GL_LINK_STATUS)==0)throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            id=program;
        } catch(RuntimeException error){if(program!=0)GL20.glDeleteProgram(program);throw error;}
        finally {GL20.glDeleteShader(v);if(f!=0)GL20.glDeleteShader(f);}
        for(int i=0;i<12;i++)if(GL20.glGetUniformLocation(id,"Sampler"+i)>=0)samplers.put("Sampler"+i,i);
        LIGHT0_DIRECTION=safeGetUniform("Light0_Direction");LIGHT1_DIRECTION=safeGetUniform("Light1_Direction");
    }
    private static int compile(int type,String source) {
        int shader=GL20.glCreateShader(type);GL20.glShaderSource(shader,source);GL20.glCompileShader(shader);
        if(GL20.glGetShaderi(shader,GL20.GL_COMPILE_STATUS)==0){String message=GL20.glGetShaderInfoLog(shader);GL20.glDeleteShader(shader);throw new IllegalStateException(message);}
        return shader;
    }
    public static CompiledShaderProgram fromMod(String name,VertexFormat format)throws IOException {
        JsonObject config=JsonParser.parseString(read("shaders/core/"+name+".json")).getAsJsonObject();
        String vertex=config.get("vertex").getAsString().split(":",2)[1];
        String fragment=config.get("fragment").getAsString().split(":",2)[1];
        CompiledShaderProgram program=new CompiledShaderProgram(read("shaders/"+vertex+".vsh"),read("shaders/"+fragment+".fsh"),format);
        try {
            if(config.has("samplers")){
                program.samplers.clear();int unit=0;
                for(var entry:config.getAsJsonArray("samplers"))program.samplers.put(entry.getAsJsonObject().get("name").getAsString(),unit++);
                if(unit>12)throw new IllegalArgumentException("Too many shader samplers");
            }
            for(var entry:config.getAsJsonArray("uniforms")) {
                JsonObject uniform=entry.getAsJsonObject();String key=uniform.get("name").getAsString();int count=uniform.get("count").getAsInt();
                float[] values=new float[count];var initial=uniform.getAsJsonArray("values");
                for(int i=0;i<count;i++)values[i]=initial.get(java.lang.Math.min(i,initial.size()-1)).getAsFloat();
                Uniform target=program.safeGetUniform(key);target.matrix=uniform.get("type").getAsString().startsWith("matrix");target.set(values);
            }
            return program;
        } catch(RuntimeException error){program.close();throw error;}
    }
    private static String read(String path)throws IOException {
        try(var input=CompiledShaderProgram.class.getResourceAsStream("/assets/cheatprogram/"+path)) {
            if(input==null)throw new FileNotFoundException(path);return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    public Uniform safeGetUniform(String name){return uniforms.computeIfAbsent(name,key->new Uniform(GL20.glGetUniformLocation(id,key)));}
    public Uniform getUniform(String name){Uniform u=safeGetUniform(name);return u.location<0?null:u;}
    public int getId(){return id;}
    public void bindSampler(String name,int texture){
        Integer unit=samplers.get(name);if(unit==null)throw new IllegalArgumentException("Undeclared sampler "+name);
        RenderSystem.setShaderTexture(unit,texture);
    }
    public void apply() {
        GlStateManager._glUseProgram(id);
        safeGetUniform("ModelViewMat").set(RenderSystem.getModelViewStack());safeGetUniform("ProjMat").set(RenderSystem.getProjectionMatrix());
        safeGetUniform("TextureMat").set(RenderSystem.getTextureMatrix());safeGetUniform("ColorModulator").set(RenderSystem.getShaderColor());
        safeGetUniform("LineWidth").set(RenderSystem.getShaderLineWidth());
        int[] viewport=new int[4];GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);safeGetUniform("ScreenSize").set((float)viewport[2],(float)viewport[3]);
        RenderSystem.setupShaderLights(this);
        for(var u:uniforms.values())u.upload();
        for(var entry:samplers.entrySet()){int at=GL20.glGetUniformLocation(id,entry.getKey());if(at>=0)GL20.glUniform1i(at,entry.getValue());}
    }
    public void clear(){GlStateManager._glUseProgram(0);}
    @Override public void close(){GL20.glDeleteProgram(id);}
    public static final class Uniform {
        private final int location;
        private float[] values={0};
        private boolean matrix;
        Uniform(int location){this.location=location;}
        public void set(float... values){this.values=values.clone();}
        public void set(Vector3f vector){set(vector.x,vector.y,vector.z);}
        public void set(Matrix4f matrix){this.matrix=true;this.values=new float[16];matrix.get(this.values);}
        public FloatBuffer getFloatBuffer(){return FloatBuffer.wrap(values);}
        void upload(){
            if(location<0)return;
            if(matrix){if(values.length!=16)throw new IllegalStateException("Expected 4x4 uniform");GL20.glUniformMatrix4fv(location,false,values);return;}
            switch(values.length){case 1->GL20.glUniform1f(location,values[0]);case 2->GL20.glUniform2fv(location,values);case 3->GL20.glUniform3fv(location,values);case 4->GL20.glUniform4fv(location,values);default->GL20.glUniform1fv(location,values);}
        }
    }
}
