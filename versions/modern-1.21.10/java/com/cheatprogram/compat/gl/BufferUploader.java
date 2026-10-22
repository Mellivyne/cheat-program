package com.cheatprogram.compat.gl;
import com.mojang.blaze3d.vertex.MeshData;
/** Reuses one GPU buffer for immediate meshes; persistent geometry uses its own VertexBuffer. */
public final class BufferUploader {
    private static VertexBuffer immediate;
    private BufferUploader(){}
    public static void invalidate(){/* bind() always rebinds its VAO. */}
    public static void drawWithShader(MeshData mesh){
        if(mesh==null)return;
        if(immediate==null)immediate=new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        immediate.bind();immediate.upload(mesh);immediate.draw();
    }
}