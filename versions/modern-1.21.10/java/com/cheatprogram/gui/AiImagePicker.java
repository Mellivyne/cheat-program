package com.cheatprogram.gui;

import com.cheatprogram.ai.AiImage;
import com.cheatprogram.core.Lang;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Reusable explicit attachment strip. File contents and thumbnails are never persisted. */
public final class AiImagePicker implements AutoCloseable {
    private static final java.util.concurrent.ExecutorService FILES=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Cheat Program AI image picker");t.setDaemon(true);return t;});
    private static final AtomicLong IDS=new AtomicLong();
    private final List<AiImage> images=new ArrayList<>();
    private final java.util.Map<AiImage,ResourceLocation> textures=new java.util.IdentityHashMap<>();
    private long generation;
    private boolean choosing,closed;
    private String status="ui.ai.images.hint";
    public List<AiImage> images(){return List.copyOf(images);}
    public boolean isEmpty(){return images.isEmpty();}
    public void choose(){
        if(choosing||closed)return;if(images.size()>=AiImage.MAX_ATTACHMENTS){status="ui.ai.images.limit";return;}
        choosing=true;long token=++generation;int room=AiImage.MAX_ATTACHMENTS-images.size();
        FILES.execute(()->{
            List<AiImage> loaded=new ArrayList<>();String error="";
            try(MemoryStack stack=MemoryStack.stackPush()){
                var filters=stack.mallocPointer(5);for(String f:List.of("*.png","*.jpg","*.jpeg","*.bmp","*.webp"))filters.put(stack.UTF8(f));filters.flip();
                String picked=TinyFileDialogs.tinyfd_openFileDialog(Lang.t("ui.ai.images.add"),null,filters,"Images",true);
                if(picked!=null){String[] files=picked.split("\\|");if(files.length>room)error="ui.ai.images.limit";
                    else for(String file:files)loaded.add(AiImage.read(Path.of(file)));}
            }catch(Throwable failure){error="ui.ai.images.invalid";}
            String message=error;Minecraft.getInstance().execute(()->{if(closed||generation!=token)return;choosing=false;if(message.isEmpty()){images.addAll(loaded);status=images.isEmpty()?"ui.ai.images.hint":"ui.ai.images.ready";}else status=message;});
        });
    }
    public void clear(){generation++;choosing=false;for(var id:textures.values())if(id!=null)Minecraft.getInstance().getTextureManager().release(id);textures.clear();images.clear();status="ui.ai.images.hint";}
    private void remove(AiImage image){images.remove(image);ResourceLocation id=textures.remove(image);if(id!=null)Minecraft.getInstance().getTextureManager().release(id);}
    private ResourceLocation texture(AiImage image){
        if(textures.containsKey(image))return textures.get(image);
        try{NativeImage pixels=NativeImage.read(image.thumbnailPng());ResourceLocation id=ResourceLocation.fromNamespaceAndPath("cheatprogram","dynamic/ai-image/"+IDS.incrementAndGet());
            Minecraft.getInstance().getTextureManager().register(id,new DynamicTexture(()->"Cheat Program AI attachment",pixels));textures.put(image,id);return id;
        }catch(Exception failure){status="ui.ai.images.previewFailed";textures.put(image,null);return null;}
    }
    /** Height 58 logical units. Click a thumbnail to remove just that image. */
    public void draw(GuiBase gui,float x,float y,float width){
        closed=false;
        gui.button(this+".add",Lang.t(choosing?"ui.ai.images.loading":"ui.ai.images.add"),Icon.FOLDER,x,y,104,23,false,this::choose);
        gui.button(this+".clear",Lang.t("ui.ai.images.clear"),Icon.CLOSE,x+110,y,64,23,false,this::clear);
        gui.c.textC(GuiBase.REG,Lang.t("ui.ai.images.count",images.size()),x+181,y+12,8,Theme.TEXT_3);
        float gap=5,size=Math.min(24,Math.max(12,(width-7*gap)/8));
        for(int i=0;i<images.size();i++){AiImage image=images.get(i);float xx=x+i*(size+gap);ResourceLocation id=texture(image);
            if(id!=null)R2D.texture(id,gui.c.px(xx),gui.c.py(y+27),size*gui.c.scale,size*gui.c.scale,0,0,1,1,0xffffffff);
            gui.hit(xx,y+27,size,size).left(()->remove(image));
        }
        if(images.isEmpty()||!status.equals("ui.ai.images.ready"))gui.c.textC(GuiBase.REG,gui.c.ellipsize(GuiBase.REG,Lang.t(status),7.5f,width),x,y+43,7.5f,Theme.TEXT_3);
    }
    @Override public void close(){closed=true;clear();}
}
