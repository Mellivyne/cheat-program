package com.cheatprogram.compat.gl;
import com.mojang.blaze3d.vertex.*;
import org.joml.Matrix4f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.*;

/** GPU-resident cached geometry; model, building, ore and ray meshes upload only when rebuilt. */
public final class VertexBuffer implements AutoCloseable {
    public enum Usage {STATIC,DYNAMIC}
    private final int vao=GL30.glGenVertexArrays(),vbo=GL15.glGenBuffers(),ibo=GL15.glGenBuffers();
    private final int usage;
    private MeshData.DrawState state;
    private int indexType,mode;
    private boolean indexed,closed;
    public VertexBuffer(Usage usage){this.usage=usage==Usage.STATIC?GL15.GL_STATIC_DRAW:GL15.GL_STREAM_DRAW;}
    public void bind(){GlStateManager._glBindVertexArray(vao);}
    public static void unbind(){GlStateManager._glBindVertexArray(0);}
    public boolean isInvalid(){return closed;}
    public void upload(MeshData mesh){
        try(mesh){
            if(closed)throw new IllegalStateException("Vertex buffer closed");
            state=mesh.drawState();var format=state.format();bind();
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER,vbo);GL15.glBufferData(GL15.GL_ARRAY_BUFFER,mesh.vertexBuffer(),usage);
            for(int i=0;i<16;i++)GL20.glDisableVertexAttribArray(i);
            var elements=format.getElements();
            for(int i=0;i<elements.size();i++){
                var e=elements.get(i);int type=switch(e.type()){case FLOAT->GL11.GL_FLOAT;case BYTE->GL11.GL_BYTE;case UBYTE->GL11.GL_UNSIGNED_BYTE;case SHORT->GL11.GL_SHORT;case USHORT->GL11.GL_UNSIGNED_SHORT;case INT->GL11.GL_INT;case UINT->GL11.GL_UNSIGNED_INT;};
                boolean normalized=e.normalized();
                GL20.glEnableVertexAttribArray(i);
                if(e.type()!=VertexFormatElement.Type.FLOAT&&!normalized)GL30.glVertexAttribIPointer(i,e.count(),type,format.getVertexSize(),format.getOffset(e));
                else GL20.glVertexAttribPointer(i,e.count(),type,normalized,format.getVertexSize(),format.getOffset(e));
            }
            indexed=state.mode()==VertexFormat.Mode.QUADS||state.mode()==VertexFormat.Mode.LINES||mesh.indexBuffer()!=null;
            if(indexed){
                GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER,ibo);indexType=state.indexType()==VertexFormat.IndexType.SHORT?GL11.GL_UNSIGNED_SHORT:GL11.GL_UNSIGNED_INT;mode=GL11.GL_TRIANGLES;
                if(mesh.indexBuffer()!=null)GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,mesh.indexBuffer(),usage);
                else {
                    int bytes=state.indexType().bytes;ByteBuffer indices=MemoryUtil.memAlloc(state.indexCount()*bytes).order(ByteOrder.nativeOrder());
                    try{for(int q=0;q<state.vertexCount()/4;q++)for(int offset:new int[]{0,1,2,2,3,0}){int index=q*4+offset;if(bytes==2)indices.putShort((short)index);else indices.putInt(index);}indices.flip();GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER,indices,usage);}
                    finally{MemoryUtil.memFree(indices);}
                }
            }else mode=switch(state.mode()){case DEBUG_LINES->GL11.GL_LINES;case DEBUG_LINE_STRIP->GL11.GL_LINE_STRIP;case TRIANGLE_STRIP->GL11.GL_TRIANGLE_STRIP;case TRIANGLE_FAN->GL11.GL_TRIANGLE_FAN;default->GL11.GL_TRIANGLES;};
        }
    }
    public void draw(){
        if(closed||state==null)return;
        var shader=RenderSystem.getShader();if(shader==null)throw new IllegalStateException("No custom shader selected");bind();shader.apply();
        if(indexed)GL11.glDrawElements(mode,state.indexCount(),indexType,0L);else GL11.glDrawArrays(mode,0,state.vertexCount());
    }
    public void drawWithShader(Matrix4f view,Matrix4f projection,CompiledShaderProgram program){
        Matrix4f modelCopy=new Matrix4f(view),projectionCopy=new Matrix4f(projection),oldProjection=new Matrix4f(RenderSystem.getProjectionMatrix());
        var oldType=RenderSystem.getProjectionType();var oldShader=RenderSystem.getShader();var model=RenderSystem.getModelViewStack();model.pushMatrix();
        try{model.set(modelCopy);RenderSystem.setProjectionMatrix(projectionCopy,oldType);RenderSystem.setShader(program);draw();}
        finally{model.popMatrix();RenderSystem.setProjectionMatrix(oldProjection,oldType);if(oldShader==null)RenderSystem.clearShader();else RenderSystem.setShader(oldShader);}
    }
    @Override public void close(){if(closed)return;closed=true;GL15.glDeleteBuffers(vbo);GL15.glDeleteBuffers(ibo);GL30.glDeleteVertexArrays(vao);}
}
