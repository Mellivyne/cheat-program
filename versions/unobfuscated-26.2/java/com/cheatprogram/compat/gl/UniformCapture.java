package com.cheatprogram.compat.gl;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** CPU mirrors captured when vanilla writes its GPU uniforms. Never reads back the GPU. */
public final class UniformCapture {
    private static final Map<GpuBuffer,Map<Long,Matrix4f>> projections=new WeakHashMap<>();
    private static final Map<GpuBuffer,Map<Long,Vector3f[]>> lights=new WeakHashMap<>();
    private UniformCapture(){}
    public static void projection(GpuBufferSlice slice,Matrix4f matrix){
        projections.computeIfAbsent(slice.buffer(),key->new HashMap<>()).put(slice.offset(),new Matrix4f(matrix));
    }
    public static Matrix4f projection(GpuBufferSlice slice){
        if(slice==null)return new Matrix4f();
        var entries=projections.get(slice.buffer());var matrix=entries==null?null:entries.get(slice.offset());
        if(matrix==null)throw new IllegalStateException("Projection buffer has no captured matrix");
        return new Matrix4f(matrix);
    }
    public static void lighting(GpuBufferSlice slice,org.joml.Vector3fc a,org.joml.Vector3fc b){
        lights.computeIfAbsent(slice.buffer(),key->new HashMap<>()).put(slice.offset(),new Vector3f[]{new Vector3f(a),new Vector3f(b)});
    }
    public static void applyLights(GpuBufferSlice slice,CompiledShaderProgram shader){
        if(slice==null)return;
        var entries=lights.get(slice.buffer());var pair=entries==null?null:entries.get(slice.offset());
        if(pair==null)throw new IllegalStateException("Lighting buffer has no captured directions");
        shader.LIGHT0_DIRECTION.set(pair[0]);shader.LIGHT1_DIRECTION.set(pair[1]);
    }
    public static void clear(){projections.clear();lights.clear();}
}
